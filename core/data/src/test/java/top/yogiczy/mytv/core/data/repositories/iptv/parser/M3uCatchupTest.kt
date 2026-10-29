package top.yogiczy.mytv.core.data.repositories.iptv.parser

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class M3uCatchupTest {
    @Test fun headerDefaultsAndChannelOverridesAreIndependent() = runBlocking {
        val items = M3uIptvParser().parse("""
            #EXTM3U catchup="append" catchup-source="?b={b:10}&d={duration}" pltv2tvod="false"
            #EXTINF:-1 group-title="A;B" catchup="default" catchup-source="https://archive.test/{b:yyyyMMdd,HHmmss}",First, HD
            https://example.test/first
            #EXTINF:-1,Second
            https://example.test/second
            #EXTINF:-1 catchup="none",Disabled
            https://example.test/PLTV/3
        """.trimIndent())
        assertEquals(4, items.size)
        items.take(2).forEach {
            assertEquals("First, HD", it.name)
            assertEquals("default", it.catchup)
            assertEquals("https://archive.test/{b:yyyyMMdd,HHmmss}", it.catchupSource)
            assertFalse(it.pltv2tvod)
        }
        assertEquals("append", items[2].catchup)
        assertEquals("?b={b:10}&d={duration}", items[2].catchupSource)
        assertEquals("none", items[3].catchup)
    }

    @Test fun kodiOptionsPreserveEqualsAndDoNotLeakAcrossChannels() = runBlocking {
        val items = M3uIptvParser().parse("""
            #EXTM3U
            #EXTINF:-1 group-title="A;B",Replay
            #KODIPROP:catchup = "append"
            #KODIPROP:catchup-source=?b={b:10}&token=a=b==
            #KODIPROP:pltv2tvod=false
            #KODIPROP:catchup-source-extra=ignored
            https://example.test/live
            #EXTINF:-1,Plain
            https://example.test/plain
        """.trimIndent())
        items.take(2).forEach {
            assertEquals("append", it.catchup)
            assertEquals("?b={b:10}&token=a=b==", it.catchupSource)
            assertFalse(it.pltv2tvod)
        }
        assertNull(items.last().catchup)
        assertNull(items.last().catchupSource)
        assertTrue(items.last().pltv2tvod)
    }

    @Test fun singleQuotedAndUnquotedAttributesAreAccepted() = runBlocking {
        val item = M3uIptvParser().parse("""
            #EXTM3U
            #EXTINF:-1 catchup=default catchup-source='https://example.test/{b:10}',Replay
            https://example.test/live
        """.trimIndent()).single()
        assertEquals("default", item.catchup)
        assertEquals("https://example.test/{b:10}", item.catchupSource)
    }
}
