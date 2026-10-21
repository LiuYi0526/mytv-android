package top.yogiczy.mytv.core.data.entities.channel

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import top.yogiczy.mytv.core.data.entities.iptvsource.IptvSource

class ChannelHistoryListTest {
    private val source = IptvSource(name = "测试", url = "https://example.test/list")
    private fun channel(name: String, ua: String? = null) = Channel(name = name,
        lineList = ChannelLineList(listOf(ChannelLine("https://example.test/$name", httpUserAgent = ua))))

    @Test fun newestFirstAndRepeatedChannelsMoveToFront() {
        val a = channel("A"); val b = channel("B")
        val history = ChannelHistoryList().record(source, a).record(source, b).record(source, a)
        assertEquals(listOf("A", "B"), history.channels(source, ChannelList(listOf(a, b))).map { it.name })
        assertEquals(2, history.value.size)
    }

    @Test fun keepsSourcesSeparateEvenWhenChannelNamesMatch() {
        val a = channel("A")
        val other = source.copy(url = "https://other.test/list")
        val history = ChannelHistoryList().record(source, a).record(other, a)
        assertEquals(2, history.value.size)
        assertEquals(listOf(a), history.channels(source, ChannelList(listOf(a))))
        assertTrue(history.channels(source.copy(name = "其他"), ChannelList(listOf(a))).isEmpty())
    }

    @Test fun changingPlayerParametersRetainsSourceHistory() {
        val a = channel("A")
        val history = ChannelHistoryList().record(source.copy(url = source.url + "#sp;vp-ua=Old"), a)
        assertEquals(listOf(a), history.channels(source.copy(url = source.url + "#sp;vp-ua=New"), ChannelList(listOf(a))))
    }

    @Test fun showsCurrentChannelMetadataAndSkipsRemovedOrHiddenChannels() {
        val a = channel("A", "Old"); val b = channel("B")
        val updated = a.copy(logo = "https://example.test/logo", lineList = ChannelLineList(listOf(ChannelLine("https://new.test/a", "New"))))
        val history = ChannelHistoryList().record(source, a).record(source, b)
        val result = history.channels(source, ChannelList(listOf(updated)))
        assertEquals(1, result.size)
        assertEquals("New", result.first().lineList.first().httpUserAgent)
        assertEquals(updated.logo, result.first().logo)
    }

    @Test fun boundsStoredAndDisplayedHistory() {
        val available = ChannelList((0..110).map { channel("Channel$it") })
        val history = available.fold(ChannelHistoryList()) { list, item -> list.record(source, item) }
        assertEquals(100, history.value.size)
        assertEquals(20, history.channels(source, available).size)
        assertEquals("Channel110", history.channels(source, available).first().name)
    }

    @Test fun persistsAcrossJsonRoundTrip() {
        val a = channel("中文频道")
        val history = ChannelHistoryList().record(source, a)
        val restored = Json.decodeFromString<ChannelHistoryList>(Json.encodeToString(history))
        assertEquals(listOf(a), restored.channels(source, ChannelList(listOf(a))))
    }

    @Test fun ignoresEmptyChannels() {
        val history = ChannelHistoryList()
        assertSame(history, history.record(source, Channel.EMPTY))
        assertSame(history, history.record(source, Channel(name = "Empty", lineList = ChannelLineList())))
    }

    @Test fun sameNameAndEpgIdInOtherGroupsDoNotReplaceWatchedLogoOrStream() {
        val playlistLogo = "https://raw.githubusercontent.com/LiuYi0526/IPTVnew/main/TVlogos/China/CCTV/CCTV4.png"
        val watched = Channel(
            name = "CCTV-4 中文国际", epgName = "CCTV-4 (亚洲)", epgId = "cctv_cctv4",
            logo = playlistLogo,
            lineList = ChannelLineList(listOf(ChannelLine("http://127.0.0.1/3221226340/index.m3u8"))),
        )
        val otherGroup = watched.copy(
            logo = "https://example.test/cctv-news.png",
            lineList = ChannelLineList(listOf(ChannelLine("https://example.test/other-stream.m3u8"))),
        )
        val history = ChannelHistoryList().record(source, watched)
        for (available in listOf(listOf(watched, otherGroup), listOf(otherGroup, watched))) {
            val actual = history.channels(source, ChannelList(available)).single()
            assertEquals(playlistLogo, actual.logo)
            assertEquals(watched.lineList, actual.lineList)
        }
    }

    @Test fun doesNotGuessWhenAnOldStreamHasMultipleSameNamedReplacements() {
        val watched = channel("CCTV-4 中文国际")
        val first = watched.copy(lineList = ChannelLineList(listOf(ChannelLine("https://new.test/one"))))
        val second = watched.copy(lineList = ChannelLineList(listOf(ChannelLine("https://new.test/two"))))
        val history = ChannelHistoryList().record(source, watched)
        assertTrue(history.channels(source, ChannelList(listOf(first, second))).isEmpty())
    }
}
