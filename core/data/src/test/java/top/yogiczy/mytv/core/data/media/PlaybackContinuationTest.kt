package top.yogiczy.mytv.core.data.media

import org.junit.Assert.*
import org.junit.Test

class PlaybackContinuationTest {
    @Test fun historyContinuesImmediatelyWhileNearLiveWaitsForANewWindow() {
        val policy = PlaybackContinuation()
        assertEquals(0L, policy.waitMs(1_000, 301_000))
        assertEquals(15_000L, policy.waitMs(1_000, 1_000))
        assertEquals(5_000L, policy.waitMs(1_000, 11_000))
    }

    @Test fun repeatedEmptyWindowsStopButAdvancingContentResetsTheLimit() {
        val policy = PlaybackContinuation()
        assertTrue(policy.accept(1_000))
        assertTrue(policy.accept(1_000))
        assertTrue(policy.accept(1_000))
        assertFalse(policy.accept(1_000))
        assertTrue(policy.accept(30_000))
        policy.reset()
        assertTrue(policy.accept(1_000))
    }
}
