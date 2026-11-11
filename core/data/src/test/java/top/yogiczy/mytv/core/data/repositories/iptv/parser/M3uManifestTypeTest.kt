package top.yogiczy.mytv.core.data.repositories.iptv.parser

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test
import top.yogiczy.mytv.core.data.entities.channel.ChannelLine
import top.yogiczy.mytv.core.data.utils.Globals

class M3uManifestTypeTest {
    @Test fun hlsOptionsBelongToAllGroupsOfTheEntryWithoutLeakingToTheNext() = runBlocking {
        val entries = M3uIptvParser().parse("""
            #EXTM3U
            #EXTINF:-1 group-title="A;B",HLS
            #KODIPROP:inputstream.adaptive.manifest_type = " HLS "
            #EXTVLCOPT:http-user-agent=PlayerTest/1
            https://example.test/entry?token=a=b
            #EXTINF:-1,Automatic
            https://example.test/plain.m3u8
        """.trimIndent())
        assertEquals(3, entries.size)
        entries.take(2).forEach {
            assertEquals(" HLS ", it.manifestType)
            assertEquals("PlayerTest/1", it.httpUserAgent)
            assertEquals("https://example.test/entry?token=a=b", it.url)
        }
        assertNull(entries.last().manifestType)
        assertNull(entries.last().httpUserAgent)
    }

    @Test fun aliasesAndCaseSurviveParsedEntrySerialization() = runBlocking {
        for (hint in listOf("hls", "m3u8", "HlS", " M3U8 ")) {
            val entries = M3uIptvParser().parse("""
                #EXTINF:-1 group-title="A",Channel
                #KODIPROP:inputstream.adaptive.manifest_type="$hint"
                https://example.test/entry
            """.trimIndent())
            val cached = Globals.json.decodeFromString<List<IptvParser.ChannelItem>>(Globals.json.encodeToString(entries))
            assertEquals(hint, cached.single().manifestType)
        }
    }

    @Test fun missingAndUnknownHintsStayCompatibleWithOldChannels() = runBlocking {
        val old = Globals.json.decodeFromString<ChannelLine>("""{"url":"https://example.test/old"}""")
        assertNull(old.manifestType)
        val entry = M3uIptvParser().parse("""
            #EXTINF:-1,Channel
            #KODIPROP:inputstream.adaptive.manifest_type=future-format
            #KODIPROP:inputstream.adaptive.manifest_type-extra=hls
            https://example.test/entry
        """.trimIndent()).single()
        assertEquals("future-format", entry.manifestType)
    }
}
