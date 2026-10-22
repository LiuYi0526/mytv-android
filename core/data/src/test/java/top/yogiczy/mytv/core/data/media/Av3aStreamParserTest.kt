package top.yogiczy.mytv.core.data.media

import org.junit.Assert.*
import org.junit.Test

class Av3aStreamParserTest {
    private data class Sample(val header: Av3aHeader, val data: ByteArray, val timeUs: Long?)
    private fun parser(samples: MutableList<Sample>) = Av3aStreamParser { h, b, t -> samples += Sample(h, b, t) }

    private fun header(
        profile: Int = 0, rate: Int = 2, channel: Int = 8, bitrate: Int = 1,
        objects: Int = 2, bedType: Int = 1, objectBitrate: Int = 4, order: Int = 1,
    ): ByteArray {
        val bits = StringBuilder()
        fun put(value: Int, width: Int) { bits.append(value.toString(2).padStart(width, '0')) }
        put(0xfff, 12); put(2, 4); put(0, 1); put(0, 3)
        put(profile, 3); put(rate, 4); put(0, 8)
        when (profile) {
            0 -> put(channel, 7)
            1 -> {
                put(bedType, 2)
                if (bedType == 1) { put(channel, 7); put(bitrate, 4) }
                put(objects - 1, 7); put(objectBitrate, 4)
            }
            2 -> put(order - 1, 4)
        }
        put(1, 2)
        if (profile != 1) put(bitrate, 4)
        put(0, 8)
        return bits.toString().padEnd(72, '0').chunked(8).map { it.toInt(2).toByte() }.toByteArray()
    }

    private fun frame(bytes: ByteArray = header()): ByteArray = bytes.copyOf(Av3aHeader.parse(bytes)!!.frameSize)

    @Test fun parsesTenChannelAudioVivid() {
        assertEquals(Av3aHeader(48000, 10, 384000, 1024), Av3aHeader.parse(header()))
    }

    @Test fun mixedAudioAddsBedAndObjectBitrates() {
        assertEquals(Av3aHeader(48000, 12, 512000, 1366), Av3aHeader.parse(header(profile = 1)))
    }

    @Test fun parsesObjectOnlyAndHoa() {
        assertEquals(128000, Av3aHeader.parse(header(profile = 1, bedType = 0))!!.bitrate)
        assertEquals(9, Av3aHeader.parse(header(profile = 2, order = 2))!!.channelCount)
    }

    @Test fun rejectsTruncatedAndReservedFields() {
        assertNull(Av3aHeader.parse(ByteArray(8)))
        assertNull(Av3aHeader.parse(header(rate = 15)))
        assertNull(Av3aHeader.parse(header(channel = 127)))
        assertNull(Av3aHeader.parse(header(channel = 4)))
        assertNull(Av3aHeader.parse(header(bitrate = 15)))
        assertNull(Av3aHeader.parse(header(profile = 2, order = 4)))
        assertNull(Av3aHeader.parse(header().also { it[1] = 0xf1.toByte() }))
    }

    @Test fun everyFrameSplitPreservesBytesAndTimestamp() {
        val bytes = frame()
        for (split in 1 until bytes.size) {
            val samples = mutableListOf<Sample>()
            val parser = parser(samples)
            parser.packetStarted(1000000)
            parser.consume(bytes, 0, split)
            parser.packetStarted(null)
            parser.consume(bytes, split)
            assertEquals("split=$split", 1, samples.size)
            assertArrayEquals(bytes, samples.single().data)
            assertEquals(1000000L, samples.single().timeUs)
        }
    }

    @Test fun timestampsUseSamplesRatherThanCompressedBytes() {
        val samples = mutableListOf<Sample>()
        val parser = parser(samples)
        parser.packetStarted(0)
        repeat(4) { parser.consume(frame(header(bitrate = 0))) }
        assertEquals(listOf(0L, 21333L, 42666L, 64000L), samples.map { it.timeUs })
    }

    @Test fun pesTimestampDuringFrameAppliesToNextFrame() {
        val samples = mutableListOf<Sample>()
        val parser = parser(samples)
        val bytes = frame()
        parser.packetStarted(100)
        parser.consume(bytes, 0, 5)
        parser.packetStarted(900000)
        parser.consume(bytes, 5)
        parser.consume(bytes)
        assertEquals(listOf(100L, 900000L), samples.map { it.timeUs })
    }

    @Test fun resynchronizesAfterGarbageAndInvalidHeader() {
        val samples = mutableListOf<Sample>()
        val parser = parser(samples)
        parser.packetStarted(123)
        val bytes = byteArrayOf(1, 2, -1) + header(rate = 15) + frame()
        bytes.forEach { parser.consume(byteArrayOf(it)) }
        assertEquals(1, samples.size)
        assertEquals(123L, samples.single().timeUs)
    }

    @Test fun findsSyncInsideInvalidHeader() {
        val samples = mutableListOf<Sample>()
        val parser = parser(samples)
        parser.consume(byteArrayOf(-1, -14, -1) + frame())
        assertEquals(1, samples.size)
    }

    @Test fun seekDiscardsPartialFrameAndPreviousTimestamp() {
        val samples = mutableListOf<Sample>()
        val parser = parser(samples)
        parser.packetStarted(300)
        parser.consume(frame(), 0, 100)
        parser.seek()
        parser.consume(frame())
        assertNull(samples.single().timeUs)
        parser.packetStarted(700)
        parser.consume(frame())
        assertEquals(700L, samples.last().timeUs)
    }

    @Test fun acceptsFormatChanges() {
        val samples = mutableListOf<Sample>()
        val parser = parser(samples)
        parser.consume(frame() + frame(header(channel = 1)))
        assertEquals(listOf(10, 2), samples.map { it.header.channelCount })
    }

    @Test fun repeatedInvalidSyncDoesNotStallOrEmitSamples() {
        val samples = mutableListOf<Sample>()
        parser(samples).consume(ByteArray(100000) { 0xff.toByte() })
        assertTrue(samples.isEmpty())
    }
}
