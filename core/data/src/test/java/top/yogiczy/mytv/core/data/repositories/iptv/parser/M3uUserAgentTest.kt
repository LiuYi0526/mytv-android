package top.yogiczy.mytv.core.data.repositories.iptv.parser

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class M3uUserAgentTest {
    @Test fun vlcUserAgentAppliesToAllGroupsWithoutLeakingToNextLine() = runBlocking {
        val userAgent = "Mozilla/5.0 (Linux; Android 16; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/101.0.4951.61 Safari/537.36"
        val entries = M3uIptvParser().parse("""
            #EXTM3U
            #EXTINF:-1 tvg-id="mod_200" group-title="Taiwan;Sports",200 ELTA Sports 1
            #EXTVLCOPT:http-user-agent=$userAgent
            http://example.test/elta/index.m3u8
            #EXTINF:-1 group-title="Taiwan;Sports",200 ELTA Sports 1
            http://example.test/elta/backup.m3u8
        """.trimIndent())
        assertEquals(4, entries.size)
        entries.groupBy { it.groupName }.values.forEach { lines ->
            assertEquals(2, lines.size)
            assertEquals(userAgent, lines[0].httpUserAgent)
            assertEquals("mod_200", lines[0].epgId)
            assertNull(lines[1].httpUserAgent)
        }
    }

    @Test fun vlcOptionOverridesInlineAttributeAndKeepsEqualsInValue() = runBlocking {
        val entries = M3uIptvParser().parse("""
            #EXTM3U
            #EXTINF:-1 http-user-agent="Inline/1",Channel
              #EXTVLCOPT:http-user-agent = "Browser/1 token=a=b; platform=TV"
            #EXTVLCOPT:http-referrer=https://example.test/
            http://example.test/live
        """.trimIndent())
        assertEquals("Browser/1 token=a=b; platform=TV", entries.single().httpUserAgent)
    }

    @Test fun emptyOrUnrelatedOptionsDoNotEraseInlineUserAgent() = runBlocking {
        val entries = M3uIptvParser().parse("""
            #EXTM3U
            #EXTINF:-1 http-user-agent="Inline/1",Channel
            #EXTVLCOPT:http-user-agent=
            #EXTVLCOPT:http-user-agent-extra=Other/1
            http://example.test/live
        """.trimIndent())
        assertEquals("Inline/1", entries.single().httpUserAgent)
    }

    @Test fun optionBeforeAnEntryDoesNotLeakIntoTheNextChannel() = runBlocking {
        val entries = M3uIptvParser().parse("""
            #EXTM3U
            #EXTVLCOPT:http-user-agent=Unattached/1
            #EXTINF:-1,Channel
            http://example.test/live
        """.trimIndent())
        assertNull(entries.single().httpUserAgent)
    }
}
