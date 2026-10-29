package top.yogiczy.mytv.core.data.utils

import top.yogiczy.mytv.core.data.entities.channel.ChannelLine
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** YYKM-compatible catchup rules, shared by the EPG and player. Times are milliseconds. */
object ChannelPlayback {
    private data class Options(val type: String, val source: String)
    // Android's ICU regex engine requires the literal closing brace to be escaped too.
    private val placeholder = Regex("""\$?\{([^}]+)\}""")
    private val automaticSource = "?playseek=\${(b)yyyyMMddHHmmss}-\${(e)yyyyMMddHHmmss}"

    private fun options(line: ChannelLine): Options? {
        val type = line.catchup?.trim()?.lowercase(Locale.ROOT)
        val source = line.catchupSource?.trim()
        // Explicitly disabled/unsupported rules must not advertise a non-working replay button.
        if (type != null && type !in setOf("default", "append", "")) return null
        if (!type.isNullOrEmpty() && !source.isNullOrEmpty()) return Options(type, source)
        return if (listOf("pltv", "tvod").any { line.playableUrl.contains(it, true) }) {
            Options("append", automaticSource)
        } else null
    }

    fun isSupported(line: ChannelLine): Boolean = options(line) != null

    /** Returns null on malformed templates, without changing the live line or its DRM/UA. */
    fun playbackUrl(
        line: ChannelLine,
        startAt: Long,
        endAt: Long,
        now: Long = System.currentTimeMillis(),
        timeZone: TimeZone = TimeZone.getDefault(),
    ): String? = runCatching {
        require(endAt > startAt)
        val options = options(line) ?: return null
        val source = placeholder.replace(options.source) { match ->
            expandTime(match.groupValues[1], startAt, endAt, now, timeZone) ?: match.value
        }
        require(!placeholder.containsMatchIn(source))
        var result = when (options.type) {
            "default" -> source
            "append" -> appendQuery(line.playableUrl, source)
            else -> return null
        }
        if (line.pltv2tvod) result = result.replace("pltv", "tvod", ignoreCase = true)
        require(URI(result).isAbsolute && !placeholder.containsMatchIn(result))
        result
    }.getOrNull()

    private fun expandTime(
        token: String, startAt: Long, endAt: Long, now: Long, localZone: TimeZone,
    ): String? {
        val parts = token.removePrefix("(").split(':', ')', '|')
        val selector = parts[0]
        val time = when (selector) {
            "b", "start", "utc" -> startAt
            "e", "end", "utcend" -> endAt
            "now", "lutc" -> now
            "duration" -> endAt - startAt
            else -> return null
        }
        val format = parts.getOrNull(1)?.takeIf { it.isNotBlank() } ?: when (selector) {
            "b", "e" -> "timestamp"
            "duration" -> "duration"
            else -> "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"
        }
        when (format.lowercase(Locale.ROOT)) {
            "timestamp", "13" -> return time.toString()
            "timestampseconds", "10", "duration" -> return (time / 1000).toString()
        }
        val zoneId = if (selector in setOf("utc", "utcend", "lutc")) "UTC" else parts.getOrNull(2)
        val zone = zoneId?.let {
            require(it.equals("utc", true) || it in TimeZone.getAvailableIDs())
            TimeZone.getTimeZone(it)
        } ?: localZone
        // YYKM also accepts Y/m/d/H/M/S shorthand alongside Java date patterns.
        val pattern = if (listOf("yyyy", "MM", "dd", "HH", "mm", "ss").any { it in format }) format
        else buildString {
            format.forEach { append(when (it) {
                'Y' -> "yyyy"; 'm' -> "MM"; 'd' -> "dd"
                'H' -> "HH"; 'M' -> "mm"; 'S' -> "ss"
                else -> it.toString()
            }) }
        }
        return SimpleDateFormat(pattern, Locale.ROOT).apply { timeZone = zone }.format(Date(time))
    }

    private fun appendQuery(url: String, source: String): String {
        // Decode then encode each value once, preserving signed live URL parameters and fragments.
        val query = source.removePrefix("?").substringBefore('#')
        val parameters = query.split('&').filter { it.isNotEmpty() }.distinctBy { it.substringBefore('=') }
            .joinToString("&") { part ->
                fun encode(value: String) = URLEncoder.encode(URLDecoder.decode(value, "UTF-8"), "UTF-8")
                    .replace("+", "%20").replace("%7E", "~").replace("*", "%2A")
                encode(part.substringBefore('=')) + "=" + encode(part.substringAfter('=', ""))
            }
        if (parameters.isEmpty()) return url
        val base = url.substringBefore('#')
        val fragment = if ('#' in url) "#${url.substringAfter('#')}" else ""
        val separator = when {
            '?' !in base -> "?"
            base.endsWith('?') || base.endsWith('&') -> ""
            else -> "&"
        }
        return base + separator + parameters + fragment
    }
}
