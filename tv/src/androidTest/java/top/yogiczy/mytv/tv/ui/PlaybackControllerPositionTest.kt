package top.yogiczy.mytv.tv.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.background
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import top.yogiczy.mytv.tv.ui.screensold.videoplayercontroller.components.VideoPlayerControllerPositionCtrl
import top.yogiczy.mytv.tv.ui.theme.MyTvTheme
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
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

    private var progressColor = Color.Unspecified

    private fun showPartitioned(absolute: Boolean = true, available: () -> Long?) {
        compose.setContent {
            MyTvTheme {
                progressColor = MaterialTheme.colorScheme.primary
                CompositionLocalProvider(LocalContentColor provides Color.White) {
                    Box(Modifier.fillMaxWidth().background(Color.Black)) {
                        VideoPlayerControllerPositionCtrl(
                            currentPositionProvider = { start + 120_000 },
                            durationProvider = { start to start + 600_000 },
                            positionIsAbsoluteProvider = { absolute },
                            availableUntilProvider = available,
                            onSeekTo = { target.set(it); calls.incrementAndGet() },
                        )
                    }
                }
            }
        }
    }

    private fun trackColorAt(fraction: Float): Color {
        val pixels = compose.onNodeWithContentDescription("播放进度").captureToImage().toPixelMap()
        return pixels[(pixels.width * fraction).toInt(), pixels.height / 2]
    }

    @Test fun timeshiftTrackDistinguishesPlayedAvailableAndFutureContent() {
        showPartitioned { start + 360_000 }
        val played = trackColorAt(0.1f)
        val available = trackColorAt(0.4f)
        val future = trackColorAt(0.9f)
        assertEquals(progressColor.red, played.red, 0.02f)
        assertEquals(progressColor.green, played.green, 0.02f)
        assertEquals(progressColor.blue, played.blue, 0.02f)
        assertEquals(available.red, available.green, 0.02f)
        assertTrue("The aired part must be brighter than the future part", available.red > future.red + 0.2f)
    }

    @Test fun liveBoundaryAdvancesWhilePausedPlayheadStaysPut() {
        val available = mutableStateOf(start + 240_000)
        showPartitioned { available.value }
        val before = trackColorAt(0.5f)
        val played = trackColorAt(0.1f)
        compose.runOnIdle { available.value = start + 480_000 }
        val after = trackColorAt(0.5f)
        assertTrue(after.red > before.red + 0.2f)
        assertEquals(played, trackColorAt(0.1f))
        assertEquals(0, calls.get())
    }

    @Test fun completedProgrammeHasNoFutureSection() {
        showPartitioned { start + 1_200_000 }
        assertTrue(trackColorAt(0.9f).red > 0.5f)
    }

    @Test fun relativeMediaIgnoresWallClockAvailability() {
        showPartitioned(absolute = false) { start + 360_000 }
        assertEquals(trackColorAt(0.4f), trackColorAt(0.9f))
    }
}
