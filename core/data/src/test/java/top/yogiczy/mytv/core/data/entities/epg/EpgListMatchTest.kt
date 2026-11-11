package top.yogiczy.mytv.core.data.entities.epg

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import top.yogiczy.mytv.core.data.entities.channel.Channel
import top.yogiczy.mytv.core.data.entities.epg.EpgList.Companion.match
import top.yogiczy.mytv.core.data.entities.epg.EpgList.Companion.recentProgramme

class EpgListMatchTest {
    private fun guide(id: String, name: String) = Epg(channelId = id, channelList = listOf(id, name))

    @Test fun replayInfoUsesTheFollowingBroadcastRatherThanTheLiveClock() {
        val channel = Channel(name = "Channel")
        val past = EpgProgramme(10, 20, "Replay")
        val following = EpgProgramme(20, 30, "Next after replay")
        val list = EpgList(listOf(Epg(channelList = listOf(channel.name),
            programmeList = EpgProgrammeList(listOf(past, following)))))
        assertEquals(EpgProgrammeRecent(past, following), list.recentProgramme(channel, past))
    }

    @Test fun replayNextHandlesMidnightAndRepeatedTitles() {
        val channel = Channel(name = "Channel")
        val midnight = 86_400_000L
        val past = EpgProgramme(midnight - 1_800_000, midnight, "News")
        val following = EpgProgramme(midnight, midnight + 1_800_000, "News")
        val list = EpgList(listOf(Epg(channelList = listOf(channel.name),
            programmeList = EpgProgrammeList(listOf(past, following)))))
        assertEquals(following, list.recentProgramme(channel, past)?.next)
    }

    @Test fun replayNextSelectsEarliestValidBroadcastAcrossGapsAndUnsortedInput() {
        val channel = Channel(name = "Channel")
        val past = EpgProgramme(10, 20, "Replay")
        val following = EpgProgramme(25, 30, "Following")
        val list = EpgList(listOf(Epg(channelList = listOf(channel.name), programmeList = EpgProgrammeList(listOf(
            EpgProgramme(40, 50, "Later"), EpgProgramme(15, 25, "Overlap"),
            EpgProgramme(20, 20, "Invalid"), following,
        )))))
        assertEquals(following, list.recentProgramme(channel, past)?.next)
    }

    @Test fun missingGuidePreservesReplayWithoutInventingANextProgramme() {
        val past = EpgProgramme(10, 20, "Replay")
        assertEquals(EpgProgrammeRecent(past), EpgList().recentProgramme(Channel(name = "Missing"), past))
        assertNull(EpgList().recentProgramme(Channel(name = "Missing")))
    }

    @Test fun lastBroadcastHasNoNextProgramme() {
        val channel = Channel(name = "Channel")
        val past = EpgProgramme(10, 20, "Replay")
        val list = EpgList(listOf(Epg(channelList = listOf(channel.name), programmeList = EpgProgrammeList(listOf(past)))))
        assertEquals(EpgProgrammeRecent(past), list.recentProgramme(channel, past))
    }

    @Test
    fun tvgIdTakesPriorityOverConflictingDisplayNames() {
        val wrong = guide("one", "Same name")
        val expected = guide("two", "Different name")
        assertSame(expected, EpgList(listOf(wrong, expected)).match(Channel(name = "Same name", epgName = "Same name", epgId = "two")))
    }

    @Test
    fun supportsLegacyGuideDataAndFallsBackWhenAnIdIsUnknown() {
        val expected = Epg(channelList = listOf("opaque.id", "CCTV1"))
        val list = EpgList(listOf(expected))
        assertSame(expected, list.match(Channel(name = "Other", epgId = "opaque.id")))
        assertSame(expected, list.match(Channel(name = "Other", epgName = "cctv1", epgId = "unknown")))
    }

    @Test
    fun namesIgnoreCaseAndWhitespaceAndFallBackToStandardOrVisibleName() {
        val expected = guide("one", " CCTV1 ")
        val list = EpgList(listOf(expected))
        assertSame(expected, list.match(Channel(name = "Other", epgName = " cctv1 ")))
        assertSame(expected, list.match(Channel(name = "Other", standardName = "CCTV1")))
        assertSame(expected, list.match(Channel(name = "cctv1", epgName = "Missing", standardName = "Missing")))
    }

    @Test
    fun sourceSwitchAndRefreshNeverReuseAnOldMatch() {
        val channel = Channel(name = "CCTV1", epgName = "CCTV1")
        val old = guide("old-id", "CCTV1")
        val fresh = guide("fresh-id", "CCTV1")
        assertSame(old, EpgList(listOf(old)).match(channel))
        assertSame(fresh, EpgList(listOf(fresh)).match(channel))
        assertNull(EpgList().match(channel))
        assertSame(fresh, EpgList(listOf(fresh)).match(channel))
    }

    @Test
    fun sameNameWithDifferentIdsMatchesIndependently() {
        val first = guide("one", "Shared")
        val second = guide("two", "Shared")
        val list = EpgList(listOf(first, second))
        assertSame(first, list.match(Channel(name = "Shared", epgId = "one")))
        assertSame(second, list.match(Channel(name = "Shared", epgId = "two")))
    }

    @Test
    fun doesNotUseSubstringMatchesOrMatchEmptyNames() {
        val list = EpgList(listOf(guide("ten", "CCTV10"), Epg(channelList = listOf(""))))
        assertNull(list.match(Channel(name = "CCTV1")))
        assertNull(list.match(Channel()))
    }

    @Test
    fun guideIndexesAreNotSerializedAndOldConfigurationRemainsReadable() {
        val original = EpgList(listOf(guide("id", "Name")))
        original.match(Channel(name = "Name", epgId = "id"))
        val json = Json.encodeToString(original)
        assertFalse(json.contains("byId"))
        assertFalse(json.contains("byName"))
        assertEquals(original, Json.decodeFromString<EpgList>(json))
        assertEquals("", Json.decodeFromString<Channel>("""{"name":"Old channel"}""").epgId)
        assertNull(Json.decodeFromString<Epg>("""{"channelList":["Old channel"]}""").channelId)
    }
}
