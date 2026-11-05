package top.yogiczy.mytv.core.data.entities.channel

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import top.yogiczy.mytv.core.data.entities.channel.ChannelGroupList.Companion.channelGroupIdx
import top.yogiczy.mytv.core.data.entities.channel.ChannelGroupList.Companion.channelIdx
import top.yogiczy.mytv.core.data.entities.channel.ChannelGroupList.Companion.channelList

class ChannelSelectionTest {
    private val shopping = Channel(name = "中视购物",
        lineList = ChannelLineList(listOf(ChannelLine("https://example.test/shopping"))))
    private fun groups() = ChannelGroupList(listOf(
        ChannelGroup("中国央视", ChannelList(listOf(shopping.copy()))),
        ChannelGroup("江西移动PLTV", ChannelList(listOf(shopping.copy()))),
    )).withMetadata()

    @Test fun duplicateStreamsHaveIndependentNumbersAndSourceGroups() {
        val channels = groups().channelList
        assertEquals(listOf(0, 1), channels.map { it.index })
        assertEquals(listOf("中国央视", "江西移动PLTV"), channels.map { it.groupName })
    }

    @Test fun selectedDuplicateIsLocatedInItsOwnGroupAndGlobalPosition() {
        val groups = groups()
        val selected = groups[1].channelList.single()
        assertEquals(1, groups.channelGroupIdx(selected))
        assertEquals(1, groups.channelIdx(selected))
    }

    @Test fun savedSelectionRestoresTheSameGroupAfterJsonRoundTrip() {
        val groups = groups()
        val saved = Json.decodeFromString<Channel>(Json.encodeToString(groups[1].channelList.single()))
        assertEquals(1, groups.channelGroupIdx(saved))
        assertSame(groups[1].channelList.single(), groups.channelList.resolveChannel(saved))
    }

    @Test fun sourceGroupTakesPriorityWhenPlaylistReorderingChangesNumbers() {
        val groups = groups()
        val saved = groups[1].channelList.single()
        val reordered = ChannelGroupList(groups.reversed()).withMetadata()
        assertSame(reordered[0].channelList.single(), reordered.channelList.resolveChannel(saved))
        assertEquals(0, reordered.channelGroupIdx(saved))
    }

    @Test fun resolvingSameStreamUsesLatestPlaybackConfigurationInSelectedGroup() {
        val groups = groups()
        val saved = groups[1].channelList.single()
        val updated = saved.copy(lineList = ChannelLineList(listOf(saved.lineList.single().copy(httpUserAgent = "New UA"))))
        val available = ChannelList(listOf(groups[0].channelList.single(), updated))
        assertSame(updated, available.resolveChannel(saved))
    }

    @Test fun oldSelectionWithoutGroupMetadataCanStillUseItsVerifiedNumber() {
        val groups = groups()
        assertEquals(1, groups.channelGroupIdx(shopping.copy(index = 1)))
        assertEquals(0, groups.channelGroupIdx(shopping))
    }

    @Test fun sameUrlWithDifferentNameAndSameNameWithDifferentUrlStayDistinct() {
        val anotherName = shopping.copy(name = "Other name", index = 1)
        val anotherUrl = shopping.copy(lineList = ChannelLineList(listOf(ChannelLine("https://example.test/other"))), index = 2)
        val available = ChannelList(listOf(shopping.copy(index = 0), anotherName, anotherUrl))
        assertSame(anotherName, available.resolveChannel(anotherName.copy()))
        assertSame(anotherUrl, available.resolveChannel(anotherUrl.copy()))
        assertNull(available.resolveChannel(shopping.copy(name = "Missing")))
    }

    @Test fun historyRetainsMostRecentlySelectedGroupForDuplicateStreams() {
        val groups = groups()
        val source = top.yogiczy.mytv.core.data.entities.iptvsource.IptvSource(name = "Test", url = "https://example.test/list")
        val history = ChannelHistoryList().record(source, groups[0].channelList.single())
            .record(source, groups[1].channelList.single())
        assertEquals(1, history.value.size)
        assertEquals("江西移动PLTV", history.channels(source, groups.channelList).single().groupName)
    }

    @Test fun oldJsonWithoutSourceGroupRemainsReadable() {
        assertEquals("", Json.decodeFromString<Channel>("""{"name":"中视购物"}""").groupName)
    }
}
