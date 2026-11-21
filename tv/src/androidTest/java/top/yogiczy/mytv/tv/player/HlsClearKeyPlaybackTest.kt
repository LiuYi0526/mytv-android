package top.yogiczy.mytv.tv.player

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Test
import org.junit.runner.RunWith
import top.yogiczy.mytv.core.data.entities.channel.ChannelLine
import top.yogiczy.mytv.core.data.utils.SP
import top.yogiczy.mytv.tv.ui.screensold.videoplayer.player.Media3VideoPlayer
import top.yogiczy.mytv.tv.ui.screensold.videoplayer.player.VideoPlayer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class HlsClearKeyPlaybackTest {
    @Test
    fun encryptedFmp4PlaysWithClearKey() {
        val url = InstrumentationRegistry.getArguments().getString("hlsClearKeyUrl")
        assumeNotNull(url)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val advanced = CountDownLatch(1)
        val error = AtomicReference<VideoPlayer.PlaybackException>()
        var player: Media3VideoPlayer? = null

        try {
            instrumentation.runOnMainSync {
                SP.init(instrumentation.context)
                val engine = Media3VideoPlayer(instrumentation.targetContext, scope)
                player = engine
                engine.initialize()
                engine.setVolume(0f)
                engine.onCurrentPositionChanged { if (it >= 1_000) advanced.countDown() }
                engine.onError {
                    if (it != null) {
                        error.set(it)
                        advanced.countDown()
                    }
                }
                // Public Shaka Angel One ClearKey fixture remuxed into an HLS playlist.
                engine.prepare(ChannelLine(
                    url = requireNotNull(url),
                    licenseType = "org.w3.clearkey",
                    licenseKey = """{"feedf00deedeadbeeff0baadf00dd00d":"00112233445566778899aabbccddeeff"}""",
                ))
            }
            assertTrue("Encrypted HLS playback did not advance", advanced.await(20, TimeUnit.SECONDS))
            assertNull("Encrypted HLS playback failed: ${error.get()?.errorCodeName}", error.get())
        } finally {
            instrumentation.runOnMainSync {
                player?.release()
                scope.cancel()
                SP.init(instrumentation.targetContext)
            }
        }
    }
}
