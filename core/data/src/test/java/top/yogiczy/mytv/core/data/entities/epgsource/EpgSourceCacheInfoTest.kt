package top.yogiczy.mytv.core.data.entities.epgsource

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EpgSourceCacheInfoTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun countsChannelsNotAliasesAndPreservesUpdateTime() {
        val parsed = folder.newFile("guide.json").apply {
            writeText("""{"value":[{"channelList":["CCTV1","综合"],"programmeList":{"value":[{"title":"节目","content":"说明"}]}},{"channelList":["CCTV2"],"channelId":"2"}]}""")
        }
        val xml = folder.newFile("guide.xml").apply { writeText("<tv/>") }
        assertTrue(parsed.setLastModified(1_700_000_000_000L))
        val time = parsed.lastModified()
        val info = EpgSourceCacheInfo.read(parsed, xml)!!
        assertEquals(2, info.channelCount)
        assertEquals(parsed.length() + xml.length(), info.cacheBytes)
        assertEquals(time, info.updatedAt)
        assertEquals(time, parsed.lastModified())
    }

    @Test fun missingOrInvalidCacheIsNotModified() {
        val missing = java.io.File(folder.root, "missing.json")
        assertNull(EpgSourceCacheInfo.read(missing))
        assertFalse(missing.exists())
        val invalid = folder.newFile("invalid.json").apply { writeText("broken") }
        assertNull(EpgSourceCacheInfo.read(invalid))
        assertEquals("broken", invalid.readText())
    }

    @Test fun emptyGuideHasZeroChannels() {
        val parsed = folder.newFile("empty.json").apply { writeText("""{"value":[]}""") }
        val info = EpgSourceCacheInfo.read(parsed)!!
        assertEquals(0, info.channelCount)
        assertEquals(parsed.length(), info.cacheBytes)
    }
}
