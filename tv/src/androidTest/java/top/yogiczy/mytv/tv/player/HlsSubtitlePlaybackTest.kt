package top.yogiczy.mytv.tv.player

import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.EGLSurfaceTexture
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import top.yogiczy.mytv.tv.ui.screensold.videoplayer.player.av3a.Av3aHlsExtractorFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.FutureTask
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Optional live-source check. No channel, history or user preferences are changed. */
@RunWith(AndroidJUnit4::class)
@OptIn(UnstableApi::class)
class HlsSubtitlePlaybackTest {
    @Test fun defaultWebVttAndVideoPlayTogether() {
        val url = InstrumentationRegistry.getArguments().getString("subtitleHlsUrl")
        assumeTrue("Pass subtitleHlsUrl to check a live HLS source with default WebVTT", !url.isNullOrBlank())
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val firstFrame = CountDownLatch(1)
        val failure = AtomicReference<PlaybackException>()
        val parsedSubtitleSelected = AtomicBoolean()
        lateinit var player: ExoPlayer
        val renderThread = HandlerThread("subtitle-test-output").apply { start() }
        val renderHandler = Handler(renderThread.looper)
        val texture = EGLSurfaceTexture(renderHandler)
        val initializeSurface = FutureTask {
            texture.init(EGLSurfaceTexture.SECURE_MODE_NONE)
            Surface(texture.surfaceTexture)
        }
        renderHandler.post(initializeSurface)
        // Consume frames on an EGL thread so the decoder's output queue cannot fill up.
        val surface = initializeSurface.get(5, TimeUnit.SECONDS)
        instrumentation.runOnMainSync {
            player = ExoPlayer.Builder(instrumentation.targetContext).build()
            player.setVideoSurface(surface)
            player.volume = 0f
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setPreferredTextLanguage("en").build()
            player.addListener(object : Player.Listener {
                override fun onRenderedFirstFrame() { firstFrame.countDown() }
                override fun onPlayerError(error: PlaybackException) {
                    failure.set(error)
                    firstFrame.countDown()
                }
                override fun onTracksChanged(tracks: Tracks) {
                    parsedSubtitleSelected.set(tracks.groups.any { group ->
                        group.type == C.TRACK_TYPE_TEXT && (0 until group.length).any {
                            group.isTrackSelected(it) &&
                                group.getTrackFormat(it).sampleMimeType == MimeTypes.APPLICATION_MEDIA3_CUES
                        }
                    })
                }
            })
            player.setMediaSource(HlsMediaSource.Factory(DefaultHttpDataSource.Factory()
                .setUserAgent("okhttp").setConnectTimeoutMs(10_000).setReadTimeoutMs(10_000))
                .setExtractorFactory(Av3aHlsExtractorFactory())
                .setAllowChunklessPreparation(false)
                .createMediaSource(MediaItem.fromUri(url!!)))
            player.prepare()
            player.play()
        }
        try {
            assertTrue("No video frame within 35 seconds: ${failure.get()}", firstFrame.await(35, TimeUnit.SECONDS))
            assertNull("HLS failed: ${failure.get()?.stackTraceToString()}", failure.get())
            fun playbackTimeMs(): Long {
                val window = player.currentTimeline.getWindow(player.currentMediaItemIndex, Timeline.Window())
                // Live playlists slide: currentPosition alone can decrease while video keeps playing.
                val start = window.windowStartTimeMs.takeUnless { it == C.TIME_UNSET }
                    ?: window.positionInFirstPeriodMs
                return start + player.currentPosition
            }
            var initial = 0L
            instrumentation.runOnMainSync { initial = playbackTimeMs() }
            Thread.sleep(12_000)
            assertNull("Playback failed after first frame: ${failure.get()?.stackTraceToString()}", failure.get())
            assertTrue("Parsed WebVTT track was not selected", parsedSubtitleSelected.get())
            instrumentation.runOnMainSync {
                assertTrue("Video playback did not advance: initial=$initial, current=${playbackTimeMs()}, " +
                    "buffered=${player.bufferedPosition}, state=${player.playbackState}", playbackTimeMs() - initial >= 8_000)
                assertTrue("Player stopped", player.isPlaying)
            }
        } finally {
            instrumentation.runOnMainSync {
                player.release()
                surface.release()
            }
            val releaseSurface = FutureTask { texture.release() }
            renderHandler.post(releaseSurface)
            releaseSurface.get(5, TimeUnit.SECONDS)
            renderThread.quitSafely()
        }
    }
}
