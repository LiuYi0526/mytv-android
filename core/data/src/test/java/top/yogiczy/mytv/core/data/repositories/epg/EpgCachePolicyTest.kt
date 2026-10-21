package top.yogiczy.mytv.core.data.repositories.epg

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlinx.coroutines.runBlocking
import top.yogiczy.mytv.core.data.repositories.FileCacheRepository
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class EpgCachePolicyTest {
    @get:Rule
    val temporary = TemporaryFolder()
    private val zone = TimeZone.getTimeZone("Asia/Shanghai")
    private fun time(value: String): Long = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT).apply {
        timeZone = zone
    }.parse(value)!!.time

    private fun expired(cached: String, now: String, hour: Int = 2) =
        EpgCachePolicy.isExpired(time(cached), hour, time(now), zone)

    private inner class TestCache(file: File, private val now: Long) : FileCacheRepository(file.path, true) {
        var downloads = 0
        suspend fun load() = getOrRefresh({ modified, _ -> EpgCachePolicy.isExpired(modified, 2, now, zone) }) {
            downloads++
            "fresh guide"
        }
    }

    @Test
    fun midnightKeepsYesterdayCacheUntilRefreshHour() {
        assertFalse(expired("2026-10-21 09:05:00.000", "2026-10-21 23:59:59.999"))
        assertFalse(expired("2026-10-21 09:05:00.000", "2026-10-22 00:00:00.000"))
        assertFalse(expired("2026-10-21 09:05:00.000", "2026-10-22 01:59:59.999"))
    }

    @Test
    fun refreshHourExpiresYesterdayCacheExactlyOncePerCycle() {
        assertTrue(expired("2026-10-21 09:05:00.000", "2026-10-22 02:00:00.000"))
        assertFalse(expired("2026-10-22 02:00:00.000", "2026-10-22 02:00:00.000"))
        assertFalse(expired("2026-10-22 02:05:00.000", "2026-10-23 01:59:59.999"))
        assertTrue(expired("2026-10-22 02:05:00.000", "2026-10-23 02:00:00.000"))
    }

    @Test
    fun firstDownloadBeforeThresholdIsRefreshedAfterThreshold() {
        assertFalse(expired("2026-10-22 00:10:00.000", "2026-10-22 00:30:00.000"))
        assertTrue(expired("2026-10-22 00:10:00.000", "2026-10-22 02:00:00.000"))
    }

    @Test
    fun cacheOlderThanPreviousRefreshCycleIsStillExpired() {
        assertTrue(expired("2026-10-20 09:05:00.000", "2026-10-22 00:30:00.000"))
    }

    @Test
    fun zeroHourRetainsDailyMidnightRefresh() {
        assertFalse(expired("2026-10-21 09:05:00.000", "2026-10-21 23:59:59.999", 0))
        assertTrue(expired("2026-10-21 09:05:00.000", "2026-10-22 00:00:00.000", 0))
    }

    @Test
    fun boundaryUsesDeviceTimeZoneInsteadOfUtcDay() {
        val cached = time("2026-10-21 10:05:00.000")
        val now = time("2026-10-22 02:00:00.000")
        assertTrue(EpgCachePolicy.isExpired(cached, 2, now, zone))
        assertFalse(EpgCachePolicy.isExpired(cached, 2, now, TimeZone.getTimeZone("UTC")))
    }

    @Test
    fun loadingBeforeThresholdReadsYesterdayCacheWithoutDownloading() = runBlocking {
        val file = temporary.newFile("guide.json").apply {
            writeText("cached multi-day guide")
            assertTrue(setLastModified(time("2026-10-21 09:05:00.000")))
        }
        val cache = TestCache(file, time("2026-10-22 00:30:00.000"))
        assertEquals("cached multi-day guide", cache.load())
        assertEquals(0, cache.downloads)
    }

    @Test
    fun missingCacheStillDownloadsBeforeThreshold() = runBlocking {
        val cache = TestCache(File(temporary.root, "missing.json"), time("2026-10-22 00:30:00.000"))
        assertEquals("fresh guide", cache.load())
        assertEquals(1, cache.downloads)
    }

    @Test
    fun loadingAfterThresholdReplacesYesterdayCache() = runBlocking {
        val file = temporary.newFile("expired.json").apply {
            writeText("old guide")
            assertTrue(setLastModified(time("2026-10-21 09:05:00.000")))
        }
        val cache = TestCache(file, time("2026-10-22 02:00:00.000"))
        assertEquals("fresh guide", cache.load())
        assertEquals("fresh guide", file.readText())
        assertEquals(1, cache.downloads)
    }
}
