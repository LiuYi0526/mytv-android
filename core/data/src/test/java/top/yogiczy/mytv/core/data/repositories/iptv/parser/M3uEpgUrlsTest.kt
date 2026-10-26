package top.yogiczy.mytv.core.data.repositories.iptv.parser

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class M3uEpgUrlsTest {
    private val parser = M3uIptvParser()

    @Test fun detectsUrlTvg() = runBlocking {
        assertEquals(listOf("https://example.test/guide.xml.gz"),
            parser.getEpgUrls("""#EXTM3U url-tvg="https://example.test/guide.xml.gz""""))
    }

    @Test fun detectsXTvgUrlWithMultipleGuides() = runBlocking {
        assertEquals(listOf("https://example.test/a.xml", "http://example.test/b.xml"),
            parser.getEpgUrls("""#EXTM3U x-tvg-url=" https://example.test/a.xml, http://example.test/b.xml """"))
    }

    @Test fun mergesBothAttributesWithoutDuplicateGuides() = runBlocking {
        assertEquals(listOf("https://example.test/a.xml", "https://example.test/b.xml"),
            parser.getEpgUrls("""#EXTM3U url-tvg="https://example.test/a.xml" x-tvg-url="https://example.test/a.xml,https://example.test/b.xml""""))
    }

    @Test fun supportsBomWhitespaceQuotesAndCase() = runBlocking {
        val data = "\uFEFF  #EXTM3U X-TVG-URL = 'https://example.test/a.xml'\r\n#EXTINF:-1,Test\r\nhttps://example.test/live"
        assertTrue(parser.isSupport("https://example.test/tv.m3u", data))
        assertEquals(listOf("https://example.test/a.xml"), parser.getEpgUrls(data))
        assertEquals("Test", parser.parse(data).single().name)
    }

    @Test fun supportsUnquotedUrlAndKeepsQueryIntact() = runBlocking {
        val url = "https://example.test/epg?token=a+b==&mode=xml"
        assertEquals(listOf(url), parser.getEpgUrls("#EXTM3U url-tvg=$url"))
    }

    @Test fun emptyOrInvalidFirstAttributeDoesNotHideTheOther() = runBlocking {
        assertEquals(listOf("https://example.test/b.xml"),
            parser.getEpgUrls("""#EXTM3U x-tvg-url=" ,file:///private.xml,https://,bad" url-tvg="https://example.test/b.xml""""))
    }

    @Test fun onlyReadsSubscriptionHeaderAttributes() = runBlocking {
        val data = """
            #EXTM3U fake-url-tvg="https://example.test/not-a-guide.xml"
            #EXTINF:-1 url-tvg="https://example.test/channel.xml",Test
            https://example.test/live
        """.trimIndent()
        assertTrue(parser.getEpgUrls(data).isEmpty())
        assertTrue(parser.getEpgUrls("# comment\n#EXTM3U url-tvg=\"https://example.test/a.xml\"").isEmpty())
    }

    @Test fun retainsSingleGuideApiForExistingCallers() = runBlocking {
        val data = """#EXTM3U x-tvg-url="https://example.test/a.xml,https://example.test/b.xml""""
        assertEquals("https://example.test/a.xml", parser.getEpgUrl(data))
        assertNull(parser.getEpgUrl("#EXTM3U"))
    }
}
