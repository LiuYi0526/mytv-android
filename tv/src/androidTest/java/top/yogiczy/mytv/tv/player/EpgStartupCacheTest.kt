package top.yogiczy.mytv.tv.player

import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.Deflater
import java.util.zip.GZIPOutputStream
import kotlin.concurrent.thread
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import top.yogiczy.mytv.core.data.entities.channel.ChannelFavoriteList
import top.yogiczy.mytv.core.data.entities.channel.ChannelGroupList.Companion.channelList
import top.yogiczy.mytv.core.data.entities.epg.EpgList
import top.yogiczy.mytv.core.data.entities.epg.EpgList.Companion.match
import top.yogiczy.mytv.core.data.entities.epgsource.EpgSource
import top.yogiczy.mytv.core.data.entities.epgsource.EpgSourceList
import top.yogiczy.mytv.core.data.entities.iptvsource.IptvSource
import top.yogiczy.mytv.core.data.entities.iptvsource.IptvSourceList
import top.yogiczy.mytv.core.data.repositories.epg.EpgCacheStore
import top.yogiczy.mytv.core.data.repositories.epg.EpgParser
import top.yogiczy.mytv.core.data.repositories.epg.EpgRepository
import top.yogiczy.mytv.core.data.repositories.epg.EpgUpdateProgress
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

    @Test fun freshEtagGuideChecksAtStartupAndSurvivesChannelReload() = runBlocking {
        GuideServer("").use { server ->
            startup(server, expired = false, prepareCache = { repository ->
                server.setReply(xml("Old guide").toByteArray(), headers = mapOf("ETag" to "\"v1\""))
                server.respond()
                repository.getEpgList()
                server.holdResponse()
                server.setReply(xml("New guide").toByteArray(), headers = mapOf("ETag" to "\"v2\""))
            }) { model, _, _ ->
                withTimeout(10_000) { while (server.requests.get() < 2) delay(10) }
                val previous = model.uiState.value as MainUiState.Ready
                assertEquals("Old guide", previous.title())
                assertEquals("\"v1\"", server.lastRequest["if-none-match"])

                withContext(Dispatchers.Main) { model.init() }
                withTimeout(10_000) {
                    model.uiState.first { it is MainUiState.Ready && it.channelGroupList !== previous.channelGroupList }
                }
                server.respond()
                withTimeout(10_000) { model.uiState.first { it.title() == "New guide" } }
                assertEquals(2, server.requests.get())
            }
        }
    }

    @Test fun freshLastModifiedGuideChecksAtStartupAndKeepsCacheOn304() = runBlocking {
        GuideServer("").use { server ->
            startup(server, expired = false, prepareCache = { repository ->
                server.setReply(xml("Old guide").toByteArray(), headers = mapOf("Last-Modified" to lastModified))
                server.respond()
                repository.getEpgList()
                server.setReply(status = 304)
            }) { model, _, jobs ->
                withTimeout(10_000) { jobs.joinAll() }
                assertEquals("Old guide", model.uiState.value.title())
                assertEquals(2, server.requests.get())
                assertEquals(lastModified, server.lastRequest["if-modified-since"])
            }
        }
    }

    @Test fun failedStartupCheckKeepsOldGuideAndDoesNotRepeatOnReload() = runBlocking {
        GuideServer("").use { server ->
            startup(server, prepareCache = { repository ->
                server.setReply(xml("Old guide").toByteArray(), headers = mapOf("ETag" to "\"v1\""))
                server.respond()
                repository.getEpgList()
                server.setReply("Unavailable".toByteArray(), status = 503)
            }) { model, _, jobs ->
                withTimeout(10_000) { jobs.joinAll() }
                assertEquals("Old guide", model.uiState.value.title())
                val reload = withContext(Dispatchers.Main) {
                    model.init()
                    model.viewModelScope.coroutineContext[Job]!!.children.toList()
                }
                withTimeout(10_000) { reload.joinAll() }
                assertEquals("Old guide", model.uiState.value.title())
                assertEquals(2, server.requests.get())
            }
        }
    }

    @Test fun freshGzipMtimeGuideWaitsForItsScheduledRefresh() = runBlocking {
        GuideServer("").use { server ->
            startup(server, expired = false, prepareCache = { repository ->
                server.setReply(gzip("Old guide", 10))
                server.respond()
                repository.getEpgList()
                server.setReply(gzip("New guide", 11))
            }) { model, _, jobs ->
                withTimeout(10_000) { jobs.joinAll() }
                assertEquals("Old guide", model.uiState.value.title())
                assertEquals(1, server.requests.get())
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

    @Test fun etagTakesPriorityAnd304ReusesTheDisplayedGuide() = runBlocking {
        withValidatedGuide { server, repository, cache, raw ->
            server.setReply(xml("Old guide").toByteArray(), headers = mapOf("ETag" to "\"v1\"", "Last-Modified" to lastModified))
            repository.getEpgList()
            expireValidation(cache)
            val cached = requireNotNull(repository.getCachedGuide(0) { it })
            assertTrue(cached.expired)
            val updatedAt = cache.lastModified()
            val rawUpdatedAt = raw.lastModified()

            server.setReply(status = 304)
            val stages = mutableListOf<EpgUpdateProgress.Stage>()
            val guide = EpgRepository(server.source).getEpgList(
                displayed = cached,
                onProgress = { stages += it.stage },
                select = { error("The displayed guide should be reused") },
            )

            assertSame(cached.guide, guide)
            assertEquals("\"v1\"", server.lastRequest["if-none-match"])
            assertNull(server.lastRequest["if-modified-since"])
            assertEquals(updatedAt, cache.lastModified())
            assertEquals(rawUpdatedAt, raw.lastModified())
            assertFalse(EpgUpdateProgress.Stage.PARSING in stages)
            assertFalse(requireNotNull(repository.getCachedGuide(0) { it }).expired)
            assertEquals("Old guide", repository.getEpgList().title())
            assertEquals(2, server.requests.get())
        }
    }

    @Test fun changedResponseIsParsedOnceAndFailedParseKeepsPreviousValidators() = runBlocking {
        withValidatedGuide { server, repository, cache, raw ->
            server.setReply(xml("Old guide").toByteArray(), headers = mapOf("ETag" to "\"v1\""))
            repository.getEpgList()
            expireValidation(cache)
            val oldGuide = cache.readBytes()
            val oldXml = raw.readBytes()

            server.setReply("<tv>".toByteArray(), headers = mapOf("ETag" to "\"broken\""))
            assertNotNull(runCatching { repository.getEpgList() }.exceptionOrNull())
            assertArrayEquals(oldGuide, cache.readBytes())
            assertArrayEquals(oldXml, raw.readBytes())
            assertEquals("\"v1\"", server.lastRequest["if-none-match"])

            server.setReply(xml("New guide").toByteArray(), headers = mapOf("ETag" to "\"v2\""))
            assertEquals("New guide", repository.getEpgList().title())
            assertEquals("\"v1\"", server.lastRequest["if-none-match"])
            assertEquals(3, server.requests.get())

            expireValidation(cache)
            server.setReply(status = 304)
            assertEquals("New guide", repository.getEpgList().title())
            assertEquals("\"v2\"", server.lastRequest["if-none-match"])
        }
    }

    @Test fun lastModifiedIsUsedWhenEtagIsMissing() = runBlocking {
        withValidatedGuide { server, repository, cache, _ ->
            server.setReply(gzip("Old guide", 10), headers = mapOf("Last-Modified" to lastModified))
            assertEquals("Old guide", repository.getEpgList().title())
            expireValidation(cache)
            server.setReply(status = 304)
            assertEquals("Old guide", repository.getEpgList().title())
            assertEquals(lastModified, server.lastRequest["if-modified-since"])
            assertNull(server.lastRequest["if-none-match"])
            expireValidation(cache)
            server.setReply(gzip("New guide", 10), headers = mapOf("Last-Modified" to lastModified))
            assertEquals("New guide", repository.getEpgList().title())
            assertEquals(3, server.requests.get())
        }
    }

    @Test fun gzipMtimeFallbackSkipsAnUnchangedBodyAndReadsChangedResponseOnce() = runBlocking {
        withValidatedGuide { server, repository, cache, _ ->
            server.setReply(gzip("Old guide", 10))
            repository.getEpgList()
            expireValidation(cache)
            val cached = requireNotNull(repository.getCachedGuide(0) { it })
            assertSame(cached.guide, repository.getEpgList(displayed = cached))
            assertNull(server.lastRequest["if-none-match"])
            assertNull(server.lastRequest["if-modified-since"])
            assertNull(server.lastRequest["range"])
            assertEquals(2, server.requests.get())

            expireValidation(cache)
            server.setReply(gzip("New guide", 11))
            assertEquals("New guide", repository.getEpgList().title())
            assertEquals(3, server.requests.get())
        }
    }

    @Test fun zeroGzipMtimeDownloadsAGuideWithoutUsingAValidator() = runBlocking {
        withValidatedGuide { server, repository, cache, _ ->
            server.setReply(gzip("Old guide", 0))
            repository.getEpgList()
            assertFalse(validationFile(cache).exists())
            assertTrue(cache.setLastModified(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(2)))
            server.setReply(gzip("New guide", 0))
            assertEquals("New guide", repository.getEpgList().title())
            assertEquals(2, server.requests.get())
        }
    }

    @Test fun gzipMtimeRechecksContentAfterSevenDays() = runBlocking {
        withValidatedGuide { server, repository, cache, _ ->
            server.setReply(gzip("Old guide", 10))
            repository.getEpgList()
            assertEquals(gzip("Old guide", 10).size, gzip("New guide", 10).size)
            expireValidation(cache, downloadedDaysAgo = 8)
            server.setReply(gzip("New guide", 10))
            assertEquals("New guide", repository.getEpgList().title())
            assertEquals(2, server.requests.get())
        }
    }

    @Test fun manualRefreshAndClearedCacheDoNotSendOldValidators() = runBlocking {
        withValidatedGuide { server, repository, cache, raw ->
            server.setReply(xml("Old guide").toByteArray(), headers = mapOf("ETag" to "\"v1\""))
            repository.getEpgList()
            server.setReply(xml("New guide").toByteArray(), headers = mapOf("ETag" to "\"v2\""))
            assertEquals("New guide", repository.getEpgList(forceRefresh = true).title())
            assertNull(server.lastRequest["if-none-match"])
            repository.clearCache()
            assertFalse(cache.exists())
            assertFalse(raw.exists())
            assertFalse(validationFile(cache).exists())
            assertEquals("New guide", repository.getEpgList().title())
            assertNull(server.lastRequest["if-none-match"])
            assertEquals(3, server.requests.get())
        }
    }

    private suspend fun withValidatedGuide(
        test: suspend (GuideServer, EpgRepository, File, File) -> Unit,
    ) {
        GuideServer("").use { server ->
            val source = server.source
            val repository = EpgRepository(source)
            val cache = File(Globals.cacheDir, source.cacheFileName("v3.json"))
            val raw = File(Globals.cacheDir, source.cacheFileName("xml"))
            server.respond()
            try {
                test(server, repository, cache, raw)
            } finally {
                repository.clearCache()
            }
        }
    }

    private val lastModified = "Wed, 25 Nov 2026 04:00:00 GMT"

    private fun EpgList.title() = single().programmeList.single().title

    private fun validationFile(cache: File) = File(cache.path + ".validation")

    private fun expireValidation(cache: File, downloadedDaysAgo: Long? = null) {
        val file = validationFile(cache)
        val data = Globals.json.parseToJsonElement(file.readText()).jsonObject
        if (downloadedDaysAgo != null) {
            assertTrue(cache.setLastModified(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(downloadedDaysAgo)))
        }
        file.writeText(buildJsonObject {
            data.forEach { (key, value) -> put(key, value) }
            put("checkedAt", JsonPrimitive(0L))
            if (downloadedDaysAgo != null) {
                put("stamp", buildJsonObject {
                    put("modified", JsonPrimitive(cache.lastModified()))
                    put("bytes", JsonPrimitive(cache.length()))
                })
            }
        }.toString())
    }

    private fun gzip(title: String, mtime: Int): ByteArray {
        val output = ByteArrayOutputStream()
        // Keep equal-length XML fixtures equal in size across device zlib versions.
        object : GZIPOutputStream(output) {
            init { def.setLevel(Deflater.NO_COMPRESSION) }
        }.use { it.write(xml(title).toByteArray()) }
        return output.toByteArray().apply {
            for (offset in 0..3) this[4 + offset] = (mtime ushr (8 * offset)).toByte()
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
        prepareCache: (suspend (EpgRepository) -> Unit)? = null,
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
                if (prepareCache != null) prepareCache(repositories.first())
                else EpgCacheStore(cache).getOrRefresh { EpgParser.fromXml(xml("Old guide").byteInputStream()) }
                if (expired) {
                    if (validationFile(cache).isFile) expireValidation(cache)
                    else assertTrue(cache.setLastModified(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(2)))
                }
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
        private data class Reply(val bytes: ByteArray, val status: Int, val headers: Map<String, String>)
        private val reply = AtomicReference(Reply(body.toByteArray(), status, emptyMap()))
        private val received = CopyOnWriteArrayList<Map<String, String>>()
        private val socket = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        private val gate = AtomicReference(CountDownLatch(1))
        val url = "http://127.0.0.1:${socket.localPort}/guide.xml"
        val source = EpgSource("EPG validation ${UUID.randomUUID()}", url)
        val requests = AtomicInteger()
        val requested = CompletableDeferred<Unit>()
        val lastRequest: Map<String, String> get() = received.last()
        private val worker = thread(isDaemon = true, name = "EpgStartupCacheTest") {
            while (!socket.isClosed) {
                try {
                    socket.accept().use { client ->
                        val input = client.getInputStream().bufferedReader()
                        input.readLine()
                        val headers = buildMap {
                            while (true) {
                                val header = input.readLine()?.takeIf { it.isNotEmpty() } ?: break
                                put(header.substringBefore(':').lowercase(), header.substringAfter(':').trim())
                            }
                        }
                        val responseGate = gate.get()
                        received.add(headers)
                        requests.incrementAndGet()
                        requested.complete(Unit)
                        if (!responseGate.await(10, TimeUnit.SECONDS)) return@use
                        val current = reply.get()
                        client.getOutputStream().apply {
                            write(buildString {
                                append("HTTP/1.1 ${current.status} Test\r\nContent-Length: ${current.bytes.size}\r\nConnection: close\r\n")
                                current.headers.forEach { (name, value) -> append("$name: $value\r\n") }
                                append("\r\n")
                            }.toByteArray())
                            write(current.bytes)
                            flush()
                        }
                    }
                } catch (_: java.io.IOException) { }
            }
        }

        fun setReply(body: ByteArray = byteArrayOf(), status: Int = 200, headers: Map<String, String> = emptyMap()) {
            reply.set(Reply(body, status, headers))
        }

        fun holdResponse() {
            gate.set(CountDownLatch(1))
        }

        fun respond() = gate.get().countDown()

        override fun close() {
            respond()
            socket.close()
            worker.join(2_000)
        }
    }
}
