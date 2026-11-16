package top.yogiczy.mytv.core.data.repositories.iptv.parser

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test
import top.yogiczy.mytv.core.data.entities.channel.ChannelLine
import top.yogiczy.mytv.core.data.media.PlaybackRange
import top.yogiczy.mytv.core.data.utils.ChannelPlayback
import top.yogiczy.mytv.core.data.utils.Globals

class M3uHttpHeadersTest {
    @Test fun optionsApplyToAllEntryGroupsAndNeverLeakToAnotherEntry() = runBlocking {
        val entries = M3uIptvParser().parse("""
            #EXTM3U
            #EXTVLCOPT:http-origin=https://unattached.test
            #EXTINF:-1 group-title="A;B",Protected
            #EXTVLCOPT:http-referrer=https://site.test/watch?a=b=c
            #EXTVLCOPT:http-origin=https://site.test
            #EXTVLCOPT:http-user-agent=Player/1
            https://stream.test/one
            #EXTINF:-1,Plain
            https://stream.test/two
        """.trimIndent())
        assertEquals(3, entries.size)
        entries.take(2).forEach {
            assertEquals("https://site.test/watch?a=b=c", it.httpReferrer)
            assertEquals("https://site.test", it.httpOrigin)
            assertEquals("Player/1", it.httpUserAgent)
        }
        assertNull(entries.last().httpReferrer)
        assertNull(entries.last().httpOrigin)
    }

    @Test fun acceptsCaseWhitespaceQuotesAndRefererAliasWithoutMatchingOtherOptions() = runBlocking {
        val entry = M3uIptvParser().parse("""
            #EXTINF:-1,Protected
              #extvlcopt:HTTP-REFERRER = " https://site.test/first "
            #EXTVLCOPT:http-referer='https://site.test/final'
            #EXTVLCOPT:http-origin = 'https://site.test'
            #EXTVLCOPT:http-referrer=
            #EXTVLCOPT:http-origin-extra=https://wrong.test
            https://stream.test/live
        """.trimIndent()).single()
        assertEquals("https://site.test/final", entry.httpReferrer)
        assertEquals("https://site.test", entry.httpOrigin)
    }

    @Test fun entryCacheRoundTripKeepsHeadersPerLine() = runBlocking {
        val entries = M3uIptvParser().parse("""
            #EXTINF:-1 group-title="A",Same channel
            #EXTVLCOPT:http-referrer=https://first.test/
            #EXTVLCOPT:http-origin=https://first.test
            https://stream.test/one
            #EXTINF:-1 group-title="A",Same channel
            #EXTVLCOPT:http-origin=https://second.test
            https://stream.test/two
        """.trimIndent())
        val parsed = Globals.json.decodeFromString<List<IptvParser.ChannelItem>>(Globals.json.encodeToString(entries))
        val lines = parsed
        assertEquals(2, lines.size)
        assertEquals("https://first.test/", lines[0].httpReferrer)
        assertEquals("https://first.test", lines[0].httpOrigin)
        assertNull(lines[1].httpReferrer)
        assertEquals("https://second.test", lines[1].httpOrigin)
    }

    @Test fun overridesGlobalNamesCaseInsensitivelyWithoutMutatingDefaults() {
        val defaults = linkedMapOf("referer" to "global", "ORIGIN" to "global", "X-Token" to "preserved")
        val line = ChannelLine(httpReferrer = "https://channel.test/", httpOrigin = "https://channel.test")
        assertEquals(mapOf("Referer" to "https://channel.test/", "Origin" to "https://channel.test", "X-Token" to "preserved"),
            line.httpRequestHeaders(defaults))
        assertEquals("global", defaults["referer"])
        assertEquals(defaults, ChannelLine().httpRequestHeaders(defaults))
        assertEquals(emptyMap<String, String>(), ChannelLine().httpRequestHeaders())
    }

    @Test fun oldJsonStaysCompatibleAndReplayReloadPreservesHeaders() {
        val old = Globals.json.decodeFromString<ChannelLine>("""{"url":"https://stream.test/old"}""")
        assertNull(old.httpReferrer)
        assertNull(old.httpOrigin)
        val line = ChannelLine(url = "https://stream.test/PLTV/live.m3u8",
            httpReferrer = "https://site.test/", httpOrigin = "https://site.test")
        val replay = ChannelPlayback.reloadRequest(line, PlaybackRange(1_000, 10_000), 2_000, now = 20_000)!!
        assertEquals(line.httpRequestHeaders(), replay.line.httpRequestHeaders())
        assertNotEquals(line.url, replay.line.url)
    }
}
