package top.yogiczy.mytv.core.data.entities.channel

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import top.yogiczy.mytv.core.data.entities.iptvsource.IptvSource

class ChannelDisplayModeListTest {
    private val source = IptvSource(name = "测试源", url = "https://example.test/list#sp;vp-ua=Old")
    private val channel = Channel(name = "CCTV-5", groupName = "移动", index = 8,
        lineList = ChannelLineList(listOf(ChannelLine("https://example.test/live"), ChannelLine("https://backup.test/live"))))
    private val available = ChannelList(listOf(channel))

    @Test fun jsonRoundTripRetainsPerChannelModeAndOldEmptyDataWorks() {
        val saved = ChannelDisplayModeList().remember(source, channel, available, 3)
        val restored = Json.decodeFromString<ChannelDisplayModeList>(Json.encodeToString(saved))
        assertEquals(3, restored.mode(source, channel, available))
        assertNull(Json.decodeFromString<ChannelDisplayModeList>("{}").mode(source, channel, available))
    }

    @Test fun samePlaybackUrlInOtherGroupsAndSourcesStaysIndependent() {
        val other = channel.copy(groupName = "央视")
        val channels = ChannelList(listOf(channel, other))
        val saved = ChannelDisplayModeList().remember(source, channel, channels, 3)
            .remember(source, other, channels, 4)
        assertEquals(3, saved.mode(source, channel, channels))
        assertEquals(4, saved.mode(source, other, channels))
        assertNull(saved.mode(source.copy(name = "其它源"), channel, channels))
        assertNull(saved.mode(source.copy(url = "https://other.test/list"), channel, channels))
    }

    @Test fun duplicateNamesInSameGroupRequireMatchingUrls() {
        val other = channel.copy(lineList = ChannelLineList(listOf(ChannelLine("https://other.test/live"))))
        val channels = ChannelList(listOf(channel, other))
        val saved = ChannelDisplayModeList().remember(source, channel, channels, 3)
            .remember(source, other, channels, 5)
        assertEquals(3, saved.mode(source, channel, channels))
        assertEquals(5, saved.mode(source, other, channels))
        val changed = channel.copy(lineList = ChannelLineList(listOf(ChannelLine("https://new.test/live"))))
        assertNull(saved.mode(source, changed, ChannelList(listOf(changed, other))))
    }

    @Test fun uniqueChannelSurvivesUrlRefreshRenumberingAndNewHeaders() {
        val changed = channel.copy(index = 50, lineList = ChannelLineList(listOf(ChannelLine("https://new.test/live", "NewUA"))))
        val current = ChannelList(listOf(changed))
        val saved = ChannelDisplayModeList().remember(source, channel, available, 3)
        assertEquals(3, saved.mode(source, changed, current))
        val updated = saved.remember(source, changed, current, 2)
        assertEquals(1, updated.value.size)
        assertEquals(2, updated.mode(source, changed, current))
    }

    @Test fun followGlobalRemovesOnlyCurrentChannelOverride() {
        val other = channel.copy(name = "CCTV-6")
        val channels = ChannelList(listOf(channel, other))
        val saved = ChannelDisplayModeList().remember(source, channel, channels, 3)
            .remember(source, other, channels, 2).remember(source, channel, channels, null)
        assertNull(saved.mode(source, channel, channels))
        assertEquals(2, saved.mode(source, other, channels))
    }

    @Test fun playerParametersAndMetadataDoNotInvalidateMemory() {
        val saved = ChannelDisplayModeList().remember(source, channel, available, 0)
        assertEquals(0, saved.mode(source.copy(url = "https://example.test/list#sp;vp-ua=New"),
            channel.copy(logo = "https://logo.test/new", epgName = "新节目单名"), available))
    }

    @Test fun sourceRenameMigratesButChangingSourceUrlDropsOldMemory() {
        val saved = ChannelDisplayModeList().remember(source, channel, available, 3)
        val renamed = source.copy(name = "新名称")
        assertEquals(3, saved.updateSource(source, renamed).mode(renamed, channel, available))
        assertTrue(saved.updateSource(source, source.copy(url = "https://other.test/list")).value.isEmpty())
    }

    @Test fun blankChannelDoesNotCreatePreference() {
        val saved = ChannelDisplayModeList()
        assertSame(saved, saved.remember(source, Channel.EMPTY, available, 3))
        assertSame(saved, saved.remember(source, channel.copy(lineList = ChannelLineList()), available, 3))
    }
}
