package top.yogiczy.mytv.core.data.repositories.iptv

import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import top.yogiczy.mytv.core.data.entities.iptvsource.IptvSource
import top.yogiczy.mytv.core.data.entities.iptvsource.IptvSourceUrl
import top.yogiczy.mytv.core.data.network.request
import top.yogiczy.mytv.core.data.utils.Globals

class IptvRawRepositoryTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var server: MockWebServer
    private var previousCacheDir: File? = null
    private val day = 24 * 60 * 60 * 1000L
    private val playlist = "#EXTM3U x-tvg-url=\"https://example.com/epg.xml\"\n" +
        "#EXTINF:-1,Test channel\nhttps://example.com/live.m3u8\n"

    @Before fun setUp() {
        previousCacheDir = runCatching { Globals.cacheDir }.getOrNull()
        Globals.cacheDir = folder.root
        server = MockWebServer().apply { start() }
    }

    @After fun tearDown() {
        server.shutdown()
        previousCacheDir?.let { Globals.cacheDir = it }
    }

    private fun source(path: String = "/tv.m3u") = IptvSource("Draft", server.url(path).toString())
    private fun repository(source: IptvSource) = IptvRawRepository(source) {
        IptvSourceUrl.parse(source.url).requestUrl.request { it.string() } ?: ""
    }
    private fun response(body: String = playlist) { server.enqueue(MockResponse().setBody(body)) }

    @Test fun epgDiscoveryThenFirstChannelLoadDownloadsOnlyOnceEvenAfterRename() = runBlocking {
        response()
        val draft = source()
        assertEquals(playlist, repository(draft).getRaw(Long.MAX_VALUE))
        val saved = draft.copy(name = "Saved subscription", epgUrls = listOf("https://example.com/epg.xml"))
        assertEquals(playlist, repository(saved).getRaw(day))
        assertEquals(1, server.requestCount)
    }

    @Test fun playerParametersAndTransformsDoNotChangeTheRawDownload() = runBlocking {
        response()
        val original = source()
        repository(original).getRaw(day)
        val changed = original.copy(url = original.url + "#sp;vp-ua=Player/2", transformJs = "return channelList;")
        assertEquals(playlist, repository(changed).getRaw(day))
        assertEquals(1, server.requestCount)
    }

    @Test fun concurrentReadersOfTheSameUrlShareOneDownload() = runBlocking {
        repeat(4) { server.enqueue(MockResponse().setBody(playlist).setBodyDelay(100, TimeUnit.MILLISECONDS)) }
        val source = source()
        val results = coroutineScope {
            List(4) { index -> async { repository(source.copy(name = "Reader $index")).getRaw(day) } }.awaitAll()
        }
        assertEquals(List(4) { playlist }, results)
        assertEquals(1, server.requestCount)
    }

    @Test fun expiredRawDataDownloadsAgain() = runBlocking {
        response()
        response("updated")
        val repository = repository(source())
        repository.getRaw(day)
        assertTrue(repository.cachedFile.setLastModified(System.currentTimeMillis() - day - 1_000))
        assertEquals("updated", repository.getRaw(day))
        assertEquals(2, server.requestCount)
    }

    @Test fun manualRefreshDownloadsEvenWhenTheCacheIsFresh() = runBlocking {
        response()
        response("updated")
        val repository = repository(source())
        repository.getRaw(day)
        assertEquals("updated", repository.getRaw(0))
        assertEquals(2, server.requestCount)
    }

    @Test fun legacyCacheIsReusedWithoutResettingItsDownloadTime() = runBlocking {
        val source = source()
        val legacy = File(folder.root, source.cacheFileName("txt")).apply {
            parentFile!!.mkdirs()
            writeText(playlist)
            assertTrue(setLastModified(System.currentTimeMillis() - 60_000))
        }
        val downloadedAt = legacy.lastModified()
        val repository = repository(source)
        assertEquals(playlist, repository.getRaw(day))
        assertEquals(downloadedAt, repository.cachedFile.lastModified())
        assertNotEquals(legacy, repository.cachedFile)
        assertEquals(0, server.requestCount)
    }

    @Test fun differentRequestUrlsKeepSeparateCaches() = runBlocking {
        response("first")
        response("second")
        assertEquals("first", repository(source("/tv.m3u?token=first")).getRaw(day))
        assertEquals("second", repository(source("/tv.m3u?token=second")).getRaw(day))
        assertEquals(2, server.requestCount)
    }

    @Test fun clearingCacheAlsoRemovesLegacyData() = runBlocking {
        val source = source()
        File(folder.root, source.cacheFileName("txt")).apply {
            parentFile!!.mkdirs()
            writeText("legacy")
        }
        val repository = repository(source)
        assertEquals("legacy", repository.getRaw(day))
        repository.clearCache()
        response("updated")
        assertEquals("updated", repository.getRaw(day))
        assertEquals(1, server.requestCount)
    }

    @Test fun localPlaylistIsReadDirectlyAndNeverDeleted() = runBlocking {
        val local = folder.newFile("local.m3u").apply { writeText(playlist) }
        val repository = repository(IptvSource("Local", local.path, isLocal = true))
        assertEquals(playlist, repository.getRaw(0))
        repository.clearCache()
        assertEquals(playlist, local.readText())
        assertEquals(0, server.requestCount)
    }
}
