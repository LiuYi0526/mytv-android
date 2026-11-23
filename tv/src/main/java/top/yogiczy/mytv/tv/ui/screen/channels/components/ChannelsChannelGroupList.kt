package top.yogiczy.mytv.tv.ui.screen.channels.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import top.yogiczy.mytv.core.data.entities.channel.ChannelGroup
import top.yogiczy.mytv.core.data.entities.channel.ChannelGroupList
import top.yogiczy.mytv.tv.ui.material.LazyRow
import top.yogiczy.mytv.tv.ui.material.items
import top.yogiczy.mytv.tv.ui.rememberChildPadding
import top.yogiczy.mytv.tv.ui.theme.MyTvTheme
import top.yogiczy.mytv.tv.ui.utils.focusOnLaunched
import top.yogiczy.mytv.tv.ui.utils.ifElse

@Composable
fun ChannelsChannelGroupList(
    modifier: Modifier = Modifier,
    channelGroupListProvider: () -> ChannelGroupList = { ChannelGroupList() },
    currentChannelGroupProvider: () -> ChannelGroup = { ChannelGroup() },
    selectOnFocus: Boolean = false,
    focusSelectedOnEntry: Boolean = false,
    state: LazyListState = rememberLazyListState(),
    selectedGroupFocusRequester: FocusRequester? = null,
    onChannelGroupSelected: (ChannelGroup) -> Unit = {},
) {
    val channelGroupList = channelGroupListProvider()
    val currentChannelGroup = currentChannelGroupProvider()
    val childPadding = rememberChildPadding()

    if (channelGroupList.size <= 1) return

    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        LazyRow(
            state = state,
            contentPadding = PaddingValues(start = childPadding.start, end = childPadding.end),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            preservePaddingOnFocus = true,
            // An explicit request for the selected group takes precedence over the row's focus history.
            restoreFocus = selectedGroupFocusRequester == null,
        ) { runtime ->
            items(channelGroupList, runtime) { itemModifier, channelGroup ->
                val isSelected = channelGroup == currentChannelGroup
                ChannelsChannelGroupItem(
                    modifier = itemModifier
                        .then(
                            if (isSelected && selectedGroupFocusRequester != null)
                                Modifier.focusRequester(selectedGroupFocusRequester)
                            else Modifier
                        )
                        .ifElse(isSelected && focusSelectedOnEntry, Modifier.focusOnLaunched()),
                    channelGroupProvider = { channelGroup },
                    isSelectedProvider = { isSelected },
                    selectOnFocus = selectOnFocus,
                    onChannelGroupSelected = { onChannelGroupSelected(channelGroup) },
                )
            }
        }
    }
}

@Preview(device = "id:Android TV (720p)")
@Composable
private fun ChannelsChannelGroupListPreview() {
    MyTvTheme {
        ChannelsChannelGroupList(channelGroupListProvider = { ChannelGroupList.EXAMPLE },
            currentChannelGroupProvider = { ChannelGroupList.EXAMPLE.first() })
    }
}
