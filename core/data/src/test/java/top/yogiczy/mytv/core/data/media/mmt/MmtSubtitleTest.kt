package top.yogiczy.mytv.core.data.media.mmt

import org.junit.Assert.*
import org.junit.Test

class MmtSubtitleTest {
    private fun b(vararg values: Int) = values.map { it.toByte() }.toByteArray()
    private fun short(n: Int) = b(n ushr 8,n)
    private fun word(n: Long) = b((n ushr 24).toInt(),(n ushr 16).toInt(),(n ushr 8).toInt(),n.toInt())
    private fun descriptor(tag: Int, content: ByteArray) = short(tag) + b(content.size) + content
    private fun tlv(pid: Int, type: Int, body: ByteArray): ByteArray {
        val packet = b(0,type) + short(pid) + word(0) + word(1) + body
        val compressed = b(0,16,97) + packet
        return b(127,3) + short(compressed.size) + compressed
    }
    @Test fun signalingAndMfuDeliverSubtitleOnMediaClock() {
        val timestamp = word(3_900_000_000L) + word(0)
        fun asset(id: Int, type: String, d: ByteArray): ByteArray =
            ByteArray(6) + type.toByteArray() + b(0,1,0) + short(id) + short(d.size) + d
        val audioDescriptor = descriptor(1,word(1) + timestamp)
        // descriptor_tag=0x8020, subtitle_tag=0x20, Japanese, ordinary TTML caption, NTP time base.
        val subtitleDescriptor = descriptor(0x8020,short(0x20) + b(0,0) + "jpn".toByteArray() +
            b(0,0x20,0) + word(3_900_000_002L) + word(0) + b(0))
        val tableBody = b(0,0,0,0,2) + asset(10,"mp4a",audioDescriptor) + asset(11,"stpp",subtitleDescriptor)
        val table = b(0x20,0) + short(tableBody.size) + tableBody
        val signal = b(0,0) + short(0x8000) + b(0) + short(table.size) + table
        val emitted = mutableListOf<Pair<MmtTrack,Long>>()
        val parser = MmtTlvParser(object : MmtTlvParser.Listener {
            override fun onTracks(context: Int, tracks: List<MmtTrack>) {
                assertTrue(tracks.single { it.id == 11 }.isSubtitle)
            }
            override fun onSample(context: Int, track: MmtTrack, data: ByteArray, timeUs: Long) {
                emitted += track to timeUs
                if (track.isSubtitle) {
                    val xml = org.xmlpull.v1.XmlPullParserFactory.newInstance().newPullParser()
                    xml.setInput(data.toString(Charsets.UTF_8).reader()); xml.nextTag()
                    assertEquals("tt",xml.name)
                }
            }
        })
        fun mfu(payload: ByteArray): ByteArray {
            val body = b(0x20,0) + word(1) + word(0) + payload
            return short(body.size) + body
        }
        val input = tlv(0,2,signal) + tlv(10,0,mfu(b(1,2))) + tlv(11,0,mfu(unit(0,0,"<tt/>")))
        // Exercise arbitrary network boundaries, not just whole packets.
        input.forEach { parser.consume(byteArrayOf(it)) }
        assertEquals(listOf(0L,2_000_000L),emitted.map { it.second })
        assertEquals("jpn",emitted.last().first.language)
    }

    private fun unit(index: Int, last: Int, text: String, large: Boolean = false): ByteArray {
        val b = text.toByteArray()
        val size = if (large) byteArrayOf(0,0,(b.size ushr 8).toByte(),b.size.toByte())
            else byteArrayOf((b.size ushr 8).toByte(),b.size.toByte())
        return byteArrayOf(0,0,index.toByte(),last.toByte(),if (large) 8 else 0) + size + b
    }
    @Test fun completeDocumentPreservesTimingAndStyles() {
        val text = "<tt><body><p begin=\"2s\" end=\"4s\">字幕</p></body></tt>"
        assertEquals(text, MmtSubtitle().consume(7,unit(0,0,text))!!.toString(Charsets.UTF_8))
    }
    @Test fun missingCanvasUsesDescriptorDimensionsWithoutChangingTime() {
        val xml = """<tt xmlns="http://www.w3.org/ns/ttml" xmlns:tts="http://www.w3.org/ns/ttml#styling"><body><p begin="00:27:44.667">字幕</p></body></tt>"""
        val result = MmtSubtitle.prepareTtml(xml.toByteArray(),1).toString(Charsets.UTF_8)
        val parser = org.xmlpull.v1.XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
        parser.setInput(result.reader())
        parser.nextTag()
        assertEquals("3840px 2160px",parser.getAttributeValue("http://www.w3.org/ns/ttml#styling","extent"))
        assertTrue(result.contains("begin=\"00:27:44.667\""))
        assertEquals(result,MmtSubtitle.prepareTtml(result.toByteArray(),0).toString(Charsets.UTF_8))
    }
    @Test fun combinesOrderedUnits() {
        val p = MmtSubtitle()
        assertNull(p.consume(7,unit(0,1,"<tt>")))
        assertEquals("<tt></tt>",p.consume(7,unit(1,1,"</tt>"))!!.toString(Charsets.UTF_8))
    }
    @Test fun missingOrDifferentMpuCannotFinishOldDocument() {
        val p = MmtSubtitle()
        p.consume(7,unit(0,2,"<tt>"))
        assertNull(p.consume(7,unit(2,2,"</tt>")))
        p.consume(8,unit(0,1,"<tt>"))
        assertNull(p.consume(9,unit(1,1,"</tt>")))
        assertNotNull(p.consume(10,unit(0,0,"<tt/>")))
    }
    @Test fun rejectsTruncatedAndNonXmlDataUnits() {
        val p = MmtSubtitle()
        assertNull(p.consume(0,unit(0,0,"<tt/>").dropLast(1).toByteArray()))
        val unsupported = unit(0,0,"bitmap").also { it[4] = 0x10 }
        assertNull(p.consume(0,unsupported))
        assertNotNull(p.consume(0,unit(0,0,"<tt/>",true)))
    }
}
