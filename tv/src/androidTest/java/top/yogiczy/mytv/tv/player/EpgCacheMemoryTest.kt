package top.yogiczy.mytv.tv.player

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import top.yogiczy.mytv.core.data.entities.epg.Epg
import top.yogiczy.mytv.core.data.entities.epg.EpgList
import top.yogiczy.mytv.core.data.entities.epg.EpgProgramme
import top.yogiczy.mytv.core.data.entities.epg.EpgProgrammeList
import top.yogiczy.mytv.core.data.repositories.epg.EpgCacheStore
import top.yogiczy.mytv.core.data.repositories.epg.EpgParser
import top.yogiczy.mytv.core.data.repositories.epg.EpgUpdateProgress

@RunWith(AndroidJUnit4::class)
class EpgCacheMemoryTest {
    private val guide = EpgList(listOf(Epg(channelId = "test", channelList = listOf("Test channel"),
        programmeList = EpgProgrammeList(listOf(EpgProgramme(1, 2, "标题", "简介 & \"Unicode\" 中文"))))))

    private suspend fun <T> withCache(block: suspend (File, EpgCacheStore) -> T): T {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "epg-memory-test-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val file = File(root, "guide.json")
            return block(file, EpgCacheStore(file))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun refreshReturnsOriginalGuideAndCachePreservesDescriptions() = runBlocking {
        withCache { _, cache ->
            assertSame(guide, cache.getOrRefresh { guide })
            assertEquals(guide, cache.getOrRefresh { error("Must use the cache") })
        }
    }

    @Test fun forcedRefreshSkipsOldCacheBeforeReading() = runBlocking {
        withCache { file, cache ->
            file.writeText("invalid old JSON")
            val stages = mutableListOf<EpgUpdateProgress.Stage>()
            assertSame(guide, cache.getOrRefresh(forceRefresh = true,
                onProgress = { stages += it.stage }) { guide })
            assertFalse(stages.contains(EpgUpdateProgress.Stage.LOADING))
            assertEquals(guide, cache.getOrRefresh { error("Must use the refreshed cache") })
        }
    }

    @Test fun failedOrCancelledRefreshPreservesPreviousCache() = runBlocking {
        withCache { file, cache ->
            cache.getOrRefresh { guide }
            val bytes = file.readBytes()
            val modified = file.lastModified()
            try {
                cache.getOrRefresh(forceRefresh = true) { throw IOException("download failed") }
                fail("Expected download failure")
            } catch (_: IOException) { }
            try {
                cache.getOrRefresh(forceRefresh = true,
                    onProgress = { if (it.stage == EpgUpdateProgress.Stage.SAVING) throw CancellationException("cancel") }
                ) { guide }
                fail("Expected cancellation")
            } catch (_: CancellationException) { }
            assertArrayEquals(bytes, file.readBytes())
            assertEquals(modified, file.lastModified())
        }
    }

    @Test fun corruptCacheCanBeReplacedAndInstancesSerializeRefresh() = runBlocking {
        withCache { file, cache ->
            file.writeText("truncated {")
            var refreshes = 0
            val first = async { cache.getOrRefresh { refreshes++; delay(50); guide } }
            val second = async { EpgCacheStore(file).getOrRefresh { refreshes++; guide } }
            assertEquals(guide, first.await())
            assertEquals(guide, second.await())
            assertEquals(1, refreshes)
            cache.clear()
            assertFalse(file.exists())
        }
    }

    /** Explicit opt-in: read the user's large cache, write only a temporary copy. */
    @Test fun actualLargeXmlRefreshWritesCacheWithoutStringRoundTrip() = runBlocking {
        val relative = InstrumentationRegistry.getArguments().getString("epgXmlPath")
        assumeTrue("Pass epgXmlPath to verify XML refresh", relative != null)
        val cacheDir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir.canonicalFile
        val source = File(cacheDir, relative!!).canonicalFile
        require(source.path.startsWith(cacheDir.path + File.separator) && source.isFile)
        val modified = source.lastModified()
        val bytes = source.length()
        withCache { _, cache ->
            var parsed: EpgList? = null
            val fresh = cache.getOrRefresh(forceRefresh = true) {
                EpgParser.fromXml(source.inputStream().buffered()).also { parsed = it }
            }
            assertSame(parsed, fresh)
            assertTrue(fresh.sumOf { it.programmeList.size } > 0)
            assertTrue(fresh.any { epg -> epg.programmeList.any { !it.description.isNullOrEmpty() } })
            assertEquals(fresh, cache.getOrRefresh { error("Must use the written cache") })
            assertEquals(modified, source.lastModified())
            assertEquals(bytes, source.length())
            Log.i("EpgCacheMemoryTest", "xmlBytes=$bytes channels=${fresh.size} " +
                "programmes=${fresh.sumOf { it.programmeList.size }} refreshRoundTrip=passed")
        }
        Unit
    }

    /** Explicit opt-in: read the user's large cache, write only a temporary copy. */
    @Test fun actualLargeCacheLoadsAndRoundTripsWithinDeviceHeap() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val relative = args.getString("epgCachePath")
        assumeTrue("Pass epgCachePath to verify a real large guide", relative != null)
        val cacheDir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir.canonicalFile
        val source = File(cacheDir, relative!!).canonicalFile
        require(source.path.startsWith(cacheDir.path + File.separator) && source.isFile)
        val originalModified = source.lastModified()
        val originalBytes = source.length()
        val runtime = Runtime.getRuntime()
        val running = AtomicBoolean(true)
        val peak = AtomicLong(runtime.totalMemory() - runtime.freeMemory())
        val monitor = thread(isDaemon = true, name = "EPG-memory-monitor") {
            while (running.get()) {
                val used = runtime.totalMemory() - runtime.freeMemory()
                peak.updateAndGet { previous -> maxOf(previous, used) }
                Thread.sleep(5)
            }
        }
        try {
            // Keep headroom occupied to exercise loading alongside app state.
            val retained = ByteArray(16 * 1024 * 1024)
            val loaded = EpgCacheStore(source).getOrRefresh { error("Real cache must not be refreshed") }
            val count = loaded.sumOf { it.programmeList.size }
            val descriptions = loaded.sumOf { epg -> epg.programmeList.count { !it.description.isNullOrEmpty() } }
            assertTrue(count > 0)
            assertTrue(descriptions > 0)
            withCache { _, copy ->
                assertSame(loaded, copy.getOrRefresh { loaded })
                val reloaded = copy.getOrRefresh { error("Temporary copy must be cached") }
                assertEquals(loaded, reloaded)
            }
            assertEquals(16 * 1024 * 1024, retained.size)
            assertEquals(originalModified, source.lastModified())
            assertEquals(originalBytes, source.length())
            Log.i("EpgCacheMemoryTest", "cacheBytes=$originalBytes channels=${loaded.size} programmes=$count " +
                "descriptions=$descriptions peakJavaBytes=${peak.get()} maxJavaBytes=${runtime.maxMemory()}")
        } finally {
            running.set(false)
            monitor.join(1_000)
        }
        Unit
    }
}
