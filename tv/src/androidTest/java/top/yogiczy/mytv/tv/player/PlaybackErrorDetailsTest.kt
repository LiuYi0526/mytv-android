package top.yogiczy.mytv.tv.player

import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import top.yogiczy.mytv.core.data.utils.SP
import top.yogiczy.mytv.tv.ui.screensold.videoplayer.VideoPlayerState
import top.yogiczy.mytv.tv.ui.screensold.videoplayer.player.Media3VideoPlayer
import top.yogiczy.mytv.tv.ui.screensold.videoplayer.player.VideoPlayer.PlaybackException
import java.io.IOException

@RunWith(AndroidJUnit4::class)
@OptIn(UnstableApi::class)
class PlaybackErrorDetailsTest {
    private val subtitleReason = "Legacy decoding is disabled, can't handle text/vtt samples (expected application/x-media3-cues)."

    @Test fun deepestReasonComesBeforeGenericWrappersAndKeepsTheCode() {
        val error = PlaybackException.fromCause("MEDIA3_ERROR_FAILED_RUNTIME_CHECK", 1004,
            RuntimeException("Renderer error", IllegalStateException(subtitleReason)))
        assertEquals("MEDIA3_ERROR_FAILED_RUNTIME_CHECK(1004)", error.displayMessage.substringBefore('\n'))
        assertEquals("IllegalStateException: $subtitleReason", error.details!!.lineSequence().first())
        assertTrue(error.details!!.contains("Renderer error"))
        assertFalse(error.details!!.contains("\tat "))
    }

    @Test fun errorsWithoutDetailsKeepTheirOriginalDisplay() {
        assertEquals("ERROR_LOAD_TIMEOUT(10003)", PlaybackException.LOAD_TIMEOUT.displayMessage)
        assertEquals("IJK_ERROR_WHAT_1(-1004)", PlaybackException("IJK_ERROR_WHAT_1", -1004).displayMessage)
        assertEquals("CODE(1)", PlaybackException("CODE", 1, "  ").displayMessage)
    }

    @Test fun missingMessagesStillIdentifyTheExceptionType() {
        assertEquals("IllegalStateException", PlaybackException.fromCause("CODE", 1, IllegalStateException()).details)
    }

    @Test fun httpStatusSurvivesNestedTransportErrors() {
        val error = PlaybackException.fromCause("MEDIA3_ERROR_IO_BAD_HTTP_STATUS", 2004,
            IOException("Source error", IOException("Response code: 404")))
        assertEquals("IOException: Response code: 404", error.details!!.lineSequence().first())
    }

    @Test fun cyclesDuplicateMessagesAndLargeDetailsStayBounded() {
        val first = IOException("Repeated")
        val second = IOException("Repeated", first)
        first.initCause(second)
        assertEquals("IOException: Repeated", PlaybackException.fromCause("CODE", 1, first).details)
        val long = PlaybackException.fromCause("CODE", 1, IOException("x".repeat(10_000),
            IOException("y".repeat(10_000))))
        assertTrue(long.details!!.length <= 2000)
        assertTrue(long.details!!.startsWith("IOException: y"))
    }

    @Test fun media3RuntimeErrorReachesScreenStateAndClearsWhenBuffering() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        instrumentation.runOnMainSync {
            SP.init(instrumentation.context)
            var state: VideoPlayerState? = null
            try {
                val engine = Media3VideoPlayer(instrumentation.targetContext, scope)
                state = VideoPlayerState(engine)
                state.initialize()
                val listener = Media3VideoPlayer::class.java.getDeclaredField("playerListener")
                    .apply { isAccessible = true }.get(engine) as Player.Listener
                listener.onPlayerError(androidx.media3.common.PlaybackException("Renderer error",
                    IllegalStateException(subtitleReason), 1004))
                assertTrue(state.error!!.startsWith("MEDIA3_ERROR_FAILED_RUNTIME_CHECK(1004)\n"))
                assertTrue(state.error!!.contains(subtitleReason))
                listener.onPlaybackStateChanged(Player.STATE_BUFFERING)
                assertNull("Stale failure details must disappear with the old error", state.error)
            } finally {
                state?.release()
                scope.cancel()
                SP.init(instrumentation.targetContext)
            }
        }
    }
}
