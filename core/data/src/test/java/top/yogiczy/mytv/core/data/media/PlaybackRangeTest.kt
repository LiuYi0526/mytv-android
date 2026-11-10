package top.yogiczy.mytv.core.data.media

import org.junit.Assert.*
import org.junit.Test

class PlaybackRangeTest {
    private val start = 1793271600000L
    private val range = PlaybackRange(start, start + 600_000)

    @Test fun startOverStartsAtWindowBeginning() {
        assertEquals(0L, range.windowPosition(start, start, 3_600_000))
    }

    @Test fun programmeInsideLargerWindowUsesItsOwnOffset() {
        assertEquals(3_600_000L, range.windowPosition(start, start - 3_600_000, 7_200_000))
    }

    @Test fun staticManifestsWithoutWallClockStartAtZero() {
        assertEquals(0L, range.windowPosition(start, null, 608_000))
        assertEquals(start + 100_000, range.absolutePosition(100_000, null))
        assertEquals(600_000L, range.durationMs)
    }

    @Test fun manifestSegmentRoundingIsAllowedOnlyAtProgrammeStart() {
        assertEquals(0L, range.windowPosition(start, start + 40_000, 3_600_000))
        assertNull(range.windowPosition(start, start + 90_000, 3_600_000))
        assertNull(range.windowPosition(start + 120_000, start + 121_000, 3_600_000))
    }

    @Test fun retryRetainsUtcPositionWhenWindowMoves() {
        val resumeAt = start + 120_000
        assertEquals(120_000L, range.windowPosition(resumeAt, start, 3_600_000))
        assertEquals(80_000L, range.windowPosition(resumeAt, start + 40_000, 3_600_000))
        assertEquals(resumeAt, range.absolutePosition(80_000, start + 40_000))
    }

    @Test fun unavailableHistoryMustNotJumpToLiveEdge() {
        assertNull(range.windowPosition(start, start + 3_600_000, 3_600_000))
        assertNull(range.windowPosition(start + 300_000, start, 240_000))
    }

    @Test fun progressIsClampedToSelectedProgramme() {
        assertEquals(start, range.absolutePosition(0, start - 300_000))
        assertEquals(range.endAt, range.absolutePosition(3_600_000, start))
        assertEquals(600_000L, range.windowPosition(range.endAt + 60_000, start, 3_600_000))
    }

    @Test(expected = IllegalArgumentException::class)
    fun emptyProgrammeIsRejected() {
        PlaybackRange(start, start)
    }

    @Test fun reloadedStaticWindowStartsAtRequestedTimeAndKeepsProgrammeDuration() {
        val reloaded = range.copy(sourceStartAt = start + 120_000)
        assertEquals(600_000L, reloaded.durationMs)
        assertEquals(0L, reloaded.windowPosition(start + 120_000, null, 480_000))
        assertEquals(start + 180_000, reloaded.absolutePosition(60_000, null))
        assertNull(reloaded.windowPosition(start + 60_000, null, 480_000))
    }

    @Test fun reloadedDynamicWindowStillUsesManifestClock() {
        val reloaded = range.copy(sourceStartAt = start + 120_000)
        assertEquals(0L, reloaded.windowPosition(start + 120_000, start + 122_000, 480_000))
        assertEquals(start + 182_000, reloaded.absolutePosition(60_000, start + 122_000))
    }

    @Test fun continuousTimelineCanCrossProgrammeEndWithoutClampingOrRejectingTheNewWindow() {
        val continuous = range.copy(continuous = true)
        assertEquals(start + 720_000, continuous.absolutePosition(720_000, null))
        assertEquals(60_000L, continuous.windowPosition(start + 720_000, start + 660_000, 300_000))
    }

}
