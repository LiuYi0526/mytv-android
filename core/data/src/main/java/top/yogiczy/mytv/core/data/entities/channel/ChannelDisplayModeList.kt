package top.yogiczy.mytv.core.data.entities.channel

import kotlinx.serialization.Serializable
import top.yogiczy.mytv.core.data.entities.iptvsource.IptvSource
import top.yogiczy.mytv.core.data.entities.iptvsource.IptvSourceUrl

/** Keep only channel identity and the display mode, rather than a full channel/EPG snapshot. */
@Serializable
data class ChannelDisplayModeItem(
    val sourceName: String,
    val sourceUrl: String,
    val channelName: String,
    val groupName: String,
    val lineUrls: List<String>,
    val mode: Int,
)

@Serializable
data class ChannelDisplayModeList(val value: List<ChannelDisplayModeItem> = emptyList()) {
    private fun find(source: IptvSource, channel: Channel, available: ChannelList): ChannelDisplayModeItem? {
        val sourceUrl = IptvSourceUrl.parse(source.url).requestUrl
        val candidates = value.filter {
            it.sourceName == source.name && it.sourceUrl == sourceUrl &&
                it.channelName == channel.name && it.groupName == channel.groupName
        }
        val urls = channel.lineList.map { it.url }
        candidates.firstOrNull { it.lineUrls == urls }?.let { return it }
        // Keep the preference after a playlist refresh changes the URLs, but never guess
        // between multiple entries with the same name in the same group.
        return candidates.singleOrNull()?.takeIf {
            available.count { it.name == channel.name && it.groupName == channel.groupName } == 1
        }
    }

    fun mode(source: IptvSource, channel: Channel, available: ChannelList): Int? =
        find(source, channel, available)?.mode

    /** A null mode removes the override so this channel follows the global setting again. */
    fun remember(source: IptvSource, channel: Channel, available: ChannelList, mode: Int?): ChannelDisplayModeList {
        if (channel.name.isBlank() || channel.lineList.none { it.url.isNotBlank() }) return this
        val previous = find(source, channel, available)
        val remaining = value.filterNot { it == previous }
        if (mode == null) return ChannelDisplayModeList(remaining)
        val item = ChannelDisplayModeItem(source.name, IptvSourceUrl.parse(source.url).requestUrl,
            channel.name, channel.groupName, channel.lineList.map { it.url }, mode)
        return ChannelDisplayModeList(remaining + item)
    }

    fun updateSource(old: IptvSource, updated: IptvSource): ChannelDisplayModeList {
        val oldUrl = IptvSourceUrl.parse(old.url).requestUrl
        val newUrl = IptvSourceUrl.parse(updated.url).requestUrl
        return ChannelDisplayModeList(value.mapNotNull {
            if (it.sourceName != old.name || it.sourceUrl != oldUrl) it
            else if (oldUrl != newUrl) null
            else it.copy(sourceName = updated.name)
        })
    }
}
