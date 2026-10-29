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

/** Optional real-stream check; pass hlsUrl at runtime so expiring credentials never enter source. */
@RunWith(AndroidJUnit4::class)
@OptIn(UnstableApi::class)
class HlsCookiePlaybackTest {
    @Test fun cookieProtectedHlsReachesReadyAndAdvances() {
        val url = InstrumentationRegistry.getArguments().getString("hlsUrl")
        assumeTrue("Pass a valid cookie-protected HLS URL using hlsUrl", !url.isNullOrBlank())
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val ready = CountDownLatch(1)
        val error = AtomicReference<VideoPlayer.PlaybackException>()
        val playing = AtomicBoolean()
        val width = AtomicInteger()
        val videoDecoderInitialized = AtomicBoolean()
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
            assertTrue("HLS did not become ready", ready.await(25, TimeUnit.SECONDS))
            assertNull("HLS failed: ${error.get()?.errorCodeName}", error.get())
            Thread.sleep(2_000)
            assertTrue("HLS is not playing", playing.get())
            assertTrue("Video track format was not discovered", width.get() > 0)
            assertTrue("Video decoder was not initialized", videoDecoderInitialized.get())
            assertTrue("HLS playback position did not advance", lastPosition.get() - firstPosition.get() >= 500)
        } finally {
            instrumentation.runOnMainSync { player.stop(); player.release(); scope.cancel() }
        }
    }
}
