package top.yogiczy.mytv.core.data.repositories.epg

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import top.yogiczy.mytv.core.data.entities.epg.Epg
import top.yogiczy.mytv.core.data.entities.epg.EpgList
import top.yogiczy.mytv.core.data.entities.epg.EpgProgramme
import top.yogiczy.mytv.core.data.entities.epg.EpgProgrammeList
import java.io.InputStream
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.SimpleTimeZone
import java.util.TimeZone

/** Streaming XMLTV reader. Tag readers always consume their complete element. */
internal class XmltvParser(
    private val parser: XmlPullParser,
    private val normalizeName: (String) -> String = { it },
) {
    private data class ChannelItem(val id: String, val names: List<String>, val icon: String?)
    private data class ProgrammeItem(val channel: String, val start: Long, val stop: Long?, val title: String)

    fun parse(input: InputStream): EpgList {
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        // Let the XML parser detect the declaration/BOM instead of forcing UTF-8.
        parser.setInput(input, null)
        parser.nextTag()
        if (parser.name != "tv") throw XmlPullParserException("Expected XMLTV <tv> root")
        val rootDepth = parser.depth
        val channels = linkedMapOf<String, ChannelItem>()
        val programmes = linkedMapOf<String, MutableList<ProgrammeItem>>()

        while (true) {
            nextInElement()
            if (parser.eventType == XmlPullParser.END_TAG && parser.depth == rootDepth) break
            if (parser.eventType != XmlPullParser.START_TAG) continue
            when (parser.name) {
                "channel" -> readChannel()?.let { channels[it.id] = it }
                "programme" -> readProgramme()?.let { programmes.getOrPut(it.channel) { mutableListOf() }.add(it) }
                else -> skipElement()
            }
        }
        // Validate the rest of the document (including a truncated root/trailing garbage).
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> throw XmlPullParserException("Multiple XML root elements")
                XmlPullParser.TEXT -> if (parser.text.isNotBlank()) throw XmlPullParserException("Text after XMLTV root")
            }
        }

        programmes.keys.forEach { id -> channels.getOrPut(id) { ChannelItem(id, emptyList(), null) } }
        return EpgList(channels.values.map { channel ->
            val sorted = programmes[channel.id].orEmpty().sortedBy { it.start }
            val schedule = sorted.mapIndexedNotNull { index, programme ->
                val stop = programme.stop ?: sorted.getOrNull(index + 1)?.start ?: return@mapIndexedNotNull null
                if (stop <= programme.start) return@mapIndexedNotNull null
                EpgProgramme(programme.start, stop, programme.title)
            }.distinct()
            Epg(
                channelId = channel.id,
                channelList = (listOf(channel.id) + channel.names.flatMap { listOf(it, normalizeName(it)) })
                    .map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
                logo = channel.icon,
                programmeList = EpgProgrammeList(schedule),
            )
        })
    }

    private fun readChannel(): ChannelItem? {
        val id = parser.getAttributeValue(null, "id")?.trim().orEmpty()
        val names = mutableListOf<String>()
        var icon: String? = null
        val depth = parser.depth
        while (true) {
            nextInElement()
            if (parser.eventType == XmlPullParser.END_TAG && parser.depth == depth) break
            if (parser.eventType != XmlPullParser.START_TAG) continue
            when (parser.name) {
                "display-name" -> readText().takeIf { it.isNotBlank() }?.let { names.add(it) }
                "icon" -> {
                    icon = parser.getAttributeValue(null, "src")?.trim()?.takeIf { it.isNotEmpty() } ?: icon
                    skipElement()
                }
                else -> skipElement()
            }
        }
        return id.takeIf { it.isNotEmpty() }?.let { ChannelItem(it, names, icon) }
    }

    private fun readProgramme(): ProgrammeItem? {
        val channel = parser.getAttributeValue(null, "channel")?.trim().orEmpty()
        val start = parseTime(parser.getAttributeValue(null, "start"))
        val rawStop = parser.getAttributeValue(null, "stop")?.trim()?.takeIf { it.isNotEmpty() }
        val stop = parseTime(rawStop)
        var title: String? = null
        val depth = parser.depth
        while (true) {
            nextInElement()
            if (parser.eventType == XmlPullParser.END_TAG && parser.depth == depth) break
            if (parser.eventType != XmlPullParser.START_TAG) continue
            if (parser.name == "title") {
                val text = readText()
                if (title == null && text.isNotBlank()) title = text
            } else skipElement()
        }
        if (channel.isEmpty() || start == null || title == null) return null
        if (rawStop != null && (stop == null || stop <= start)) return null
        return ProgrammeItem(channel, start, stop, title)
    }

    private fun readText(): String {
        val depth = parser.depth
        val text = StringBuilder()
        while (true) {
            nextInElement()
            if (parser.eventType == XmlPullParser.END_TAG && parser.depth == depth) break
            if (parser.eventType == XmlPullParser.TEXT) text.append(parser.text)
        }
        return text.toString().trim()
    }

    private fun skipElement() {
        val depth = parser.depth
        while (true) {
            nextInElement()
            if (parser.eventType == XmlPullParser.END_TAG && parser.depth == depth) break
        }
    }

    private fun nextInElement(): Int {
        val event = parser.next()
        if (event == XmlPullParser.END_DOCUMENT) throw XmlPullParserException("Unexpected end of XMLTV element")
        return event
    }

    companion object {
        private val timestamp = Regex("^(\\d{4}(?:\\d{2}){0,5})(?:\\s*(Z|UTC|GMT|[+-]\\d{2}:?\\d{2}))?$", RegexOption.IGNORE_CASE)
        private const val DATE_PATTERN = "yyyyMMddHHmmss"

        internal fun parseTime(value: String?): Long? {
            val match = value?.trim()?.let { timestamp.matchEntire(it) } ?: return null
            val digits = match.groupValues[1]
            val zone = match.groupValues[2]
            val timeZone = if (zone.startsWith('+') || zone.startsWith('-')) {
                val offset = zone.substring(1).replace(":", "")
                val hours = offset.take(2).toInt()
                val minutes = offset.takeLast(2).toInt()
                if (hours > 23 || minutes > 59) return null
                SimpleTimeZone((hours * 60 + minutes) * 60_000 * if (zone[0] == '-') -1 else 1, "XMLTV")
            } else TimeZone.getTimeZone("UTC")
            val formatter = SimpleDateFormat(DATE_PATTERN.take(digits.length), Locale.ROOT).apply {
                isLenient = false
                this.timeZone = timeZone
            }
            val position = ParsePosition(0)
            return formatter.parse(digits, position)?.takeIf { position.index == digits.length }?.time
        }
    }
}
