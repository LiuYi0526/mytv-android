package top.yogiczy.mytv.tv.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import top.yogiczy.mytv.tv.ui.screensold.videoplayercontroller.components.VideoPlayerControllerPositionCtrl
import top.yogiczy.mytv.tv.ui.theme.MyTvTheme
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

@OptIn(ExperimentalTestApi::class)
class PlaybackControllerPositionTest {
    @get:Rule val compose = createComposeRule()
    private val start = 1_793_271_600_000L
    private val target = AtomicLong(Long.MIN_VALUE)
    private val calls = AtomicInteger()

    private fun show(position: Long, range: Pair<Long, Long>, absolute: Boolean) {
        compose.setContent {
            MyTvTheme {
                Box(Modifier.fillMaxWidth()) {
                    VideoPlayerControllerPositionCtrl(
                        currentPositionProvider = { position },
                        durationProvider = { range },
                        positionIsAbsoluteProvider = { absolute },
                        onSeekTo = { target.set(it); calls.incrementAndGet() },
                    )
                }
            }
        }
    }

    private fun touch(label: String) =
        compose.onNodeWithContentDescription(label).performTouchInput { click() }

    private fun assertTarget(expected: Long) {
        compose.waitUntil(5_000) { target.get() != Long.MIN_VALUE }
        assertEquals(expected, target.get())
        assertEquals("One action must not trigger duplicate seeks", 1, calls.get())
    }

    @Test fun replayForwardPreservesUtcTimeInsteadOfSendingProgrammeOffset() {
        show(start + 120_000, start to start + 3_600_000, true)
        touch("前进1分钟")
        assertTarget(start + 180_000)
    }

    @Test fun remoteReplayBackwardPreservesUtcTime() {
        show(start + 1_200_000, start to start + 3_600_000, true)
        val button = compose.onNodeWithContentDescription("后退10分钟")
        button.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        button.performKeyInput { pressKey(Key.DirectionCenter) }
        assertTarget(start + 600_000)
    }

    @Test fun replayBackwardStopsAtProgrammeStart() {
        show(start + 120_000, start to start + 3_600_000, true)
        touch("后退10分钟")
        assertTarget(start)
    }

    @Test fun relativeMediaKeepsRelativeSeekAndElapsedTimeDisplay() {
        show(120_000, 0L to 600_000L, false)
        compose.onNodeWithText("00:02:00 / 00:10:00").assertExists()
        touch("前进1分钟")
        assertTarget(180_000)
    }

    @Test fun replayForwardStopsAtProgrammeEnd() {
        show(start + 540_000, start to start + 600_000, true)
        touch("前进10分钟")
        assertTarget(start + 600_000)
    }

    @Test fun consecutiveAdjustmentsCommitOnceUsingTheSameTimeBase() {
        show(start + 120_000, start to start + 3_600_000, true)
        touch("前进1分钟")
        touch("前进1分钟")
        assertTarget(start + 240_000)
    }
}
