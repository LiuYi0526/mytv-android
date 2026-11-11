package top.yogiczy.mytv.tv.ui

import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import top.yogiczy.mytv.tv.ui.screensold.videoplayercontroller.components.VideoPlayerControllerPositionCtrl
import top.yogiczy.mytv.tv.ui.theme.MyTvTheme
import top.yogiczy.mytv.tv.ui.utils.focusOnLaunchedSaveable
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Uses Android input directly: Espresso's InputManager reflection fails on API 37. */
class PlaybackProgressInputTest {
    private var scenario: ActivityScenario<ComponentActivity>? = null
    private val calls = AtomicInteger()
    private val target = AtomicLong(Long.MIN_VALUE)
    @Volatile private var bounds = Rect.Zero
    @Volatile private var trackCenterY = 0f
    private val start = 1_793_271_600_000L

    private fun show(position: Long = 120_000, range: Pair<Long, Long> = 0L to 600_000L, absolute: Boolean = false) {
        scenario = ActivityScenario.launch(ComponentActivity::class.java)
        scenario!!.onActivity { activity ->
            activity.setContent {
                MyTvTheme {
                    trackCenterY = with(LocalDensity.current) { 24.dp.toPx() }
                    VideoPlayerControllerPositionCtrl(
                        modifier = Modifier.fillMaxWidth().focusOnLaunchedSaveable()
                            .onGloballyPositioned { bounds = it.boundsInWindow() },
                        currentPositionProvider = { position },
                        durationProvider = { range },
                        positionIsAbsoluteProvider = { absolute },
                        onSeekTo = { target.set(it); calls.incrementAndGet() },
                    )
                }
            }
        }
        await { bounds.width > 0 }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        SystemClock.sleep(100)
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (!condition() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(20)
        assertTrue("Timed out waiting for progress input", condition())
    }

    private fun key(code: Int, action: Int, repeat: Int = 0) {
        scenario!!.onActivity {
            val now = SystemClock.uptimeMillis()
            it.dispatchKeyEvent(KeyEvent(now, now, action, code, repeat))
        }
    }
    private fun press(code: Int) {
        key(code, KeyEvent.ACTION_DOWN)
        key(code, KeyEvent.ACTION_UP)
    }
    private fun touch(action: Int, fraction: Float, downAt: Long) {
        scenario!!.onActivity {
            val event = MotionEvent.obtain(downAt, SystemClock.uptimeMillis(), action,
                bounds.left + bounds.width * fraction, bounds.top + trackCenterY, 0)
            try { it.dispatchTouchEvent(event) } finally { event.recycle() }
        }
    }
    private fun assertTarget(expected: Long) {
        await { calls.get() > 0 }
        assertEquals(expected, target.get())
        SystemClock.sleep(1_100)
        assertEquals("One gesture must issue only one seek", 1, calls.get())
    }

    @After fun close() { scenario?.close() }

    @Test fun remoteKeysBatchTenMinuteStepsAndPreserveUtc() {
        show(start + 1_200_000, start to start + 3_600_000, true)
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        assertTarget(start + 1_800_000)
    }

    @Test fun heldRemoteKeepsTenMinuteStepsAndDoesNotSeekBeforeRelease() {
        show(range = 0L to 3_600_000L)
        key(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.ACTION_DOWN)
        key(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.ACTION_DOWN, 1)
        key(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.ACTION_DOWN, 4)
        SystemClock.sleep(1_200)
        assertEquals(0, calls.get())
        key(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.ACTION_UP)
        assertTarget(1_920_000)
    }

    @Test fun confirmImmediatelyCommitsWithoutDuplicateDebounce() {
        show()
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(600_000, target.get())
        assertTarget(600_000)
    }

    @Test fun remoteStopsAtBothProgrammeBounds() {
        show(start + 5_000, start to start + 15_000, true)
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        assertTarget(start)
        calls.set(0)
        target.set(Long.MIN_VALUE)
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertTarget(start + 15_000)
    }

    @Test fun heldDragOnlySeeksOnceOnRelease() {
        show()
        val down = SystemClock.uptimeMillis()
        touch(MotionEvent.ACTION_DOWN, 0.25f, down)
        touch(MotionEvent.ACTION_MOVE, 0.50f, down)
        SystemClock.sleep(1_200)
        assertEquals("Do not reload a URL while the finger is held", 0, calls.get())
        touch(MotionEvent.ACTION_UP, 0.50f, down)
        await { calls.get() > 0 }
        assertTrue("Relative drag must move forward", target.get() in 180_000L..300_000L)
        SystemClock.sleep(1_100)
        assertEquals(1, calls.get())
    }

    @Test fun cancellingDragRestoresPositionWithoutSeeking() {
        show()
        val down = SystemClock.uptimeMillis()
        touch(MotionEvent.ACTION_DOWN, 0.25f, down)
        touch(MotionEvent.ACTION_MOVE, 0.60f, down)
        touch(MotionEvent.ACTION_CANCEL, 0.60f, down)
        SystemClock.sleep(1_200)
        assertEquals(0, calls.get())
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertTarget(600_000)
    }

    @Test fun dragClampsToEndAndPreservesUtc() {
        show(start + 540_000, start to start + 600_000, true)
        val down = SystemClock.uptimeMillis()
        touch(MotionEvent.ACTION_DOWN, 0.20f, down)
        touch(MotionEvent.ACTION_MOVE, 0.90f, down)
        touch(MotionEvent.ACTION_UP, 0.90f, down)
        assertTarget(start + 600_000)
    }

    @Test fun downLeavesProgressForShortcutButtons() {
        show()
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        // Spatial focus chooses the nearest shortcut, depending on the screen width.
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        await { calls.get() > 0 }
        assertTrue(target.get() in setOf(0L, 60_000L, 180_000L, 600_000L))
        assertEquals(1, calls.get())
    }

    @Test fun unavailableDurationDoesNotIssueInvalidSeek() {
        show(0L, 0L to 0L)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        SystemClock.sleep(1_200)
        assertEquals(0, calls.get())
    }

    @Test fun absoluteProgressNeverSeeksIntoFuture() {
        val now = System.currentTimeMillis()
        show(now - 10_000, now - 60_000 to now + 600_000, true)
        repeat(3) { press(KeyEvent.KEYCODE_DPAD_RIGHT) }
        await { calls.get() > 0 }
        assertTrue(target.get() in now..System.currentTimeMillis())
        assertEquals(1, calls.get())
    }
}
