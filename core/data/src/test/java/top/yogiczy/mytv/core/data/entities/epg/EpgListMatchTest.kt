package top.yogiczy.mytv.core.data.entities.epg

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import top.yogiczy.mytv.core.data.entities.channel.Channel
import top.yogiczy.mytv.core.data.entities.epg.EpgList.Companion.match

class EpgListMatchTest {
    private fun guide(id: String, name: String) = Epg(channelId = id, channelList = listOf(id, name))

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
