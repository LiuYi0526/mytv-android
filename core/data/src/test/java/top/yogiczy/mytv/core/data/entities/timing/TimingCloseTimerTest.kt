package top.yogiczy.mytv.core.data.entities.timing

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimingCloseTimerTest {
    private class Fixture : AutoCloseable {
        var now = 1_000L
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val ticks = Channel<Unit>()
        val waits = Channel<Long>(Channel.UNLIMITED)
        val timer = TimingCloseTimer(scope, { now }) {
            waits.send(it)
            ticks.receive()
        }
        override fun close() = scope.cancel()
    }

    @Test
    fun initiallyDisabledAndNoExpiry() = Fixture().use { f ->
        assertEquals(TimingCloseTimer.Status(), f.timer.status.value)
    }

    @Test
    fun countsDownAgainstDeadlineAndRoundsUpMinutes() = runBlocking {
        Fixture().use { f ->
            f.timer.setMinutes(15)
            assertEquals(1_000L, f.waits.receive())
            assertEquals(15L, f.timer.status.value.remainingMinutes)
            f.now += 60_001L
            f.ticks.send(Unit)
            f.waits.receive()
            assertEquals(839_999L, f.timer.status.value.remainingMillis)
            assertEquals(14L, f.timer.status.value.remainingMinutes)
        }
    }

    @Test
    fun reschedulingReplacesThePreviousDeadline() = runBlocking {
        Fixture().use { f ->
            f.timer.setMinutes(15)
            f.waits.receive()
            f.now += 120_000L
            f.timer.setMinutes(30)
            f.waits.receive()
            f.now += 780_000L // The old timer would now expire.
            f.ticks.send(Unit)
            f.waits.receive()
            assertEquals(17L, f.timer.status.value.remainingMinutes)
            assertEquals(30, f.timer.status.value.selectedMinutes)
            assertFalse(f.timer.status.value.hasExpired)
        }
    }

    @Test
    fun cancellingPreventsThePendingExpiry() = runBlocking {
        Fixture().use { f ->
            f.timer.setMinutes(15)
            f.waits.receive()
            f.timer.setMinutes(0)
            f.now += 900_000L
            assertFalse(f.ticks.trySend(Unit).isSuccess)
            assertEquals(TimingCloseTimer.Status(), f.timer.status.value)
        }
    }

    @Test
    fun delayedTickStillExpiresAndStopsTheCountdown() = runBlocking {
        Fixture().use { f ->
            f.timer.setMinutes(15)
            f.waits.receive()
            f.now += 1_000_000L // Simulate a delayed callback after background/sleep.
            f.ticks.send(Unit)
            assertTrue(f.timer.status.value.hasExpired)
            assertFalse(f.timer.status.value.isActive)
            assertEquals(0L, f.timer.status.value.remainingMillis)
            assertFalse(f.ticks.trySend(Unit).isSuccess)
            f.timer.setMinutes(0)
            assertFalse(f.timer.status.value.hasExpired)
        }
    }

    @Test
    fun invalidSelectionDoesNotReplaceAnExistingTimer() = Fixture().use { f ->
        f.timer.setMinutes(30)
        try {
            f.timer.setMinutes(-1)
            throw AssertionError("Invalid duration was accepted")
        } catch (_: IllegalArgumentException) {
            assertEquals(30, f.timer.status.value.selectedMinutes)
            assertTrue(f.timer.status.value.isActive)
        }
    }

    @Test
    fun releasingScopeStopsCallbacks() = runBlocking {
        Fixture().use { f ->
            f.timer.setMinutes(15)
            f.waits.receive()
            f.scope.cancel()
            f.now += 900_000L
            assertFalse(f.ticks.trySend(Unit).isSuccess)
            assertFalse(f.timer.status.value.hasExpired)
        }
    }
}
