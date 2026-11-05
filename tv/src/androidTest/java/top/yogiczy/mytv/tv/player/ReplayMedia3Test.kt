package top.yogiczy.mytv.tv.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import android.util.Log
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.LoadingInfo
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.BaseMediaSource
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SampleStream
import androidx.media3.exoplayer.source.SinglePeriodTimeline
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import top.yogiczy.mytv.core.data.entities.channel.ChannelLine
import top.yogiczy.mytv.core.data.media.PlaybackRange
import top.yogiczy.mytv.core.data.utils.ChannelPlayback
import top.yogiczy.mytv.tv.ui.screensold.videoplayer.player.Media3VideoPlayer
import top.yogiczy.mytv.tv.ui.screensold.videoplayer.player.VideoPlayer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.io.IOException

/** Production player tests: deterministic timelines plus an optional bounded real-source check. */
@RunWith(AndroidJUnit4::class)
@OptIn(UnstableApi::class)
class ReplayMedia3Test {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)

    /** Optional real-source check. URLs and programme times are passed at invocation. */
    @Test fun optionalReplaySourceStartsAtProgrammeTimeAndKeepsPlaying() {
        val args = InstrumentationRegistry.getArguments()
        val source = args.getString("replayLiveUrl")
        assumeTrue("Pass replayLiveUrl and programme timestamps", !source.isNullOrBlank())
        val start = requireNotNull(args.getString("replayStartAt")).toLong()
        val end = requireNotNull(args.getString("replayEndAt")).toLong()
        val seconds = (args.getString("replayObserveSeconds")?.toInt() ?: 45).coerceIn(15, 90)
        val line = ChannelLine(requireNotNull(source))
        val replayLine = line.copy(url = requireNotNull(ChannelPlayback.playbackUrl(line, start, end)))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val ready = CountDownLatch(1)
        val error = AtomicReference<VideoPlayer.PlaybackException>()
        val position = AtomicLong()
        val firstPosition = AtomicLong(Long.MAX_VALUE)
        val firstChunk = AtomicLong(Long.MAX_VALUE)
        val playing = java.util.concurrent.atomic.AtomicBoolean()
        lateinit var player: Media3VideoPlayer
        lateinit var engine: androidx.media3.exoplayer.ExoPlayer
        main {
            player = Media3VideoPlayer(instrumentation.targetContext, scope)
            player.initialize()
            val field = Media3VideoPlayer::class.java.getDeclaredField("videoPlayer").apply { isAccessible = true }
            engine = field.get(player) as androidx.media3.exoplayer.ExoPlayer
            engine.addAnalyticsListener(object : AnalyticsListener {
                override fun onLoadStarted(eventTime: AnalyticsListener.EventTime, loadEventInfo: LoadEventInfo,
                    mediaLoadData: MediaLoadData) {
                    if (mediaLoadData.dataType == C.DATA_TYPE_MEDIA && mediaLoadData.mediaStartTimeMs != C.TIME_UNSET) {
                        firstChunk.compareAndSet(Long.MAX_VALUE, mediaLoadData.mediaStartTimeMs)
                    }
                }
            })
            player.setVolume(0f)
            player.onReady { ready.countDown() }
            player.onIsPlayingChanged { playing.set(it) }
            player.onCurrentPositionChanged { firstPosition.compareAndSet(Long.MAX_VALUE, it); position.set(it) }
            player.onError { if (it != null) { error.set(it); ready.countDown() } }
            player.prepare(replayLine, PlaybackRange(start, end))
        }
        try {
            assertTrue("Replay preparation timed out", ready.await(30, TimeUnit.SECONDS))
            assertNull("Replay failed: ${error.get()}", error.get())
            var previousPosition = 0L
            repeat(seconds / 5) { step ->
                previousPosition = position.get()
                Thread.sleep(5_000)
                main {
                    val manifest = engine.currentManifest as? androidx.media3.exoplayer.hls.HlsManifest
                    Log.i("ReplaySourceTest", "seconds=${(step + 1) * 5}, offset=${position.get() - start}, " +
                        "playing=${engine.isPlaying}, duration=${engine.duration}, " +
                        "segments=${manifest?.mediaPlaylist?.segments?.size}, end=${manifest?.mediaPlaylist?.hasEndTag}")
                }
                assertNull("Replay failed after ${(step + 1) * 5}s: ${error.get()}", error.get())
            }
            assertTrue("First HLS request skipped the beginning: ${firstChunk.get()}", firstChunk.get() in 0..10_000)
            assertTrue("Replay started late: ${firstPosition.get() - start}", firstPosition.get() in start..start + 15_000)
            assertTrue("Replay stopped early", playing.get() && position.get() - previousPosition >= 3_000)
            assertTrue("Replay did not advance", position.get() - start >= (seconds - 5) * 1_000L)
            assertTrue("Replay jumped past programme start", position.get() - start <= (seconds + 15) * 1_000L)
        } finally {
            main { player.stop(); player.release(); scope.cancel() }
        }
    }

    private fun withPlayer(failures: Int = 0, block: (Media3VideoPlayer, Long) -> Unit) {
        val windowStart = System.currentTimeMillis() - 7_200_000
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        lateinit var player: Media3VideoPlayer
        main {
            player = Media3VideoPlayer(instrumentation.targetContext, scope, TimelineFactory(windowStart, failures))
            player.initialize()
        }
        try { block(player, windowStart) }
        finally { main { player.release(); scope.cancel() } }
    }

    @Test fun dynamicReplayUsesProgrammeStartAndAbsoluteSeekWhileLiveKeepsDefaultPosition() = withPlayer { player, windowStart ->
        val start = windowStart + 3_600_000
        val range = PlaybackRange(start, start + 600_000)
        val position = AtomicLong()
        val ready = CountDownLatch(1)
        val error = AtomicReference<VideoPlayer.PlaybackException>()
        main {
            player.onCurrentPositionChanged { position.set(it) }
            player.onError { if (it != null) error.set(it) }
            player.onReady { ready.countDown() }
            player.prepare(ChannelLine("https://example.test/live.mpd"), range)
        }
        assertTrue("Replay did not become ready: ${error.get()}", ready.await(8, TimeUnit.SECONDS))
        Thread.sleep(650)
        assertNull(error.get())
        assertTrue("Started at live edge: ${position.get() - start}", position.get() in start..start + 3_000)
        main { player.pause(); player.seekTo(start + 120_000) }
        Thread.sleep(650)
        assertTrue("Seek interpreted UTC as a relative position", position.get() in start + 120_000..start + 122_000)

        val liveReady = CountDownLatch(1)
        main {
            player.onReady { liveReady.countDown() }
            player.prepare(ChannelLine("https://example.test/live.mpd"))
        }
        assertTrue(liveReady.await(8, TimeUnit.SECONDS))
        Thread.sleep(650)
        assertTrue("Normal live playback was forced to its beginning", position.get() > windowStart + 7_000_000)
    }

    @Test fun programmeEndPausesInsteadOfReturningToLive() = withPlayer { player, windowStart ->
        val start = windowStart + 3_600_000
        val range = PlaybackRange(start, start + 1_200)
        val ended = CountDownLatch(1)
        val position = AtomicLong()
        val playing = AtomicReference(false)
        main {
            player.onIsPlayingChanged { playing.set(it) }
            player.onCurrentPositionChanged {
                position.set(it)
                if (it == range.endAt) ended.countDown()
            }
            player.prepare(ChannelLine("https://example.test/live.mpd"), range)
        }
        assertTrue("Programme end not reached", ended.await(8, TimeUnit.SECONDS))
        Thread.sleep(650)
        assertFalse("Replay resumed at live edge", playing.get())
        assertEquals(range.endAt, position.get())
    }

    @Test fun expiredProgrammeReportsUnavailableInsteadOfPlayingLive() = withPlayer { player, windowStart ->
        val error = AtomicReference<VideoPlayer.PlaybackException>()
        val failed = CountDownLatch(1)
        main {
            player.onError { if (it != null) { error.set(it); failed.countDown() } }
            player.prepare(ChannelLine("https://example.test/live.mpd"),
                PlaybackRange(windowStart - 3_600_000, windowStart - 1_800_000))
        }
        assertTrue(failed.await(8, TimeUnit.SECONDS))
        assertEquals(VideoPlayer.PlaybackException.PLAYBACK_UNAVAILABLE, error.get())
    }

    @Test fun failedSourceReloadsAndStillStartsAtProgrammeTime() = withPlayer(failures = 1) { player, windowStart ->
        val start = windowStart + 3_600_000
        val ready = CountDownLatch(1)
        val position = AtomicLong()
        main {
            player.onReady { ready.countDown() }
            player.onCurrentPositionChanged { position.set(it) }
            player.prepare(ChannelLine("https://example.test/live.mpd?playseek=test"), PlaybackRange(start, start + 600_000))
        }
        assertTrue("Source was not reloaded after failure", ready.await(8, TimeUnit.SECONDS))
        Thread.sleep(650)
        assertTrue("Recovery jumped to live", position.get() in start..start + 3_000)
    }

    @Test fun persistentFailureStopsAfterBoundedRetries() = withPlayer(failures = 99) { player, windowStart ->
        val failed = CountDownLatch(1)
        val error = AtomicReference<VideoPlayer.PlaybackException>()
        main {
            player.onError { if (it != null) { error.set(it); failed.countDown() } }
            player.prepare(ChannelLine("https://example.test/live.mpd"),
                PlaybackRange(windowStart + 3_600_000, windowStart + 4_200_000))
        }
        assertTrue("Retry loop did not terminate", failed.await(8, TimeUnit.SECONDS))
        assertNotNull(error.get())
    }

    private class TimelineFactory(private val windowStart: Long, failures: Int) : MediaSource.Factory {
        private val remainingFailures = AtomicInteger(failures)
        override fun createMediaSource(mediaItem: MediaItem): MediaSource {
            val fail = remainingFailures.getAndDecrement() > 0
            return object : BaseMediaSource() {
            override fun getMediaItem() = mediaItem
            override fun prepareSourceInternal(mediaTransferListener: TransferListener?) {
                if (fail) return
                refreshSourceInfo(SinglePeriodTimeline(windowStart, windowStart, C.TIME_UNSET,
                    7_200_000_000L, 7_200_000_000L, 0L, 7_195_000_000L,
                    true, true, true, null, mediaItem,
                    MediaItem.LiveConfiguration.Builder().setTargetOffsetMs(5_000)
                        .setMinPlaybackSpeed(1f).setMaxPlaybackSpeed(1f).build()))
            }
            override fun maybeThrowSourceInfoRefreshError() {
                if (fail) throw IOException("Synthetic source failure")
            }
            override fun createPeriod(id: MediaSource.MediaPeriodId, allocator: Allocator, startPositionUs: Long): MediaPeriod = EmptyPeriod()
            override fun releasePeriod(mediaPeriod: MediaPeriod) = Unit
            override fun releaseSourceInternal() = Unit
            }
        }
        override fun setDrmSessionManagerProvider(provider: DrmSessionManagerProvider) = this
        override fun setLoadErrorHandlingPolicy(policy: LoadErrorHandlingPolicy) = this
        override fun getSupportedTypes() = intArrayOf(C.CONTENT_TYPE_DASH)
    }

    private class EmptyPeriod : MediaPeriod {
        override fun prepare(callback: MediaPeriod.Callback, positionUs: Long) = callback.onPrepared(this)
        override fun maybeThrowPrepareError() = Unit
        override fun getTrackGroups() = TrackGroupArray.EMPTY
        override fun selectTracks(selections: Array<out ExoTrackSelection?>, mayRetainStreamFlags: BooleanArray,
            streams: Array<out SampleStream?>, streamResetFlags: BooleanArray, positionUs: Long) = positionUs
        override fun discardBuffer(positionUs: Long, toKeyframe: Boolean) = Unit
        override fun readDiscontinuity() = C.TIME_UNSET
        override fun seekToUs(positionUs: Long) = positionUs
        override fun getAdjustedSeekPositionUs(positionUs: Long, seekParameters: SeekParameters) = positionUs
        override fun getBufferedPositionUs() = C.TIME_END_OF_SOURCE
        override fun getNextLoadPositionUs() = C.TIME_END_OF_SOURCE
        override fun continueLoading(loadingInfo: LoadingInfo) = false
        override fun isLoading() = false
        override fun reevaluateBuffer(positionUs: Long) = Unit
    }
}
