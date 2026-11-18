package top.yogiczy.mytv.core.data.network

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test
import top.yogiczy.mytv.core.data.entities.channel.ChannelLine
import top.yogiczy.mytv.core.data.media.PlaybackRange
import top.yogiczy.mytv.core.data.repositories.iptv.parser.M3uIptvParser
import top.yogiczy.mytv.core.data.repositories.iptv.parser.TxtIptvParser
import top.yogiczy.mytv.core.data.utils.ChannelPlayback
import top.yogiczy.mytv.core.data.utils.Globals

class HttpUrlHeadersTest {
    private val suffix = "|User-Agent=CustomAgent&Referer=https%3A%2F%2Fsite.test%2Fwatch%3Fa%3D1%26b%3D2" +
        "&Origin=https%3A%2F%2Fsite.test&X-Token=a%2Bb%3D%3D&Cookie=id%3Done%3B%20lang%3Den"

    @Test fun acceptsTheUnencodedExampleAndPreservesTheRealUrlExactly() {
        val url = "https://example.com/?signed=a%2Bb==&other=c+d#part"
        val parsed = HttpUrlHeaders.parse("$url|User-Agent=CustomAgent&Referer=https://example.com/")
        assertEquals(url, parsed.url)
        assertEquals(mapOf("User-Agent" to "CustomAgent", "Referer" to "https://example.com/"), parsed.headers)
    }

    @Test fun decodesOnlyOptionComponentsOnceIncludingEmbeddedSeparatorsAndPlus() {
        val parsed = HttpUrlHeaders.parse("https://example.test/live" + suffix + "&Authorization=Bearer+abc%3D%3D&X-Once=%252F")
        assertEquals("https://site.test/watch?a=1&b=2", parsed.headers["Referer"])
        assertEquals("a+b==", parsed.headers["X-Token"])
        assertEquals("id=one; lang=en", parsed.headers["Cookie"])
        assertEquals("Bearer abc==", parsed.headers["Authorization"])
        assertEquals("%2F", parsed.headers["X-Once"])
    }

    @Test fun malformedOptionsCannotBreakGoodHeadersOrCreateTransportControlHeaders() {
        val parsed = HttpUrlHeaders.parse("http://example.test/live|Bad%3AName=x&Broken=%Q1&NoEquals" +
            "&Injected=ok%0D%0AX-Evil%3Ayes&Zero=%00&seekable=0&auth=digest&!X-Custom=valid&User-Agent=Good")
        assertEquals(mapOf("X-Custom" to "valid", "User-Agent" to "Good"), parsed.headers)
        assertEquals("http://example.test/live", parsed.url)
    }

    @Test fun duplicateHeaderNamesHaveOneCaseInsensitiveWinner() {
        val parsed = HttpUrlHeaders.parse("https://example.test/live|user-agent=First&USER-AGENT=Last" +
            "&X-Token=one&x-token=two&referrer=https://site.test/&encoding=gzip")
        assertEquals("Last", parsed.headers["User-Agent"])
        assertEquals(1, parsed.headers.keys.count { it.equals("X-Token", true) })
        assertEquals("two", parsed.headers["x-token"])
        assertEquals("https://site.test/", parsed.headers["Referer"])
        assertEquals("gzip", parsed.headers["Accept-Encoding"])
    }

    @Test fun encodedPipesAndNonHttpUrlsAreNotMistakenForOptions() {
        listOf("https://example.test/a%7Cb?token=a%7Cb", "udp://239.0.0.1:1234|User-Agent=Test",
            "file:///tmp/a|b", "webview://https://example.test/a|b").forEach {
            assertEquals(HttpUrlHeaders(it, emptyMap()), HttpUrlHeaders.parse(it))
        }
    }

    @Test fun suffixOverridesEntryAndGlobalHeadersWithoutMutatingOrLeakingDefaults() {
        val defaults = linkedMapOf("user-agent" to "Global", "referer" to "global", "X-Token" to "global")
        val line = ChannelLine("https://stream.test/live$suffix", httpUserAgent = "Entry",
            httpReferrer = "entry", httpOrigin = "entry")
        val resolved = line.resolveHttpOptions()
        assertEquals("CustomAgent", resolved.httpUserAgent)
        assertEquals("https://site.test/watch?a=1&b=2", resolved.httpReferrer)
        assertEquals("https://site.test", resolved.httpOrigin)
        val headers = resolved.httpRequestHeaders(defaults)
        assertEquals("CustomAgent", headers["User-Agent"])
        assertEquals("a+b==", headers["X-Token"])
        assertEquals(1, headers.keys.count { it.equals("User-Agent", true) })
        assertEquals("Global", defaults["user-agent"])
        assertEquals(defaults, ChannelLine("https://stream.test/plain").httpRequestHeaders(defaults))
        assertEquals(resolved, resolved.resolveHttpOptions())
    }

    @Test fun oldAndNewCacheRoundTripsKeepRawIdentityAndResolvedHeaders() {
        val line = ChannelLine("https://stream.test/live$suffix")
        val oldJson = """{"url":"https://stream.test/live|User-Agent=OldCached"}"""
        assertEquals("OldCached", Globals.json.decodeFromString<ChannelLine>(oldJson).resolveHttpOptions().httpUserAgent)
        val restored = Globals.json.decodeFromString<ChannelLine>(Globals.json.encodeToString(line))
        assertEquals(line.url, restored.url)
        assertEquals(line.httpRequestHeaders(), restored.httpRequestHeaders())
        val resolved = line.resolveHttpOptions()
        assertEquals(resolved, Globals.json.decodeFromString<ChannelLine>(Globals.json.encodeToString(resolved)))
    }

    @Test fun m3uAndTxtEntriesKeepTheirOwnHeadersAndLineNames() = runBlocking {
        val m3u = M3uIptvParser().parse("""
            #EXTM3U
            #EXTINF:-1 group-title="A",Protected
            #EXTVLCOPT:http-user-agent=Entry
            https://stream.test/live$suffix
            #EXTINF:-1 group-title="A",Plain
            https://stream.test/plain
        """.trimIndent())
        val first = m3u.first()
        assertEquals("https://stream.test/live$suffix", first.url)
        assertEquals("CustomAgent", ChannelLine(first.url, httpUserAgent = first.httpUserAgent).resolveHttpOptions().httpUserAgent)
        assertTrue(ChannelLine(m3u.last().url).httpRequestHeaders().isEmpty())
        val txt = TxtIptvParser().parse("""
            A,#genre#
            Protected,https://stream.test/live$suffix
        """.trimIndent()).single()
        assertEquals("https://stream.test/live$suffix", txt.url)
        assertEquals("CustomAgent", ChannelLine(txt.url).resolveHttpOptions().httpUserAgent)
        listOf("https://stream.test/live\$线路一|User-Agent=Test", "https://stream.test/live|User-Agent=Test\$线路一").forEach {
            val line = ChannelLine(it)
            assertEquals("线路一", line.name)
            assertEquals("https://stream.test/live", line.playableUrl)
            assertEquals("Test", line.resolveHttpOptions().httpUserAgent)
        }
    }

    @Test fun replayReloadKeepsHeadersForAppendAndReplacementTemplates() {
        listOf(ChannelLine("https://stream.test/PLTV/live?token=a%2Bb==$suffix"),
            ChannelLine("https://stream.test/live$suffix", catchup = "default",
                catchupSource = "https://archive.test/replay?start={b}&end={e}")).forEach { live ->
            val replay = ChannelPlayback.reloadRequest(live, PlaybackRange(1_000, 10_000), 2_000, now = 20_000)!!.line
            assertFalse(replay.url.contains('|'))
            assertEquals(live.httpRequestHeaders(), replay.httpRequestHeaders())
            assertEquals("CustomAgent", replay.httpUserAgent)
            assertEquals("a+b==", replay.httpRequestHeaders()["X-Token"])
            assertTrue(live.url.contains('|'))
        }
    }
}
