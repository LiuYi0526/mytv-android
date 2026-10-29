package top.yogiczy.mytv.tv.player

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import top.yogiczy.mytv.core.data.entities.channel.ChannelLine
import top.yogiczy.mytv.tv.ui.screensold.videoplayer.player.Media3VideoPlayer
import top.yogiczy.mytv.tv.ui.screensold.videoplayer.player.VideoPlayer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Optional real-stream check; pass mmtUrl at runtime so expiring credentials never enter source. */
@RunWith(AndroidJUnit4::class)
@OptIn(UnstableApi::class)
class MmtPlaybackTest {
    @Test fun mmtAudioAdvancesWithVideoDisabled() {
        val url = InstrumentationRegistry.getArguments().getString("mmtUrl")
        assumeTrue(!url.isNullOrBlank())
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ready = CountDownLatch(1)
        val failure = AtomicReference<androidx.media3.common.PlaybackException>()
        val position = AtomicLong()
        val videoWidth = AtomicInteger()
        lateinit var player: androidx.media3.exoplayer.ExoPlayer
        instrumentation.runOnMainSync {
            player = androidx.media3.exoplayer.ExoPlayer.Builder(instrumentation.targetContext).build()
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, true).build()
            player.volume = 0f
            player.addListener(object : androidx.media3.common.Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == androidx.media3.common.Player.STATE_READY) ready.countDown()
                }
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    failure.set(error); ready.countDown()
                }
                override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                    tracks.groups.filter { it.type == androidx.media3.common.C.TRACK_TYPE_VIDEO }
                        .forEach { videoWidth.set(it.getTrackFormat(0).width) }
                }
            })
            val source = androidx.media3.exoplayer.source.ProgressiveMediaSource.Factory(
                androidx.media3.datasource.DefaultHttpDataSource.Factory(),
                top.yogiczy.mytv.tv.ui.screensold.videoplayer.player.mmt.MmtExtractorsFactory())
                .createMediaSource(androidx.media3.common.MediaItem.fromUri(url!!))
            player.setMediaSource(source); player.prepare(); player.play()
        }
        try {
            assertTrue("MMT audio did not become ready", ready.await(30, TimeUnit.SECONDS))
            assertNull(failure.get())
            instrumentation.runOnMainSync { position.set(player.currentPosition) }
            Thread.sleep(3000)
            instrumentation.runOnMainSync {
                assertTrue("Audio playback did not advance", player.currentPosition - position.get() > 1000)
                assertNotNull("Audio format missing", player.audioFormat)
            }
            assertTrue("Video format missing", videoWidth.get() > 0)
        } finally { instrumentation.runOnMainSync { player.release() } }
    }

    @Test fun mmtReachesReadyAndAdvances() {
        val url = InstrumentationRegistry.getArguments().getString("mmtUrl")
        assumeTrue("Pass a valid MMT/TLV URL using mmtUrl", !url.isNullOrBlank())
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val ready = CountDownLatch(1)
        val error = AtomicReference<VideoPlayer.PlaybackException>()
        val playing = AtomicBoolean()
        val width = AtomicInteger()
        val videoDecoderInitialized = AtomicBoolean()
        val subtitles = AtomicReference<List<VideoPlayer.Metadata.Subtitle>>(emptyList())
        val expectedSubtitles = InstrumentationRegistry.getArguments().getString("expectedSubtitleTracks")?.toInt() ?: 0
        val firstPosition = AtomicLong(Long.MAX_VALUE)
        val lastPosition = AtomicLong()
        lateinit var player: Media3VideoPlayer
        instrumentation.runOnMainSync {
            player = Media3VideoPlayer(instrumentation.targetContext, scope)
            player.initialize()
            player.setVolume(0f)
            player.onResolution { w, _ -> width.set(w) }
            // A headless player may not emit onVideoSizeChanged without a display surface.
            player.onMetadata {
                subtitles.set(it.subtitleTracks)
                it.video?.width?.takeIf { value -> value > 0 }?.let(width::set)
                if (!it.video?.decoder.isNullOrBlank()) videoDecoderInitialized.set(true)
            }
            player.onReady { ready.countDown() }
            player.onIsPlayingChanged { playing.set(it) }
            player.onCurrentPositionChanged {
                firstPosition.updateAndGet { previous -> minOf(previous, it) }
                lastPosition.set(it)
            }
            player.onError { if (it != null) { error.set(it); ready.countDown() } }
            player.prepare(ChannelLine(url = url!!))
        }
        try {
            assertTrue("MMT did not become ready", ready.await(25, TimeUnit.SECONDS))
            assertNull("MMT failed: ${error.get()?.errorCodeName}", error.get())
            Thread.sleep(2_000)
            assertTrue("MMT is not playing", playing.get())
            assertTrue("Video track format was not discovered", width.get() > 0)
            assertTrue("Video decoder was not initialized", videoDecoderInitialized.get())
            assertTrue("MMT playback position did not advance", lastPosition.get() - firstPosition.get() >= 500)
            assertTrue("Subtitle options were filtered out: ${subtitles.get().size}", subtitles.get().size >= expectedSubtitles)
            if (expectedSubtitles > 0) {
                val options = subtitles.get()
                assertEquals("Subtitle labels must distinguish the two Japanese tracks",
                    options.size, options.map { it.shortLabel }.distinct().size)
                for (option in options.reversed()) {
                    val selected = CountDownLatch(1)
                    instrumentation.runOnMainSync {
                        player.onMetadata { if (it.subtitle?.index == option.index) selected.countDown() }
                        player.selectSubtitleTrack(option)
                    }
                    assertTrue("Subtitle option ${option.index} could not be selected", selected.await(5, TimeUnit.SECONDS))
                }
                Thread.sleep(1000)
                assertNull("Playback failed after subtitle selection: ${error.get()?.errorCodeName}", error.get())
                assertTrue("Playback stopped after subtitle selection", playing.get())
            }
        } finally {
            instrumentation.runOnMainSync { player.stop(); player.release(); scope.cancel() }
        }
    }
}
