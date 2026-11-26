package top.yogiczy.mytv.tv.player

import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.Closeable
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import top.yogiczy.mytv.core.data.entities.channel.ChannelFavoriteList
import top.yogiczy.mytv.core.data.entities.channel.ChannelGroupList.Companion.channelList
import top.yogiczy.mytv.core.data.entities.epg.EpgList.Companion.match
import top.yogiczy.mytv.core.data.entities.epgsource.EpgSource
import top.yogiczy.mytv.core.data.entities.epgsource.EpgSourceList
import top.yogiczy.mytv.core.data.entities.iptvsource.IptvSource
import top.yogiczy.mytv.core.data.entities.iptvsource.IptvSourceList
import top.yogiczy.mytv.core.data.repositories.epg.EpgCacheStore
import top.yogiczy.mytv.core.data.repositories.epg.EpgParser
import top.yogiczy.mytv.core.data.repositories.epg.EpgRepository
import top.yogiczy.mytv.core.data.repositories.iptv.IptvRepository
import top.yogiczy.mytv.core.data.utils.Globals
import top.yogiczy.mytv.core.data.utils.SP
import top.yogiczy.mytv.tv.ui.screen.main.MainUiState
import top.yogiczy.mytv.tv.ui.screen.main.MainViewModel
import top.yogiczy.mytv.tv.ui.utils.Configs

@RunWith(AndroidJUnit4::class)
class EpgStartupCacheTest {
    @Test fun expiredGuideAppearsBeforeDownloadCompletesAndIsReplacedOnSuccess() = runBlocking {
        GuideServer(xml("New guide")).use { server ->
            startup(server) { model, cache, jobs ->
                withTimeout(10_000) { server.requested.await() }
                assertEquals("Old guide", model.uiState.value.title())
                val oldModified = cache.lastModified()

                server.respond()
                withTimeout(15_000) { jobs.joinAll() }

                assertEquals("New guide", model.uiState.value.title())
                assertTrue(cache.lastModified() > oldModified)
                assertEquals("New guide", EpgCacheStore(cache).getOrRefresh {
                    error("Expected the new cache")
                }.single().programmeList.single().title)
                assertEquals(1, server.requests.get())
            }
        }
    }

    @Test fun httpFailureRetainsExpiredGuide() = runBlocking {
        verifyFailedRefresh(503, "Unavailable")
    }

    @Test fun emptyDownloadedGuideRetainsExpiredGuide() = runBlocking {
        verifyFailedRefresh(200, """<tv><channel id="test"><display-name>Test channel</display-name></channel></tv>""")
    }

    @Test fun freshGuideDoesNotTriggerDownload() = runBlocking {
        GuideServer(xml("Unexpected guide")).use { server ->
            startup(server, expired = false) { model, _, jobs ->
                withTimeout(10_000) { jobs.joinAll() }
                assertEquals("Old guide", model.uiState.value.title())
                assertEquals(0, server.requests.get())
            }
        }
    }

    @Test fun firstLoadWaitsForDownloadWhenThereIsNoCache() = runBlocking {
        GuideServer(xml("New guide")).use { server ->
            startup(server, seedCache = false) { model, cache, jobs ->
                withTimeout(10_000) { server.requested.await() }
                assertNull(model.uiState.value.title())
                server.respond()
                withTimeout(15_000) { jobs.joinAll() }
                assertEquals("New guide", model.uiState.value.title())
                assertTrue(cache.isFile)
            }
        }
    }

    @Test fun lowerPriorityCacheAppearsWhilePrimarySourceDownloads() = runBlocking {
        GuideServer(xml("Primary guide")).use { primary ->
            GuideServer(xml("Unexpected download")).use { fallback ->
                startup(primary, seedCache = false, fallback = fallback) { model, _, jobs ->
                    withTimeout(10_000) { primary.requested.await() }
                    assertEquals("Fallback guide", model.uiState.value.title())
                    primary.respond()
                    withTimeout(15_000) { jobs.joinAll() }
                    assertEquals("Primary guide", model.uiState.value.title())
                    assertEquals(0, fallback.requests.get())
                }
            }
        }
    }

    private suspend fun verifyFailedRefresh(status: Int, body: String) {
        GuideServer(body, status).use { server ->
            startup(server) { model, cache, jobs ->
                withTimeout(10_000) { server.requested.await() }
                assertEquals("Old guide", model.uiState.value.title())
                val oldModified = cache.lastModified()
                val oldBytes = cache.readBytes()

                server.respond()
                withTimeout(15_000) { jobs.joinAll() }

                assertEquals("Old guide", model.uiState.value.title())
                assertEquals(oldModified, cache.lastModified())
                assertArrayEquals(oldBytes, cache.readBytes())
                assertEquals(1, server.requests.get())
            }
        }
    }

    private suspend fun startup(
        server: GuideServer,
        expired: Boolean = true,
        seedCache: Boolean = true,
        fallback: GuideServer? = null,
        verify: suspend (MainViewModel, File, List<Job>) -> Unit,
    ) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val suffix = UUID.randomUUID().toString()
        val source = EpgSource("EPG startup $suffix", server.url)
        val fallbackSource = fallback?.let { EpgSource("EPG fallback $suffix", it.url) }
        val sources = listOfNotNull(source, fallbackSource)
        val repositories = sources.map(::EpgRepository)
        val cache = File(Globals.cacheDir, source.cacheFileName("v3.json"))
        val playlist = File.createTempFile("epg-startup-", ".m3u", Globals.fileDir).apply {
            writeText("#EXTM3U\n#EXTINF:-1 tvg-id=\"test\",Test channel\nhttps://example.test/live.m3u8\n")
        }
        val subscription = IptvSource("EPG startup $suffix", playlist.path, true, epgUrls = sources.map { it.url })
        var model: MainViewModel? = null
        try {
            if (seedCache) {
                EpgCacheStore(cache).getOrRefresh { EpgParser.fromXml(xml("Old guide").byteInputStream()) }
                if (expired) assertTrue(cache.setLastModified(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(2)))
            }
            if (fallbackSource != null) {
                val fallbackCache = File(Globals.cacheDir, fallbackSource.cacheFileName("v3.json"))
                EpgCacheStore(fallbackCache).getOrRefresh { EpgParser.fromXml(xml("Fallback guide").byteInputStream()) }
            }
            val (viewModel, jobs) = withContext(Dispatchers.Main) {
                SP.init(instrumentation.context)
                Configs.cloudSyncAutoPull = false
                Configs.iptvChannelFavoriteList = ChannelFavoriteList()
                Configs.iptvSimilarChannelMerge = false
                Configs.iptvSourceList = IptvSourceList(listOf(subscription))
                Configs.iptvSourceCurrent = subscription
                Configs.epgEnable = true
                Configs.epgSourceList = EpgSourceList(sources)
                Configs.epgSourceCurrent = source
                Configs.epgRefreshTimeThreshold = 0
                val vm = MainViewModel().also { model = it }
                vm to vm.viewModelScope.coroutineContext[Job]!!.children.toList()
            }
            withTimeout(10_000) { viewModel.uiState.first { it is MainUiState.Ready } }
            verify(viewModel, cache, jobs)
        } finally {
            server.respond()
            val scope = model?.viewModelScope
            withContext(Dispatchers.Main) { scope?.cancel() }
            scope?.coroutineContext?.get(Job)?.join()
            withContext(Dispatchers.Main) { SP.init(instrumentation.targetContext) }
            repositories.forEach { it.clearCache() }
            IptvRepository(subscription).clearCache()
            playlist.delete()
        }
    }

    private fun MainUiState.title(): String? {
        val ready = this as? MainUiState.Ready ?: return null
        val channel = ready.channelGroupList.channelList.singleOrNull() ?: return null
        return ready.epgList.match(channel)?.programmeList?.singleOrNull()?.title
    }

    private fun xml(title: String) = """
        <tv>
          <channel id="test"><display-name>Test channel</display-name></channel>
          <programme channel="test" start="20261126090000 +0800" stop="20261126100000 +0800">
            <title>$title</title>
          </programme>
        </tv>
    """.trimIndent()

    private class GuideServer(body: String, status: Int = 200) : Closeable {
        private val socket = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        private val gate = CountDownLatch(1)
        val url = "http://127.0.0.1:${socket.localPort}/guide.xml"
        val requests = AtomicInteger()
        val requested = CompletableDeferred<Unit>()
        private val worker = thread(isDaemon = true, name = "EpgStartupCacheTest") {
            while (!socket.isClosed) {
                try {
                    socket.accept().use { client ->
                        val input = client.getInputStream().bufferedReader()
                        while (!input.readLine().isNullOrEmpty()) { }
                        requests.incrementAndGet()
                        requested.complete(Unit)
                        if (!gate.await(10, TimeUnit.SECONDS)) return@use
                        val bytes = body.toByteArray()
                        client.getOutputStream().apply {
                            write("HTTP/1.1 $status Test\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            write(bytes)
                            flush()
                        }
                    }
                } catch (_: java.io.IOException) { }
            }
        }

        fun respond() = gate.countDown()

        override fun close() {
            respond()
            socket.close()
            worker.join(2_000)
        }
    }
}
