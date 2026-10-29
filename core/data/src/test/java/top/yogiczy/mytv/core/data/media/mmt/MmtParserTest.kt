package top.yogiczy.mytv.core.data.media.mmt

import org.junit.Assert.*
import org.junit.Test

class MmtParserTest {
    @Test fun nalConversionPreservesMultipleUnitsAndRejectsTruncation() {
        val data = byteArrayOf(0,0,0,2,64,1,0,0,0,3,66,1,2)
        assertArrayEquals(byteArrayOf(0,0,0,1,64,1,0,0,0,1,66,1,2), MmtTlvParser.annexB(data))
        assertNull(MmtTlvParser.annexB(data.copyOf(data.size - 1)))
        assertNull(MmtTlvParser.annexB(byteArrayOf(-1,-1,-1,-1)))
    }
    @Test fun sniffRequiresConsecutivePlausiblePackets() {
        val packet = byteArrayOf(127,3,0,3,0,16,97)
        assertTrue(MmtTlvParser.sniff(packet + packet))
        assertFalse(MmtTlvParser.sniff(packet))
        assertFalse(MmtTlvParser.sniff(ByteArray(4096) { 0x47 }))
    }
    @Test fun lostOrDuplicatePacketsDoNotJoinSamples() {
        val f = MmtFragments(16)
        assertTrue(f.acceptSequence(10))
        assertNull(f.append(1, byteArrayOf(1)))
        assertFalse(f.acceptSequence(10))
        assertTrue(f.acceptSequence(12))
        assertNull(f.append(3, byteArrayOf(3)))
        assertTrue(f.acceptSequence(13))
        assertNull(f.append(1, byteArrayOf(4)))
        assertTrue(f.acceptSequence(14))
        assertArrayEquals(byteArrayOf(4,5), f.append(3, byteArrayOf(5)))
    }
    @Test fun sequenceWrapAndBufferLimit() {
        val f = MmtFragments(4)
        assertTrue(f.acceptSequence(0xffffffffL))
        f.append(1, byteArrayOf(1,2))
        assertTrue(f.acceptSequence(0))
        assertArrayEquals(byteArrayOf(1,2,3), f.append(3, byteArrayOf(3)))
        f.append(1, byteArrayOf(1,2,3,4))
        assertNull(f.append(3, byteArrayOf(5)))
    }
    @Test fun timestampsUseDecodeOffsetAndCompositionOrder() {
        val t = MmtTrack(1, "hev1")
        t.timeScale = 1000
        t.timestamps[7] = 1_000_000L
        t.durations[7] = MmtTrack.Timing(20, intArrayOf(40,0), intArrayOf(20,20))
        assertEquals(1_020_000L, t.presentationTime(7,0))
        assertEquals(1_000_000L, t.presentationTime(7,1))
        assertNull(t.presentationTime(7,2))
    }
}
