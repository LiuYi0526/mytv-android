package top.yogiczy.mytv.tv.player

import java.io.Closeable
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test
import top.yogiczy.mytv.core.data.entities.channel.Channel
import top.yogiczy.mytv.core.data.entities.channel.ChannelGroup
import top.yogiczy.mytv.core.data.entities.channel.ChannelGroupList
import top.yogiczy.mytv.core.data.entities.channel.ChannelGroupList.Companion.channelList
import top.yogiczy.mytv.core.data.entities.channel.ChannelList
import top.yogiczy.mytv.core.data.entities.channel.ChannelLine
import top.yogiczy.mytv.core.data.entities.channel.ChannelLineList
import top.yogiczy.mytv.core.data.entities.iptvsource.IptvSource
import top.yogiczy.mytv.core.data.repositories.iptv.IptvRepository
import top.yogiczy.mytv.core.data.utils.Globals

/** Real HTTP/cache integration; all sources and files belong only to this test. */
class IptvStartupCacheTest {
    private val day = 86_400_000L
    private fun groups(name: String) = ChannelGroupList(listOf(
        ChannelGroup(name = "Test", channelList = ChannelList(listOf(Channel(name = name)))),
    ))
    private fun playlist(name: String) = "#EXTM3U\n#EXTINF:-1 group-title=\"Test\",$name\nhttps://example.test/live.m3u8\n"

    private suspend fun scenario(block: suspend (PlaylistServer, IptvSource, IptvRepository, File) -> Unit) {
        PlaylistServer().use { server ->
            val source = IptvSource("Startup test ${UUID.randomUUID()}", server.url)
            val repository = IptvRepository(source)
            val parsed = File(Globals.cacheDir, source.cacheFileName("v4.json"))
            try { block(server, source, repository, parsed) }
            finally { repository.clearCache() }
        }
    }

    private fun cache(file: File, name: String, expired: Boolean = true) {
        file.parentFile!!.mkdirs()
        file.writeText(Globals.json.encodeToString(groups(name)))
        if (expired) assertTrue(file.setLastModified(System.currentTimeMillis() - day - 1_000))
    }

    @Test fun expiredChannelsAppearBeforeTheSlowRefreshFinishes() = runBlocking {
        scenario { server, _, repository, file ->
            cache(file, "Before")
            val gate = CountDownLatch(1)
            server.reply(playlist("After"), gate = gate)
            val first = CompletableDeferred<ChannelGroupList>()
            val refreshes = AtomicInteger()
            val load = async(Dispatchers.IO) {
                repository.observeChannelGroupList(day) {
                    assertTrue("Refresh began before cached channels were published", first.isCompleted)
                    refreshes.incrementAndGet()
                }.onEach {
                    if (!first.isCompleted) first.complete(it)
                }.toList()
            }
            try {
                assertEquals("Before", withTimeout(5_000) { first.await() }.channelList.single().name)
                assertTrue(server.requested.await(5, TimeUnit.SECONDS))
                assertFalse("Refresh blocked the initial emission", load.isCompleted)
                gate.countDown()
                val results = withTimeout(5_000) { load.await() }
                assertEquals(listOf("Before", "After"), results.map { it.channelList.single().name })
                assertEquals("After", repository.getCachedChannelGroupList()!!.channelList.single().name)
                assertEquals(1, server.requests.get())
                assertEquals(1, refreshes.get())
            } finally { gate.countDown(); load.cancelAndJoin() }
        }
    }

    @Test fun freshCacheDoesNotRequestOrEmitTwice() = runBlocking {
        scenario { server, _, repository, file ->
            cache(file, "Fresh", expired = false)
            assertEquals(listOf(groups("Fresh")), repository.observeChannelGroupList(day) {
                fail("Fresh cached data must not show a refresh message")
            }.toList())
            assertEquals(0, server.requests.get())
        }
    }

    @Test fun failedRefreshKeepsOldChannelsAndFileAndMakesOnlyOneRequest() = runBlocking {
        scenario { server, source, repository, file ->
            cache(file, "Keep")
            val bytes = file.readBytes()
            val modified = file.lastModified()
            server.reply("Unavailable", status = 503)
            val shown = mutableListOf<ChannelGroupList>()
            try {
                repository.observeChannelGroupList(day).collect { shown += it }
                fail("Refresh should report failure")
            } catch (_: top.yogiczy.mytv.core.data.network.HttpException) { }
            assertEquals(listOf(groups("Keep")), shown)
            assertArrayEquals(bytes, file.readBytes())
            assertEquals(modified, file.lastModified())
            assertEquals(groups("Keep"), IptvRepository(source).getCachedChannelGroupList())
            assertEquals(1, server.requests.get())
        }
    }

    @Test fun noCacheFailureDoesNotPretendThereAreUsableChannelsOrRetry() = runBlocking {
        scenario { server, _, repository, _ ->
            server.reply("Unavailable", status = 503)
            val shown = mutableListOf<ChannelGroupList>()
            try {
                repository.observeChannelGroupList(day).collect { shown += it }
                fail("An uncached failure must be reported")
            } catch (_: top.yogiczy.mytv.core.data.network.HttpException) { }
            assertTrue(shown.isEmpty())
            assertEquals(1, server.requests.get())
            assertNull(repository.getCachedChannelGroupList())
        }
    }

    @Test fun firstLoadDownloadsAndPublishesOnce() = runBlocking {
        scenario { server, _, repository, _ ->
            server.reply(playlist("First"))
            assertEquals(listOf("First"), repository.observeChannelGroupList(day).toList()
                .map { it.channelList.single().name })
            assertEquals(1, server.requests.get())
        }
    }

    @Test fun corruptFreshCacheIsRecoveredInsteadOfBeingShownOrBlockingRefresh() = runBlocking {
        scenario { server, _, repository, file ->
            file.writeText("{broken json")
            server.reply(playlist("Recovered"))
            assertEquals(listOf("Recovered"), repository.observeChannelGroupList(day).toList()
                .map { it.channelList.single().name })
            assertEquals(1, server.requests.get())
            assertEquals("Recovered", repository.getCachedChannelGroupList()!!.channelList.single().name)
        }
    }

    @Test fun forceRefreshStillDownloadsWhenTheCacheIsFresh() = runBlocking {
        scenario { server, _, repository, file ->
            cache(file, "Before", expired = false)
            server.reply(playlist("Manual"))
            assertEquals(listOf("Before", "Manual"), repository.observeChannelGroupList(0).toList()
                .map { it.channelList.single().name })
            assertEquals(1, server.requests.get())
        }
    }

    @Test fun aPrefetchedNewRawPlaylistReplacesTheParsedCacheWithoutAnotherDownload() = runBlocking {
        scenario { server, _, repository, file ->
            cache(file, "Before", expired = false)
            assertTrue(file.setLastModified(System.currentTimeMillis() - 3_000))
            server.reply(playlist("Prefetched"))
            repository.getEpgUrls(0)
            assertEquals(listOf("Before", "Prefetched"), repository.observeChannelGroupList(day).toList()
                .map { it.channelList.single().name })
            assertEquals(1, server.requests.get())
        }
    }

    @Test fun aDifferentSubscriptionCannotDisplayAnotherSourcesCachedChannels() = runBlocking {
        scenario { server, source, _, file ->
            cache(file, "Private cache")
            val other = IptvRepository(source.copy(url = source.url + "?other"))
            try {
                assertNull(other.getCachedChannelGroupList())
                assertEquals(0, server.requests.get())
            } finally { other.clearCache() }
        }
    }

    @Test fun cancellationLeavesOldCacheAndDoesNotLaunchAnotherRefresh() = runBlocking {
        scenario { server, _, repository, file ->
            cache(file, "Keep")
            val bytes = file.readBytes()
            val gate = CountDownLatch(1)
            server.reply(playlist("Cancelled"), gate = gate)
            val first = CompletableDeferred<Unit>()
            val load = async(Dispatchers.IO) {
                repository.observeChannelGroupList(day).collect { first.complete(Unit) }
            }
            try {
                withTimeout(5_000) { first.await() }
                assertTrue(server.requested.await(5, TimeUnit.SECONDS))
                load.cancelAndJoin()
                assertArrayEquals(bytes, file.readBytes())
                assertEquals(1, server.requests.get())
            } finally { gate.countDown(); load.cancelAndJoin() }
        }
    }

    @Test fun cachedChannelsKeepTheSubscriptionPlayerParameters() = runBlocking {
        scenario { server, source, _, _ ->
            val configured = source.copy(url = source.url + "#sp;vp-ua=Player/2")
            val repository = IptvRepository(configured)
            try {
                val file = File(Globals.cacheDir, configured.cacheFileName("v4.json"))
                val channels = groups("Cached").let { groups ->
                    // Seed the usual serialized model; source parameters are applied on read.
                    groups.copy(value = groups.map { group -> group.copy(channelList = ChannelList(
                        group.channelList.map { it.copy(lineList = ChannelLineList(
                            listOf(ChannelLine(url = "https://example.test/live")),
                        )) },
                    )) })
                }
                file.writeText(Globals.json.encodeToString(channels))
                val shown = repository.observeChannelGroupList(day).toList().single()
                assertEquals("Player/2", shown.channelList.single().lineList.single().httpUserAgent)
                assertEquals(0, server.requests.get())
            } finally { repository.clearCache() }
        }
    }

    private class PlaylistServer : Closeable {
        private data class Reply(val body: String, val status: Int, val gate: CountDownLatch?)
        private val socket = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        private val replies = LinkedBlockingQueue<Reply>()
        val url = "http://127.0.0.1:${socket.localPort}/test.m3u"
        val requests = AtomicInteger()
        val requested = CountDownLatch(1)
        private val worker = thread(isDaemon = true, name = "IptvStartupCacheTest") {
            while (!socket.isClosed) {
                try {
                    socket.accept().use { client ->
                        val reader = client.getInputStream().bufferedReader()
                        while (!reader.readLine().isNullOrEmpty()) { }
                        requests.incrementAndGet()
                        requested.countDown()
                        val reply = replies.poll(5, TimeUnit.SECONDS) ?: return@use
                        reply.gate?.await(5, TimeUnit.SECONDS)
                        val body = reply.body.toByteArray()
                        client.getOutputStream().apply {
                            write(("HTTP/1.1 ${reply.status} Test\r\nContent-Length: ${body.size}\r\n" +
                                "Connection: close\r\n\r\n").toByteArray())
                            write(body)
                            flush()
                        }
                    }
                } catch (_: java.io.IOException) { }
            }
        }
        fun reply(body: String, status: Int = 200, gate: CountDownLatch? = null) {
            replies.add(Reply(body, status, gate))
        }
        override fun close() { socket.close(); worker.join(6_000) }
    }
}
