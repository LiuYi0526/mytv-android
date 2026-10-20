package top.yogiczy.mytv.core.data.entities.iptvsource

import top.yogiczy.mytv.core.data.entities.channel.ChannelGroupList
import top.yogiczy.mytv.core.data.entities.channel.ChannelLineList
import top.yogiczy.mytv.core.data.entities.channel.ChannelList
import java.net.URLDecoder

data class IptvSourceUrl(val requestUrl: String, val playerUserAgent: String? = null) {
    /** Apply after reading the cache as well, keeping per-line UA overrides intact. */
    fun applyTo(groups: ChannelGroupList): ChannelGroupList {
        val userAgent = playerUserAgent ?: return groups
        return ChannelGroupList(groups.map { group ->
            group.copy(channelList = ChannelList(group.channelList.map { channel ->
                channel.copy(lineList = ChannelLineList(channel.lineList.map { line ->
                    if (line.httpUserAgent == null) line.copy(httpUserAgent = userAgent) else line
                }))
            }))
        })
    }

    companion object {
        private val parameter = Regex("(?:^|;)([^;=]+)=(.*?)(?=;[^;=]+=|$)")

        fun parse(value: String): IptvSourceUrl {
            val url = value.trim()
            val fragment = url.substringAfter('#', "")
            if (fragment != "sp" && !fragment.startsWith("sp;")) return IptvSourceUrl(url)

            val userAgent = parameter.findAll(fragment.removePrefix("sp;"))
                .lastOrNull { it.groupValues[1] == "vp-ua" }
                ?.groupValues?.get(2)
                ?.let { raw ->
                    // Fragment values are not form data: a literal '+' must stay a '+'.
                    runCatching { URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8") }.getOrNull()
                }
                ?.takeIf { ua -> ua.isNotBlank() && ua.none { it < ' ' || it == '\u007f' } }
            return IptvSourceUrl(url.substringBefore('#'), userAgent)
        }
    }
}
