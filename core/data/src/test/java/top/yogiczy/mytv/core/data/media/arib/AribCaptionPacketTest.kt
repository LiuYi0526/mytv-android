package top.yogiczy.mytv.core.data.media.arib

import org.junit.Assert.*
import org.junit.Test

class AribCaptionPacketTest {
    @Test fun discoversCaptionAndSuperimposeLanguages() {
        for (type in 0x80..0x81) {
            val result = parse(group(type, 0x80, management()))!!
            assertEquals(type, result.type)
            assertEquals(listOf(AribCaptionPacket.Language(1, "jpn")), result.languages)
            assertNull(result.statementLanguageId)
        }
    }
    @Test fun handlesTwoLanguagesAndDisplayConditionByte() {
        val body = byteArrayOf(0x3f, 2, 0x1c, 0, 'j'.code.toByte(), 'p'.code.toByte(), 'n'.code.toByte(), 0x80.toByte(),
            0x3a, 'e'.code.toByte(), 'n'.code.toByte(), 'g'.code.toByte(), 0x80.toByte(), 0, 0, 0)
        assertEquals(listOf(AribCaptionPacket.Language(1, "jpn"), AribCaptionPacket.Language(2, "eng")), parse(group(0x80, 0, body))!!.languages)
    }
    @Test fun handlesOriginalTimeAndAdditionalPesHeader() {
        val body = management().let { byteArrayOf(0xbf.toByte()) + ByteArray(5) + it.copyOfRange(1, it.size) }
        val data = group(0x80, 0, body)
        val withHeader = data.copyOfRange(0, 2) + byteArrayOf(0xf2.toByte(), 0, 0) + data.copyOfRange(3, data.size)
        assertEquals("jpn", parse(withHeader)!!.languages!!.single().code)
    }
    @Test fun identifiesStatementsWithoutInventingLanguageManagement() {
        val result = parse(group(0x80, 0x88, byteArrayOf(0x3f, 0, 0, 0)))!!
        assertEquals(2, result.statementLanguageId)
        assertNull(result.languages)
    }
    @Test fun ignoresUnrelatedMetadataAndUnsupportedPrivateStreams() {
        val good = group(0x80, 0, management())
        assertNull(AribCaptionPacket.parse("other.owner", good))
        assertNull(parse(good.copyOf().apply { this[0] = 0x82.toByte() }))
        assertNull(parse(good.copyOf().apply { this[1] = 0 }))
        assertNull(parse(good.copyOf().apply { this[2] = 0 }))
    }
    @Test fun rejectsTruncatedOrOversizedGroupsBeforeNativeDecode() {
        val good = group(0x80, 0, management())
        for (size in 0 until good.size) assertNull("prefix $size", parse(good.copyOf(size)))
        assertNull(parse(good.copyOf().apply { this[6] = 0xff.toByte(); this[7] = 0xff.toByte() }))
        assertNull(parse(ByteArray(AribCaptionPacket.MAX_PACKET_SIZE + 1)))
    }
    @Test fun rejectsTruncatedLanguageAndInvalidLinks() {
        assertNull(parse(group(0x80, 0, byteArrayOf(0x3f, 1, 0x1a, 'j'.code.toByte()))))
        assertNull(parse(group(0x80, 0, management()).apply { this[4] = 2; this[5] = 1 }))
        assertNull(parse(group(0x80, 0, management()).apply { this[9] = 0 }))
    }
    @Test fun returnedPacketOwnsItsData() {
        val original = group(0x80, 0, management())
        val packet = parse(original)!!
        original.fill(0)
        assertEquals(0x80, packet.bytes[0].toInt() and 255)
    }
    private fun parse(bytes: ByteArray) = AribCaptionPacket.parse(AribCaptionPacket.OWNER, bytes)
    private fun management() = byteArrayOf(0x3f, 1, 0x1a, 'j'.code.toByte(), 'p'.code.toByte(), 'n'.code.toByte(), 0x80.toByte(), 0, 0, 0)
    private fun group(type: Int, id: Int, body: ByteArray) =
        byteArrayOf(type.toByte(), 0xff.toByte(), 0xf0.toByte(), id.toByte(), 0, 0, (body.size ushr 8).toByte(), body.size.toByte()) + body + byteArrayOf(0, 0)
}
