package top.yogiczy.mytv.core.data.repositories.iptv.parser

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class M3uEpgIdTest {
    @Test
    fun keepsTvgIdSeparateFromTheDisplayName() = runBlocking {
        val entries = M3uIptvParser().parse("""
            #EXTM3U
            #EXTINF:-1 tvg-id=" opaque-HD.Id " tvg-name="CCTV1" group-title="A;B",Visible name
            http://example.test/live.m3u8
        """.trimIndent())
        assertEquals(2, entries.size)
        entries.forEach {
            assertEquals("opaque-HD.Id", it.epgId)
            assertEquals("CCTV1", it.epgName)
            assertEquals("Visible name", it.name)
        }
    }

    @Test
    fun missingTvgNameStillPreservesIdAndVisibleName() = runBlocking {
        val entries = M3uIptvParser().parse("""
            #EXTM3U
            #EXTINF:-1 tvg-id="channel.1",Display
            http://example.test/one
            #EXTINF:-1,Legacy
            http://example.test/two
        """.trimIndent())
        assertEquals("channel.1", entries[0].epgId)
        assertEquals("Display", entries[0].epgName)
        assertEquals("", entries[1].epgId)
        assertEquals("Legacy", entries[1].epgName)
    }
}
