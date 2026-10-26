package top.yogiczy.mytv.core.data.entities.epgsource

import org.junit.Assert.*
import org.junit.Test
import top.yogiczy.mytv.core.data.entities.iptvsource.IptvSource

class EpgSourceCatalogTest {
    private val a = EpgSource("A", "https://example.test/a.xml")
    private val b = EpgSource("B", "https://example.test/b.xml.gz")

    @Test fun migratesStoredDefaultAndLegacySubscriptionGuidesWithoutDuplicates() {
        val result = EpgSourceCatalog.migrate(listOf(a), b,
            listOf(IptvSource("TV", "https://example.test/tv.m3u", epgUrls = listOf(a.url, "https://example.test/c.xml"))))
        assertEquals(listOf(a.url, b.url, "https://example.test/c.xml"), result.map { it.url })
        assertEquals("TV 节目单", result.last().name)
    }

    @Test fun repeatedReconciliationPreservesUserNamesAndOrder() {
        val result = EpgSourceCatalog.migrate(listOf(a, b), b, listOf(IptvSource(epgUrls = listOf(b.url, a.url))))
        assertEquals(listOf(a, b), result.value)
        assertEquals(result, EpgSourceCatalog.migrate(result, b, emptyList()))
    }

    @Test fun importsAreUniqueByUrlAndGeneratedNamesDoNotCollide() {
        val result = EpgSourceCatalog.register(listOf(a), listOf(" ${a.url} ", b.url, b.url), "A")
        assertEquals(2, result.size)
        assertEquals("A 2", result.last().name)
        assertEquals(a, result.first())
    }

    @Test fun invalidDetectedUrlsDoNotEnterTheCatalog() {
        assertTrue(EpgSourceCatalog.register(emptyList(), listOf("", "file:///secret.xml", "https://", "not a URL"), "EPG").isEmpty())
    }

    @Test fun explicitSelectionWorksWithoutAFollowSettingAndKeepsPriority() {
        val source = IptvSource(epgUrls = listOf(b.url, a.url, b.url))
        assertEquals(listOf(b, a), EpgSourceCatalog.resolve(source, listOf(a, b), a))
    }

    @Test fun unboundSubscriptionUsesTheLatestDefault() {
        assertEquals(listOf(b), EpgSourceCatalog.resolve(IptvSource(), listOf(a, b), b))
    }

    @Test fun unknownReferencesFallBackToDefault() {
        assertEquals(listOf(a), EpgSourceCatalog.resolve(IptvSource(epgUrls = listOf(b.url)), listOf(a), a))
    }

    @Test fun noDefaultAndNoSelectionMeansNoDownload() {
        assertTrue(EpgSourceCatalog.resolve(IptvSource(), emptyList(), EpgSource()).isEmpty())
    }

    @Test fun editingGuideUrlUpdatesBindingsAndPreservesSubscriptionParameters() {
        val source = IptvSource("TV", "https://example.test/tv.m3u#sp;vp-ua=MyPlayer", transformJs = "return data",
            epgUrls = listOf(a.url, b.url))
        val updated = EpgSourceCatalog.updateBinding(source, a.url, "https://example.test/new.xml")
        assertEquals(listOf("https://example.test/new.xml", b.url), updated.epgUrls)
        assertEquals(source.url, updated.url)
        assertEquals(source.transformJs, updated.transformJs)
    }

    @Test fun deletingOneGuideRetainsOtherBindingsAndDeletingLastUsesDefault() {
        val source = IptvSource(epgUrls = listOf(a.url, b.url))
        val updated = EpgSourceCatalog.updateBinding(source, a.url, null)
        assertEquals(listOf(b.url), updated.epgUrls)
        val cleared = EpgSourceCatalog.updateBinding(updated, b.url, null)
        assertEquals(listOf(a), EpgSourceCatalog.resolve(cleared, listOf(a), a))
    }

    @Test fun renamingTheDefaultUsesCanonicalMetadataAndDeletingItChoosesRemaining() {
        val renamed = a.copy(name = "New name")
        assertEquals(renamed, EpgSourceCatalog.defaultSource(listOf(renamed, b), a))
        assertEquals(b, EpgSourceCatalog.defaultSource(listOf(b), a))
        assertEquals(EpgSource(), EpgSourceCatalog.defaultSource(emptyList(), a))
    }

    @Test fun manualEntryTrimsAndPreservesQueryCredentials() {
        assertEquals(EpgSource("Name", "https://example.test/epg?key=a+b=="),
            EpgSourceCatalog.prepare(" Name ", " https://example.test/epg?key=a+b== ", emptyList()))
    }

    @Test fun rejectsDuplicateNameOrUrlAndInvalidEntries() {
        for ((name, url) in listOf("A" to b.url, "Other" to a.url, " " to b.url,
            "Other" to "file:///epg.xml", "Other" to "https://", "Other" to "${b.url}\n${a.url}")) {
            assertThrows(IllegalArgumentException::class.java) { EpgSourceCatalog.prepare(name, url, listOf(a)) }
        }
    }
}
