package top.yogiczy.mytv.tv.ui.screen.dashboard.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import top.yogiczy.mytv.core.data.entities.channel.Channel
import top.yogiczy.mytv.core.data.entities.epg.EpgList
import top.yogiczy.mytv.core.data.entities.epg.EpgList.Companion.recentProgramme
import top.yogiczy.mytv.tv.ui.material.LazyRow
import top.yogiczy.mytv.tv.ui.material.items
import top.yogiczy.mytv.tv.ui.rememberChildPadding
import top.yogiczy.mytv.tv.ui.screen.channels.components.ChannelsChannelItem

@Composable
fun DashboardSectionHeader(title: String, count: Int? = null) {
    val padding = rememberChildPadding()
    Row(Modifier.fillMaxWidth().padding(horizontal = padding.start), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        count?.let { Text("共 $it 个频道", style = MaterialTheme.typography.bodyLarge) }
    }
}

@Composable
fun DashboardHistoryList(
    channels: List<Channel>,
    epgListProvider: () -> EpgList,
    onChannelSelected: (Channel) -> Unit,
    onChannelFavoriteToggle: (Channel) -> Unit,
) {
    val padding = rememberChildPadding()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        DashboardSectionHeader("最近观看", channels.size)
        if (channels.isNotEmpty()) LazyRow(
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            contentPadding = PaddingValues(start = padding.start, end = padding.end),
        ) { runtime ->
            items(channels, runtime) { itemModifier, channel ->
                ChannelsChannelItem(
                    modifier = itemModifier,
                    channelProvider = { channel },
                    onChannelSelected = { onChannelSelected(channel) },
                    onChannelFavoriteToggle = { onChannelFavoriteToggle(channel) },
                    recentEpgProgrammeProvider = { epgListProvider().recentProgramme(channel) },
                )
            }
        }
    }
}
