package top.yogiczy.mytv.core.data.repositories.epg

import org.junit.Assert.*
import org.junit.Test
import org.kxml2.io.KXmlParser
import org.xmlpull.v1.XmlPullParserException
import java.io.ByteArrayInputStream
import java.util.Locale
import java.util.TimeZone

class XmltvParserTest {
    private fun parse(xml: String) = XmltvParser(KXmlParser()).parse(xml.byteInputStream())
    private fun programme(attributes: String = "", title: String = "News") =
        """<programme channel="c1" start="20250101000000 +0000" stop="20250101010000 +0000" $attributes><title>$title</title></programme>"""

    @Test
    fun readsTitleByTagAndKeepsChannelMetadataSeparate() {
        val guide = XmltvParser(KXmlParser()) { if (it == "CCTV-1 高清") "CCTV1" else it }.parse("""
            <tv>
              <channel id="opaque.id"><display-name> CCTV-1 高清 </display-name><display-name>CCTV一套</display-name><icon src="channel.png"/></channel>
              <programme channel="opaque.id" start="20250101000000 +0000" stop="20250101010000 +0000">
                <desc><title>Wrong title inside description</title></desc><icon src="programme.png"/>
                <title><![CDATA[News & Weather]]></title><title lang="en">Second title</title>
              </programme>
            </tv>
        """.trimIndent().byteInputStream())
        assertEquals("opaque.id", guide.single().channelId)
        assertEquals(listOf("opaque.id", "CCTV-1 高清", "CCTV1", "CCTV一套"), guide.single().channelList)
        assertEquals("channel.png", guide.single().logo)
        assertEquals("News & Weather", guide.single().programmeList.single().title)
    }

    @Test
    fun invalidEntriesDoNotConsumeTheNextProgramme() {
        val guide = parse("""
            <tv><channel/><channel id="c1"/>
              <programme channel="c1" start="bad" stop="20250101010000 +0000"><title>Bad date</title></programme>
              <programme channel="c1" start="20250230000000 +0000" stop="20250301010000 +0000"><title>Impossible date</title></programme>
              <programme start="20250101000000 +0000" stop="20250101010000 +0000"><title>No channel</title></programme>
              <programme channel="c1" start="20250101020000 +0000" stop="20250101010000 +0000"><title>Backwards</title></programme>
              <programme channel="c1" start="20250101000000 +0000" stop="20250101010000 +0000"><desc>No title</desc></programme>
              ${programme(title = "Valid")}
            </tv>
        """.trimIndent())
        assertEquals(1, guide.size)
        assertEquals(listOf("Valid"), guide.single().programmeList.map { it.title })
    }

    @Test
    fun sortsDeduplicatesAndInfersMissingStopFromTheNextProgramme() {
        val guide = parse("""
            <tv><channel id="c1"/>
              <programme channel="c1" start="20250101020000Z" stop="20250101030000Z"><title>C</title></programme>
              <programme channel="c1" start="20250101010000Z"><title>B</title></programme>
              ${programme(title = "A")}${programme(title = "A")}
            </tv>
        """.trimIndent())
        val programmes = guide.single().programmeList
        assertEquals(listOf("A", "B", "C"), programmes.map { it.title })
        assertEquals(programmes[2].startAt, programmes[1].endAt)
    }

    @Test
    fun channelIdsWorkWithoutDisplayNamesOrPriorDeclarations() {
        val guide = parse("""<tv>${programme()}<channel id="c1"/><channel id="c2"/></tv>""")
        assertEquals(listOf("c1"), guide.first().channelList)
        assertEquals(1, guide.first().programmeList.size)
        val undeclared = parse("""<tv>${programme()}</tv>""")
        assertEquals("c1", undeclared.single().channelId)
    }

    @Test
    fun readsEntitiesNestedTextAndNamespacesWithoutMixingChannels() {
        val guide = parse("""
            <tv xmlns="urn:xmltv">
              <channel id="c1"><display-name>A &amp; B</display-name></channel>
              <channel id="c2"><display-name>C</display-name></channel>
              ${programme(title = "<b>News</b> &amp; Weather")}
              <programme channel="c2" start="20250101000000Z" stop="20250101010000Z"><title>Other</title></programme>
            </tv>
        """.trimIndent())
        assertEquals("News & Weather", guide[0].programmeList.single().title)
        assertEquals("Other", guide[1].programmeList.single().title)
        assertTrue("A & B" in guide[0].channelList)
    }

    @Test
    fun honoursXmlEncodingAndByteOrderMarks() {
        for (encoding in listOf("UTF-8", "UTF-16", "GB18030")) {
            val xml = """<?xml version="1.0" encoding="$encoding"?><tv><channel id="c1"><display-name>综合频道</display-name></channel>${programme(title = "新闻")}</tv>"""
            val bytes = xml.toByteArray(charset(encoding))
            val guide = XmltvParser(KXmlParser()).parse(ByteArrayInputStream(bytes))
            assertTrue("综合频道" in guide.single().channelList)
            assertEquals("新闻", guide.single().programmeList.single().title)
        }
    }

    @Test
    fun doesNotDependOnAvailableOrLoadExternalDtd() {
        val bytes = """<?xml version="1.0"?><!DOCTYPE tv SYSTEM "http://127.0.0.1:1/never.dtd"><tv><channel id="c1"/>${programme()}</tv>""".toByteArray()
        val stream = object : ByteArrayInputStream(bytes) { override fun available() = 0 }
        assertEquals(1, XmltvParser(KXmlParser()).parse(stream).single().programmeList.size)
    }

    @Test
    fun timestampsAreStrictAndIndependentOfDeviceLocaleAndZone() {
        val oldLocale = Locale.getDefault()
        val oldZone = TimeZone.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("th-TH"))
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
            val expected = 1_735_689_600_000L
            for (time in listOf("20250101000000 +0000", "20250101080000+0800", "20241231190000 -05:00", "202501010000Z", "20250101", "20250101000000")) {
                assertEquals(time, expected, XmltvParser.parseTime(time))
            }
            for (invalid in listOf("20250230000000Z", "20250101240000Z", "20250101000000 +0860", "20250101000000 +2500", "20250101000000 garbage", "", "20250101000")) {
                assertNull(invalid, XmltvParser.parseTime(invalid))
            }
        } finally {
            Locale.setDefault(oldLocale)
            TimeZone.setDefault(oldZone)
        }
    }

    @Test
    fun rejectsBrokenDocumentsInsteadOfReturningPartialGuide() {
        for (xml in listOf("<html/>", "<tv><channel id='c1'>", "<tv/>trailing", "<tv/><tv/>")) {
            assertThrows(xml, XmlPullParserException::class.java) { parse(xml) }
        }
    }
}
