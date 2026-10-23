package top.yogiczy.mytv.core.data.entities.iptvsource

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import top.yogiczy.mytv.core.data.entities.channel.Channel
import top.yogiczy.mytv.core.data.entities.channel.ChannelHistoryItem
import top.yogiczy.mytv.core.data.entities.channel.ChannelHistoryList

class IptvSourceEditTest {
    private val source = IptvSource("IPTV", "http://example.com/tv.m3u#sp;vp-ua=test", transformJs = "function main(x) { return x; }")

    @Test fun oldSavedSourcesDecodeWithNoExplicitGuides() {
        val old = Json.decodeFromString<IptvSource>("""{"name":"IPTV","url":"http://example.com/tv.m3u","isLocal":false,"transformJs":"script"}""")
        assertTrue(old.epgUrls.isEmpty())
        assertEquals("script", old.transformJs)
    }

    @Test fun editsTrimFieldsAndKeepPlayerParametersAndTransformScript() {
        val edited = IptvSourceEdit.prepare(source, " 新名称 ", " ${source.url} ", " https://epg.test/a.xml\n\nhttps://epg.test/b.xml.gz\nhttps://epg.test/a.xml ", emptyList())
        assertEquals("新名称", edited.name)
        assertEquals(source.url, edited.url)
        assertEquals(source.transformJs, edited.transformJs)
        assertEquals(listOf("https://epg.test/a.xml", "https://epg.test/b.xml.gz"), edited.epgUrls)
        assertEquals(edited, Json.decodeFromString<IptvSource>(Json.encodeToString(edited)))
    }

    @Test fun changingGuideSettingsKeepsExistingChannelCacheKey() {
        val oldKey = 31 * (31 * (31 * source.name.hashCode() + source.url.hashCode()) + source.isLocal.hashCode()) + source.transformJs.hashCode()
        assertEquals(oldKey, source.channelCacheKey())
        assertEquals(oldKey, source.copy(epgUrls = listOf("https://epg.test/tv.xml")).channelCacheKey())
    }

    @Test fun fileUrlsBecomeLocalPaths() {
        val edited = IptvSourceEdit.prepare(source, "local", "file:///storage/emulated/0/Download/tv.m3u", "", emptyList())
        assertTrue(edited.isLocal)
        assertEquals("/storage/emulated/0/Download/tv.m3u", edited.url)
        assertTrue(IptvSourceEdit.prepare(source, "local", "/storage/tv.m3u", "", emptyList()).isLocal)
    }

    @Test fun aRemoteEditCanReplaceALocalSource() {
        assertFalse(IptvSourceEdit.prepare(source.copy(isLocal = true), "IPTV", "https://example.com/new.m3u", "", emptyList()).isLocal)
    }

    @Test fun invalidOrDuplicateValuesAreRejected() {
        fun rejected(name: String, url: String, epg: String = "", others: List<IptvSource> = emptyList()) {
            try { IptvSourceEdit.prepare(source, name, url, epg, others); fail("Invalid input was accepted") }
            catch (_: IllegalArgumentException) { }
        }
        rejected(" ", source.url)
        rejected("IPTV", "")
        rejected("IPTV", "example.com/tv.m3u")
        rejected("IPTV", "http://example.com/a\nhttp://example.com/b")
        rejected("IPTV", source.url, "bad guide")
        rejected("IPTV", source.url, others = listOf(source.copy(url = "http://other.test/tv.m3u")))
    }

    @Test fun renamingAndChangingUrlPreservesOnlyMatchingHistoryAssociation() {
        val history = ChannelHistoryList(listOf(
            ChannelHistoryItem("IPTV", "http://example.com/tv.m3u", Channel.EMPTY),
            ChannelHistoryItem("IPTV", "http://other.test/tv.m3u", Channel.EMPTY),
            ChannelHistoryItem("Other", "http://example.com/tv.m3u", Channel.EMPTY),
        ))
        val updated = source.copy(name = "renamed", url = "https://example.com/new.m3u#sp;vp-ua=new")
        val result = history.updateSource(source, updated)
        assertEquals("renamed", result.value[0].sourceName)
        assertEquals("https://example.com/new.m3u", result.value[0].sourceUrl)
        assertEquals(history.value.drop(1), result.value.drop(1))
    }
}
