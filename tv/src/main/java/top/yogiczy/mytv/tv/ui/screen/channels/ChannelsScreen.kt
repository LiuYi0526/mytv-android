package top.yogiczy.mytv.tv.ui.screen.channels

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import kotlinx.coroutines.launch
import top.yogiczy.mytv.core.data.entities.channel.Channel
import top.yogiczy.mytv.core.data.entities.channel.ChannelGroup
import top.yogiczy.mytv.core.data.entities.channel.ChannelGroupList
import top.yogiczy.mytv.core.data.entities.channel.ChannelGroupList.Companion.channelList
import top.yogiczy.mytv.core.data.entities.epg.EpgList
import top.yogiczy.mytv.tv.ui.screen.channels.components.ChannelsChannelGrid
import top.yogiczy.mytv.tv.ui.screen.channels.components.ChannelsChannelGroupList
import top.yogiczy.mytv.tv.ui.screen.components.AppScreen
import top.yogiczy.mytv.tv.ui.theme.MyTvTheme
import top.yogiczy.mytv.tv.ui.utils.saveRequestFocus

@Composable
fun ChannelsScreen(
    modifier: Modifier = Modifier,
    channelGroupListProvider: () -> ChannelGroupList = { ChannelGroupList() },
    onChannelSelected: (Channel) -> Unit = {},
    onChannelFavoriteToggle: (Channel) -> Unit = {},
    epgListProvider: () -> EpgList = { EpgList() },
    onBackPressed: () -> Unit = {},
    allowTwoLineName: Boolean = false,
) {
    GroupedChannelsScreen(
        modifier = modifier,
        title = "全部频道",
        channelGroups = channelGroupListProvider(),
        onChannelSelected = onChannelSelected,
        onChannelFavoriteToggle = onChannelFavoriteToggle,
        epgListProvider = epgListProvider,
        onBackPressed = onBackPressed,
        allowTwoLineName = allowTwoLineName,
    )
}

@Composable
internal fun GroupedChannelsScreen(
    modifier: Modifier,
    title: String,
    channelGroups: ChannelGroupList,
    onChannelSelected: (Channel) -> Unit,
    onChannelFavoriteToggle: (Channel) -> Unit,
    epgListProvider: () -> EpgList,
    onBackPressed: () -> Unit,
    headerExtra: @Composable (() -> Unit)? = null,
    inFavoriteMode: Boolean = false,
    allowTwoLineName: Boolean = false,
) {
    var selectedGroupKey by rememberSaveable { mutableStateOf<String?>(null) }
    fun groupKey(index: Int): String =
        if (inFavoriteMode && index > 0) "source/${channelGroups[index].name}" else index.toString()
    val selectedIndex = selectedGroupKey?.let { key -> channelGroups.indices.firstOrNull { groupKey(it) == key } }
    val currentIndex = selectedIndex ?: 0
    val currentChannelGroup = channelGroups.getOrElse(currentIndex) { ChannelGroup() }
    LaunchedEffect(selectedIndex) {
        if (selectedIndex == null) selectedGroupKey = null
    }
    val hasChannels = currentChannelGroup.channelList.isNotEmpty()
    val hasGroupBar = channelGroups.size > 1
    val groupListState = rememberLazyListState()
    val selectedGroupFocusRequester = remember { FocusRequester() }
    val backFocusRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()

    fun focusCurrentGroup() {
        if (!selectedGroupFocusRequester.saveRequestFocus().getOrDefault(false)) {
            scope.launch {
                groupListState.scrollToItem(currentIndex)
                withFrameNanos { }
                selectedGroupFocusRequester.saveRequestFocus()
            }
        }
    }

    AppScreen(
        modifier = modifier,
        header = { Text(title) },
        headerExtra = headerExtra,
        canBack = true,
        enableTopBarHidden = true,
        focusBackOnEntry = !hasChannels && !hasGroupBar,
        backButtonModifier = Modifier.focusRequester(backFocusRequester).onPreviewKeyEvent { event ->
            if (hasGroupBar && event.key == Key.DirectionDown && event.type == KeyEventType.KeyDown) {
                focusCurrentGroup()
                true
            } else false
        },
        onBackPressed = onBackPressed,
    ) { updateTopBarVisibility ->
        Column(
            modifier = Modifier.padding(top = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ChannelsChannelGroupList(
                modifier = Modifier.onPreviewKeyEvent { event ->
                    event.key == Key.DirectionUp && event.type == KeyEventType.KeyDown &&
                        backFocusRequester.saveRequestFocus().getOrDefault(false)
                },
                channelGroupListProvider = { channelGroups },
                state = groupListState,
                selectedGroupFocusRequester = selectedGroupFocusRequester,
                selectOnFocus = true,
                focusSelectedOnEntry = !hasChannels,
                currentChannelGroupProvider = { currentChannelGroup },
                onChannelGroupSelected = {
                    selectedGroupKey = groupKey(channelGroups.indexOf(it))
                },
            )

            ChannelsChannelGrid(
                channelListProvider = { currentChannelGroup.channelList },
                onChannelSelected = onChannelSelected,
                onChannelFavoriteToggle = onChannelFavoriteToggle,
                epgListProvider = epgListProvider,
                inFavoriteMode = inFavoriteMode,
                updateTopBarVisibility = updateTopBarVisibility,
                focusFirstOnEntry = true,
                onFirstRowUp = if (hasGroupBar) ::focusCurrentGroup else null,
                allowTwoLineName = allowTwoLineName,
            )
        }
    }
}

@Preview(device = "id:Android TV (720p)")
@Composable
private fun ChannelsScreenPreview() {
    MyTvTheme {
        ChannelsScreen(
            channelGroupListProvider = { ChannelGroupList.EXAMPLE },
            epgListProvider = { EpgList.example(ChannelGroupList.EXAMPLE.channelList) },
        )
    }
}

@Preview(device = "id:Android TV (720p)")
@Composable
private fun ChannelsScreenEmptyPreview() {
    MyTvTheme {
        ChannelsScreen()
    }
}
