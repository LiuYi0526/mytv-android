package top.yogiczy.mytv.tv.player

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
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
import top.yogiczy.mytv.core.data.utils.SP
import top.yogiczy.mytv.tv.ui.screensold.videoplayer.player.Media3VideoPlayer
import top.yogiczy.mytv.tv.ui.screensold.videoplayer.player.VideoPlayer
import top.yogiczy.mytv.tv.ui.utils.Configs
import java.io.IOException
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
        val prepares = AtomicInteger()
        val lastLoad = AtomicReference("none")
        val lastLoadError = AtomicReference("none")
        val prefs = SP.getInstance(instrumentation.context)
        var player: Media3VideoPlayer? = null
        try {
            instrumentation.runOnMainSync {
                val userAgent = Configs.videoPlayerUserAgent
                val headers = Configs.videoPlayerHeaders
                prefs.edit().clear().commit()
                SP.init(instrumentation.context)
                Configs.videoPlayerUserAgent = userAgent
                Configs.videoPlayerHeaders = headers
                // Keep optional real-source checks bounded without changing the user's settings.
                Configs.videoPlayerLoadTimeout = 8_000
                val engine = Media3VideoPlayer(instrumentation.targetContext, scope)
                player = engine
                engine.initialize()
                engine.setVolume(0f)
                engine.onPrepared { prepares.incrementAndGet() }
                val field = Media3VideoPlayer::class.java.getDeclaredField("videoPlayer").apply { isAccessible = true }
                (field.get(engine) as ExoPlayer).addAnalyticsListener(object : AnalyticsListener {
                    override fun onLoadStarted(eventTime: AnalyticsListener.EventTime, loadEventInfo: LoadEventInfo,
                        mediaLoadData: MediaLoadData) {
                        lastLoad.set("type=${mediaLoadData.dataType},host=${loadEventInfo.uri.host}")
                    }
                    override fun onLoadError(eventTime: AnalyticsListener.EventTime, loadEventInfo: LoadEventInfo,
                        mediaLoadData: MediaLoadData, error: IOException, wasCanceled: Boolean) {
                        lastLoadError.set(error.javaClass.simpleName)
                    }
                })
                engine.onResolution { w, _ -> width.set(w) }
                // A headless player may not emit onVideoSizeChanged without a display surface.
                engine.onMetadata {
                    it.video?.width?.takeIf { value -> value > 0 }?.let(width::set)
                    if (!it.video?.decoder.isNullOrBlank()) videoDecoderInitialized.set(true)
                }
                engine.onReady { ready.countDown() }
                engine.onIsPlayingChanged { playing.set(it) }
                engine.onCurrentPositionChanged {
                    firstPosition.updateAndGet { previous -> minOf(previous, it) }
                    lastPosition.set(it)
                }
                engine.onError { if (it != null) { error.set(it); ready.countDown() } }
                engine.prepare(ChannelLine(url = url!!))
            }
            fun diagnostic() = "prepares=${prepares.get()},lastLoad=${lastLoad.get()},lastError=${lastLoadError.get()}"
            assertTrue("HLS did not become ready; ${diagnostic()}", ready.await(25, TimeUnit.SECONDS))
            assertNull("HLS failed: ${error.get()?.errorCodeName}; ${diagnostic()}", error.get())
            Thread.sleep(2_000)
            assertTrue("HLS is not playing", playing.get())
            assertTrue("Video track format was not discovered", width.get() > 0)
            assertTrue("Video decoder was not initialized", videoDecoderInitialized.get())
            assertTrue("HLS playback position did not advance", lastPosition.get() - firstPosition.get() >= 500)
        } finally {
            instrumentation.runOnMainSync {
                player?.stop()
                player?.release()
                scope.cancel()
                prefs.edit().clear().commit()
                SP.init(instrumentation.targetContext)
            }
        }
    }
}
