package top.yogiczy.mytv.core.data.entities.channel

import kotlinx.serialization.Serializable

/**
 * 频道线路
 */
@Serializable
data class ChannelLine(
    val url: String = "",
    val httpUserAgent: String? = null,
    val name: String? = if (url.contains("$")) url.split("$").lastOrNull() else null,
    val manifestType: String? = null,
    val licenseType: String? = null,
    val licenseKey: String? = null,
    val catchup: String? = null,
    val catchupSource: String? = null,
    val pltv2tvod: Boolean = true,
    val httpReferrer: String? = null,
    val httpOrigin: String? = null,
) {

    /** Per-line options override global defaults without duplicate case-insensitive header names. */
    fun httpRequestHeaders(defaults: Map<String, String> = emptyMap()): Map<String, String> =
        defaults.toMutableMap().apply {
            listOf("Referer" to httpReferrer, "Origin" to httpOrigin).forEach { (name, value) ->
                if (!value.isNullOrBlank()) {
                    keys.removeAll { it.equals(name, ignoreCase = true) }
                    put(name, value)
                }
            }
        }

    val playableUrl: String
        get() = url.substringBefore("$").let {
            // 修复部分链接无法播放，实际请求时?将去掉，导致链接无法访问，因此加上一个t
            if (url.endsWith("?")) "${it}t" else it
        }

    companion object {
        val EXAMPLE =
            ChannelLine(
                url = "http://1.2.3.4\$LR•IPV6『线路1』",
                httpUserAgent = "okhttp",
            )
    }
}
