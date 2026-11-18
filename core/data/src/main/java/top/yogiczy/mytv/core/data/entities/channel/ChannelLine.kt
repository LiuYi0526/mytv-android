package top.yogiczy.mytv.core.data.entities.channel

import kotlinx.serialization.Serializable
import top.yogiczy.mytv.core.data.network.HttpUrlHeaders

/**
 * 频道线路
 */
@Serializable
data class ChannelLine(
    val url: String = "",
    val httpUserAgent: String? = null,
    val name: String? = if (url.contains("$")) url.substringAfterLast('$').substringBefore('|') else null,
    val manifestType: String? = null,
    val licenseType: String? = null,
    val licenseKey: String? = null,
    val catchup: String? = null,
    val catchupSource: String? = null,
    val pltv2tvod: Boolean = true,
    val httpReferrer: String? = null,
    val httpOrigin: String? = null,
    val httpHeaders: Map<String, String> = emptyMap(),
) {
    private val urlHeaders by lazy {
        // Existing $line labels can be before or after the protocol-options suffix.
        val base = url.substringBefore('|').substringBefore('$')
        val options = if ('|' in url) "|" + url.substringAfter('|').substringBefore('$') else ""
        HttpUrlHeaders.parse(base + options)
    }

    /** Per-line options override global defaults without duplicate case-insensitive header names. */
    fun httpRequestHeaders(defaults: Map<String, String> = emptyMap()): Map<String, String> =
        defaults.toMutableMap().apply {
            fun putHeader(name: String, value: String) {
                keys.removeAll { it.equals(name, ignoreCase = true) }
                put(name, value)
            }
            httpHeaders.forEach { (name, value) -> putHeader(name, value) }
            listOf("Referer" to httpReferrer, "Origin" to httpOrigin).forEach { (name, value) ->
                if (!value.isNullOrBlank()) {
                    putHeader(name, value)
                }
            }
            urlHeaders.headers.forEach { (name, value) -> putHeader(name, value) }
        }

    /** Resolve on use, including lines restored from old caches; keep raw playlist identities intact. */
    fun resolveHttpOptions(): ChannelLine {
        if ('|' !in url && httpHeaders.isEmpty()) return this
        val headers = httpRequestHeaders()
        fun header(name: String) = headers.entries.firstOrNull { it.key.equals(name, true) }?.value
        return copy(url = urlHeaders.url, httpUserAgent = header("User-Agent") ?: httpUserAgent,
            httpReferrer = header("Referer") ?: httpReferrer, httpOrigin = header("Origin") ?: httpOrigin,
            httpHeaders = headers)
    }

    val playableUrl: String
        get() = urlHeaders.url.let {
            // 修复部分链接无法播放，实际请求时?将去掉，导致链接无法访问，因此加上一个t
            if (it.endsWith("?")) "${it}t" else it
        }

    companion object {
        val EXAMPLE =
            ChannelLine(
                url = "http://1.2.3.4\$LR•IPV6『线路1』",
                httpUserAgent = "okhttp",
            )
    }
}
