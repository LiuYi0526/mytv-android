package top.yogiczy.mytv.core.data.entities.channel

import kotlinx.serialization.Serializable
import top.yogiczy.mytv.core.data.entities.iptvsource.IptvSource
import top.yogiczy.mytv.core.data.entities.iptvsource.IptvSourceUrl

@Serializable
data class ChannelHistoryItem(val sourceName: String, val sourceUrl: String, val channel: Channel)

@Serializable
data class ChannelHistoryList(val value: List<ChannelHistoryItem> = emptyList()) {
    fun record(source: IptvSource, channel: Channel): ChannelHistoryList {
        if (channel.name.isBlank() || channel.lineList.none { it.url.isNotBlank() }) return this
        val entry = ChannelHistoryItem(source.name, IptvSourceUrl.parse(source.url).requestUrl, channel)
        return ChannelHistoryList((listOf(entry) + value.filterNot {
            it.sourceName == entry.sourceName && it.sourceUrl == entry.sourceUrl && it.channel.name == channel.name
        }).take(100))
    }

    fun channels(source: IptvSource, available: ChannelList): List<Channel> {
        val url = IptvSourceUrl.parse(source.url).requestUrl
        val byName = available.groupBy { it.name }
        return value.asSequence()
            .filter { it.sourceName == source.name && it.sourceUrl == url }
            .mapNotNull { entry ->
                val candidates = byName[entry.channel.name].orEmpty()
                val watchedUrls = entry.channel.lineList.map { it.url }
                // Different groups may share a name and EPG id but use different streams/logos.
                candidates.firstOrNull { channel -> channel.lineList.map { it.url } == watchedUrls }
                    ?: candidates.singleOrNull()
            }
            .distinctBy { it.name }
            .take(20)
            .toList()
    }
}
