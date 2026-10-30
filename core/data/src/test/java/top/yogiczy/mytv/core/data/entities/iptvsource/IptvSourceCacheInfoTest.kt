package top.yogiczy.mytv.core.data.entities.iptvsource

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class IptvSourceCacheInfoTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun readsCountsAndActualCacheSizeWithoutChangingTimestamp() {
        val parsed = folder.newFile("parsed.json")
        parsed.writeText("""{"value":[{"name":"A","channelList":{"value":[{"name":"1"},{"name":"2"}]}},{"name":"B","channelList":{"value":[{"name":"3"}]}}]}""")
        val raw = folder.newFile("raw.txt").apply { writeText("playlist") }
        assertTrue(parsed.setLastModified(1_700_000_000_000L))
        val time = parsed.lastModified()
        val info = IptvSourceCacheInfo.read(parsed, raw)!!
        assertEquals(2, info.groupCount)
        assertEquals(3, info.channelCount)
        assertEquals(parsed.length() + raw.length(), info.cacheBytes)
        assertEquals(time, info.updatedAt)
        assertEquals(time, parsed.lastModified())
    }

    @Test fun missingOrInvalidParsedCacheDoesNotCreateOrOverwriteFiles() {
        val missing = java.io.File(folder.root, "missing.json")
        assertNull(IptvSourceCacheInfo.read(missing))
        assertFalse(missing.exists())
        val invalid = folder.newFile("invalid.json").apply { writeText("broken") }
        assertNull(IptvSourceCacheInfo.read(invalid))
        assertEquals("broken", invalid.readText())
    }

    @Test fun successfullyCachedEmptyPlaylistShowsZeroCounts() {
        val parsed = folder.newFile("empty.json").apply { writeText("""{"value":[]}""") }
        val info = IptvSourceCacheInfo.read(parsed)!!
        assertEquals(0, info.groupCount)
        assertEquals(0, info.channelCount)
        assertEquals(parsed.length(), info.cacheBytes)
    }
}
