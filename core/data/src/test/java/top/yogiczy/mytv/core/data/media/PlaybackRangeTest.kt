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
}
