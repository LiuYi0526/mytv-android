package top.yogiczy.mytv.core.data.repositories.iptv.parser

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import top.yogiczy.mytv.core.data.media.ClearKeyLicense

class M3uDrmTest {
    @Test fun kodiClearKeySurvivesParsingAcrossGroupsWithoutLeaking() = runBlocking {
        val entries = M3uIptvParser().parse("""
            #EXTM3U
            #EXTINF:-1 tvg-id="starhubtvplus_101" group-title="Starhub TV+;Test",101 Preview Channel
            #KODIPROP:inputstreamaddon=inputstream.adaptive
            #KODIPROP:inputstream.adaptive.manifest_type=mpd
            #KODIPROP:inputstream.adaptive.license_type=org.w3.clearkey
            #KODIPROP:inputstream.adaptive.license_key=94472526cc92471493d46543faefadda:36658f2faa1f09f206a2f448ac653e2d
            https://example.test/manifest.mpd
            #EXTINF:-1,Plain
            https://example.test/plain.mpd
        """.trimIndent())
        assertEquals(3, entries.size)
        entries.take(2).forEach {
            assertEquals("mpd", it.manifestType)
            assertTrue(ClearKeyLicense.isSupported(it.licenseType))
            assertTrue(ClearKeyLicense.response(it.licenseKey).isNotEmpty())
        }
        assertNull(entries.last().licenseKey)
        assertNull(entries.last().licenseType)
        assertNull(entries.last().manifestType)
    }

    @Test fun optionsUseExactNamesAndPreserveEqualsInValues() = runBlocking {
        val entry = M3uIptvParser().parse("""
            #EXTINF:-1,Channel
            #KODIPROP:inputstream.adaptive.manifest_type = "MPD"
            #KODIPROP:inputstream.adaptive.license_type = "org.w3.clearkey"
            #KODIPROP:inputstream.adaptive.license_key=https://example.test/license?token=a=b==
            #KODIPROP:inputstream.adaptive.license_key-extra=ignored
            https://example.test/live
        """.trimIndent()).single()
        assertEquals("MPD", entry.manifestType)
        assertEquals("org.w3.clearkey", entry.licenseType)
        assertEquals("https://example.test/license?token=a=b==", entry.licenseKey)
    }
}
