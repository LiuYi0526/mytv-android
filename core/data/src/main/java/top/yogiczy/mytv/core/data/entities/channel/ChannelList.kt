package top.yogiczy.mytv.core.data.entities.channel

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

/**
 * 频道列表
 */
@Serializable
@Immutable
data class ChannelList(
    val value: List<Channel> = emptyList(),
) : List<Channel> by value {
    /** Resolve a saved selection to current metadata without losing its source group. */
    fun resolveChannel(channel: Channel): Channel? {
        firstOrNull { it === channel }?.let { return it }
        val urls = channel.lineList.map { it.url }
        val candidates = filter {
            it.name == channel.name && it.lineList.map { line -> line.url } == urls
        }
        if (channel.groupName.isNotBlank()) {
            candidates.firstOrNull { it.groupName == channel.groupName }?.let { return it }
        }
        return candidates.firstOrNull { channel.index >= 0 && it.index == channel.index }
            ?: candidates.firstOrNull()
    }

    companion object {
        val EXAMPLE = ChannelList(List(10) { i ->
            Channel.EXAMPLE.copy(
                name = "直播频道${i + 1}",
                epgName = "直播频道${i + 1}",
                index = 9998,
            )
        })
    }
}
