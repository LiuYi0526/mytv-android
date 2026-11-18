package top.yogiczy.mytv.core.data.network

import java.net.URLDecoder
import java.util.Locale

/** HTTP header subset of Kodi's URL protocol options (https://kodi.wiki/view/HTTP). */
data class HttpUrlHeaders(val url: String, val headers: Map<String, String>) {
    companion object {
        private val headerName = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
        // These are Kodi transport controls, not HTTP headers. We do not implement them here.
        private val transportOptions = setOf("auth", "seekable", "noshout", "sslcipherlist",
            "connection-timeout", "failonerror", "redirect-limit", "postdata", "customrequest", "verifypeer")

        fun parse(value: String): HttpUrlHeaders {
            val separator = value.indexOf('|')
            if (separator < 0 || !(value.startsWith("http://", true) || value.startsWith("https://", true))) {
                return HttpUrlHeaders(value, emptyMap())
            }
            val headers = linkedMapOf<String, String>()
            value.substring(separator + 1).split('&').forEach { option ->
                if ('=' !in option) return@forEach
                val key = decode(option.substringBefore('='))?.trim() ?: return@forEach
                val content = decode(option.substringAfter('=')) ?: return@forEach
                val forcedHeader = key.startsWith('!')
                val rawName = key.removePrefix("!")
                val lowerName = rawName.lowercase(Locale.ROOT)
                if (!forcedHeader && lowerName in transportOptions) return@forEach
                val name = when (lowerName) {
                    "user-agent" -> "User-Agent"
                    "referer", "referrer" -> "Referer"
                    "origin" -> "Origin"
                    "cookie" -> "Cookie"
                    "authorization" -> "Authorization"
                    "encoding", "acceptencoding", "accept-encoding" -> "Accept-Encoding"
                    else -> rawName
                }
                if (!headerName.matches(name) || content.any { it != '\t' && it !in ' '..'~' }) return@forEach
                headers.keys.removeAll { it.equals(name, true) }
                headers[name] = content
            }
            return HttpUrlHeaders(value.substring(0, separator), headers)
        }

        private fun decode(value: String): String? = runCatching { URLDecoder.decode(value, "UTF-8") }.getOrNull()
    }
}
