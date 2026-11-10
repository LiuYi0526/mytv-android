package top.yogiczy.mytv.core.data.media

import org.junit.Assert.*
import org.junit.Test
import top.yogiczy.mytv.core.data.entities.epg.EpgProgramme

class PlaybackControllerTimelineTest {
    private val start = 1_793_271_600_000L
    private val programme = EpgProgramme(start, start + 600_000, "直播")

    private fun resolve(duration: Long = 0, absolute: Boolean = false,
                        replay: EpgProgramme? = null, live: EpgProgramme? = programme,
                        supported: Boolean = true, now: Long = start + 120_000) =
        PlaybackControllerTimeline.resolve(12_000, duration, absolute, replay, live, supported, now)

    @Test fun missingNativeTimelineUsesGuideClockAndRange() {
        val timeline = resolve()
        assertEquals(start + 120_000, timeline.position)
        assertEquals(start to programme.endAt, timeline.range)
        assertTrue(timeline.absolute)
        assertTrue(timeline.epgFallback)
        assertTrue(timeline.seekEnabled)
    }

    @Test fun guideProgressDoesNotAdvertiseUnsupportedReplay() {
        val timeline = resolve(supported = false)
        assertEquals(start + 120_000, timeline.position)
        assertTrue(timeline.epgFallback)
        assertFalse(timeline.seekEnabled)
    }

    @Test fun validRelativeMediaKeepsItsOwnPositionAndDuration() {
        val timeline = resolve(duration = 600_000)
        assertEquals(12_000, timeline.position)
        assertEquals(0L to 600_000L, timeline.range)
        assertFalse(timeline.absolute)
    }

    @Test fun absoluteEngineAndReplayKeepTheEngineClock() {
        val timeline = resolve(absolute = true, replay = programme)
        assertEquals(12_000, timeline.position)
        assertEquals(start to programme.endAt, timeline.range)
        assertTrue(timeline.absolute)
        assertFalse(timeline.epgFallback)
    }

    @Test fun replayNeverSubstitutesTheLiveWallClock() {
        assertFalse(resolve(replay = programme).epgFallback)
    }

    @Test fun missingOrStaleGuideCannotInventAClock() {
        assertEquals(0L to 0L, resolve(live = null).range)
        assertFalse(resolve(now = programme.endAt).epgFallback)
        assertFalse(resolve(now = start - 1).epgFallback)
        assertTrue(resolve(duration = Long.MIN_VALUE + 1).epgFallback)
    }
}
