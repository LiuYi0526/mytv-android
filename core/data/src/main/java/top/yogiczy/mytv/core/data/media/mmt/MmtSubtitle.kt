package top.yogiczy.mytv.core.data.media.mmt

import java.io.ByteArrayOutputStream

/** ARIB subtitle data-unit assembly, after MMTP MFU reassembly. XML timing/style is preserved. */
internal class MmtSubtitle {
    private val content = ByteArrayOutputStream()
    private var next = -1
    private var last = -1
    private var mpu = -1L
    fun reset() { content.reset(); next = -1; last = -1; mpu = -1 }

    fun consume(sequence: Long, data: ByteArray): ByteArray? {
        try {
            val b = MmtBytes(data)
            b.skip(2)
            val index = b.u8()
            val end = b.u8()
            val flags = b.u8()
            if (flags ushr 4 != 0 || index > end) { reset(); return null }
            val large = flags and 8 != 0
            val size = if (large) b.u32().toInt() else b.u16()
            if (index == 0 && end > 0 && flags and 4 != 0) b.skip(end * if (large) 5 else 3)
            b.need(size)
            if (index == 0) { reset(); next = 0; last = end; mpu = sequence }
            if (index != next || end != last || mpu != sequence || size > MAX_XML_BYTES - content.size()) {
                reset(); return null
            }
            content.write(b.data, b.position, size)
            next++
            if (index != end) return null
            return content.toByteArray().also { reset() }
        } catch (_: MmtMalformed) { reset(); return null }
    }

    companion object {
        private const val MAX_XML_BYTES = 512 * 1024
        /** ARIB carries its canvas in the descriptor; Media3 handles child timing inheritance. */
        fun prepareTtml(data: ByteArray, resolution: Int): ByteArray {
            try {
                val factory = org.xmlpull.v1.XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }
                val parser = factory.newPullParser().apply { setInput(data.inputStream(), "UTF-8") }
                val writer = java.io.StringWriter()
                val serializer = factory.newSerializer().apply { setOutput(writer); startDocument("UTF-8", null) }
                val styling = "http://www.w3.org/ns/ttml#styling"
                while (parser.nextToken() != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                    when (parser.eventType) {
                        org.xmlpull.v1.XmlPullParser.START_TAG -> {
                            if (parser.depth == 1 && parser.name != "tt") return data
                            for (i in parser.getNamespaceCount(parser.depth - 1) until parser.getNamespaceCount(parser.depth)) {
                                serializer.setPrefix(parser.getNamespacePrefix(i).orEmpty(), parser.getNamespaceUri(i))
                            }
                            if (parser.depth == 1 && parser.getAttributeValue(styling,"extent") == null) {
                                var prefix = "mytvMmtTts"
                                while (parser.getNamespace(prefix) != null) prefix += "x"
                                serializer.setPrefix(prefix,styling)
                            }
                            serializer.startTag(parser.namespace.orEmpty(),parser.name)
                            for (i in 0 until parser.attributeCount) serializer.attribute(
                                parser.getAttributeNamespace(i).orEmpty(),parser.getAttributeName(i),parser.getAttributeValue(i))
                            if (parser.depth == 1 && parser.getAttributeValue(styling,"extent") == null) {
                                val scale = resolution.coerceIn(0,2)
                                serializer.attribute(styling,"extent","${1920 shl scale}px ${1080 shl scale}px")
                            }
                            // Media3 1.11.1 inherits child starts, but still needs a known origin
                            // for documents with only end/dur and no explicit begin anywhere.
                            if (parser.name == "body" && parser.getAttributeValue("", "begin") == null) {
                                serializer.attribute("", "begin", "0s")
                            }
                        }
                        org.xmlpull.v1.XmlPullParser.END_TAG -> serializer.endTag(parser.namespace.orEmpty(),parser.name)
                        org.xmlpull.v1.XmlPullParser.TEXT, org.xmlpull.v1.XmlPullParser.CDSECT,
                        org.xmlpull.v1.XmlPullParser.ENTITY_REF -> serializer.text(parser.text ?: return data)
                        org.xmlpull.v1.XmlPullParser.IGNORABLE_WHITESPACE -> serializer.ignorableWhitespace(parser.text)
                        org.xmlpull.v1.XmlPullParser.COMMENT -> serializer.comment(parser.text)
                        org.xmlpull.v1.XmlPullParser.PROCESSING_INSTRUCTION -> serializer.processingInstruction(parser.text)
                        org.xmlpull.v1.XmlPullParser.DOCDECL -> return data
                    }
                }
                serializer.endDocument()
                return writer.toString().toByteArray(Charsets.UTF_8)
            } catch (_: org.xmlpull.v1.XmlPullParserException) { return data }
            catch (_: java.io.IOException) { return data }
            catch (_: IllegalArgumentException) { return data }
        }
    }
}
