package top.yogiczy.mytv.core.data.repositories

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileCacheStreamTest {
    @get:Rule val temporary = TemporaryFolder()

    private class Cache(file: File) : FileCacheRepository(file.path, true) {
        suspend fun load(expired: (Long, InputStream?) -> Boolean, refresh: suspend () -> InputStream) =
            getOrRefreshInputStream(expired, refresh)
    }

    @Test fun cacheHitClosesInspectionStreamButLeavesReturnedStreamUsable() = runBlocking {
        val file = temporary.newFile().apply { writeText("cached XML") }
        var inspection: InputStream? = null
        val result = Cache(file).load({ _, stream -> inspection = stream; false }) {
            error("Must use cached XML")
        }
        result.use { assertEquals("cached XML", it.bufferedReader().readText()) }
        try {
            inspection!!.read()
            fail("Inspection stream must be closed")
        } catch (_: IOException) { }
    }

    @Test fun expiredCacheClosesBothInspectionAndDownloadedStreams() = runBlocking {
        val file = temporary.newFile().apply { writeText("old XML") }
        var inspection: InputStream? = null
        var downloadClosed = false
        val download = object : ByteArrayInputStream("fresh XML".toByteArray()) {
            override fun close() { downloadClosed = true; super.close() }
        }
        Cache(file).load({ _, stream -> inspection = stream; true }) { download }.use {
            assertEquals("fresh XML", it.bufferedReader().readText())
        }
        assertTrue(downloadClosed)
        try {
            inspection!!.read()
            fail("Inspection stream must be closed")
        } catch (_: IOException) { }
    }
}
