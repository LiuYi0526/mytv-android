package top.yogiczy.mytv.core.data.entities.channel

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

/**
 * 频道分组列表
 */
@Serializable
@Immutable
data class ChannelGroupList(
    val value: List<ChannelGroup> = emptyList(),
) : List<ChannelGroup> by value {
    companion object {
        val EXAMPLE = ChannelGroupList(List(20) { groupIdx ->
            ChannelGroup(
                name = "频道分组${groupIdx + 1}",
                channelList = ChannelList(
                    List(20) { idx ->
                        Channel.EXAMPLE.copy(
                            name = "频道${groupIdx + 1}-${idx + 1}",
                            epgName = "频道${groupIdx + 1}-${idx + 1}",
                        )
                    },
                )
            )
        })

        fun ChannelGroupList.channelGroupIdx(channel: Channel): Int {
            val resolved = channelList.resolveChannel(channel) ?: return -1
            return indexOfFirst { group -> group.channelList.any { it === resolved } }
        }

        fun ChannelGroupList.chanelGroup(channel: Channel) = this[channelGroupIdx(channel)]

        fun ChannelGroupList.channelIdx(channel: Channel): Int {
            val channels = channelList
            val resolved = channels.resolveChannel(channel) ?: return -1
            return channels.indexOfFirst { it === resolved }
        }

        fun ChannelGroupList.channelFirstOrNull() = this.firstOrNull()?.channelList?.firstOrNull()

        fun ChannelGroupList.channelLastOrNull() = this.lastOrNull()?.channelList?.lastOrNull()

        val ChannelGroupList.channelList: ChannelList
            get() = ChannelList(this.asSequence().flatMap { it.channelList.asSequence() }.toList())
    }

    fun withMetadata(): ChannelGroupList {
        var index = 0
        return ChannelGroupList(map { group ->
            group.copy(channelList = ChannelList(group.channelList.map { channel ->
                channel.copy(index = index++, groupName = group.name)
            }))
        })
    }
}
