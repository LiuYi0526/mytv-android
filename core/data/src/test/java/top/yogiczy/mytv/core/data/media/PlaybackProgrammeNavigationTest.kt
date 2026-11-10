package top.yogiczy.mytv.core.data.media

import org.junit.Assert.*
import org.junit.Test
import top.yogiczy.mytv.core.data.entities.channel.ChannelLine
import top.yogiczy.mytv.core.data.entities.epg.EpgProgramme

class PlaybackProgrammeNavigationTest {
    private val first = EpgProgramme(100, 200, "同名节目")
    private val second = EpgProgramme(200, 300, "同名节目")
    private val third = EpgProgramme(300, 400, "同名节目")
    private val line = ChannelLine("https://example.test/PLTV/live.m3u8")

    @Test fun adjacentUsesTimesForRepeatedTitlesAndUnsortedGuide() {
        val guide = listOf(third, first, second)
        assertEquals(first, PlaybackProgrammeNavigation.adjacent(guide, second, -1))
        assertEquals(third, PlaybackProgrammeNavigation.adjacent(guide, second, 1))
    }

    @Test fun boundariesAndMissingGuideDoNotInventProgrammes() {
        assertNull(PlaybackProgrammeNavigation.adjacent(listOf(first), first, -1))
        assertNull(PlaybackProgrammeNavigation.adjacent(listOf(first), first, 1))
        assertNull(PlaybackProgrammeNavigation.adjacent(emptyList(), first, 1))
        assertNull(PlaybackProgrammeNavigation.adjacent(listOf(first), null, 1))
    }

    @Test fun malformedAndDuplicateTimeSlotsAreSkipped() {
        val guide = listOf(first, second, second.copy(title = "别名"), EpgProgramme(250, 250), third)
        assertEquals(third, PlaybackProgrammeNavigation.adjacent(guide, second, 1))
    }

    @Test fun neighboursCanCrossMidnight() {
        val before = first.copy(startAt = 86_300_000, endAt = 86_400_000)
        val after = second.copy(startAt = before.endAt, endAt = 86_500_000)
        assertEquals(before, PlaybackProgrammeNavigation.adjacent(listOf(before, after), after, -1))
    }

    @Test fun onlyStartedProgrammesWithValidReplayRulesCanStart() {
        assertTrue(PlaybackProgrammeNavigation.canStart(second, line, 250))
        assertTrue(PlaybackProgrammeNavigation.canStart(first, line, 250))
        assertFalse(PlaybackProgrammeNavigation.canStart(third, line, 250))
        assertFalse(PlaybackProgrammeNavigation.canStart(third, line, 300))
        assertFalse(PlaybackProgrammeNavigation.canStart(second, line.copy(catchup = "none"), 250))
        assertFalse(PlaybackProgrammeNavigation.canStart(second, ChannelLine("https://example.test/live.m3u8"), 250))
    }

    @Test fun malformedOrMissingProgrammeCannotStart() {
        assertFalse(PlaybackProgrammeNavigation.canStart(null, line, 250))
        assertFalse(PlaybackProgrammeNavigation.canStart(EpgProgramme(200, 100), line, 250))
    }
}
