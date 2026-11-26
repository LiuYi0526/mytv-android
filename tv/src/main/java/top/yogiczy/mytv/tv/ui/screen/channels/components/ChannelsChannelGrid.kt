package top.yogiczy.mytv.tv.ui.screen.channels.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import top.yogiczy.mytv.tv.ui.utils.FocusReturnScope
import top.yogiczy.mytv.tv.ui.utils.screenFocus
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import top.yogiczy.mytv.core.data.entities.channel.Channel
import top.yogiczy.mytv.core.data.entities.channel.ChannelList
import top.yogiczy.mytv.core.data.entities.epg.EpgList
import top.yogiczy.mytv.core.data.entities.epg.EpgList.Companion.recentProgramme
import top.yogiczy.mytv.tv.ui.material.PaddedLazyVerticalGrid
import top.yogiczy.mytv.tv.ui.rememberChildPadding
import top.yogiczy.mytv.tv.ui.screen.components.AppScreen
import top.yogiczy.mytv.tv.ui.screen.settings.settingsVM
import top.yogiczy.mytv.tv.ui.theme.MyTvTheme
import top.yogiczy.mytv.tv.ui.theme.resolveChannelGridColumns
import top.yogiczy.mytv.tv.ui.tooling.PreviewWithLayoutGrids
import top.yogiczy.mytv.tv.ui.utils.backHandler
import top.yogiczy.mytv.tv.ui.utils.ifElse
import top.yogiczy.mytv.tv.ui.utils.saveFocusRestorer
import top.yogiczy.mytv.tv.ui.utils.saveRequestFocus

private data class FocusedFavorite(val key: String, val index: Int)

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ChannelsChannelGrid(
    modifier: Modifier = Modifier,
    channelListProvider: () -> ChannelList = { ChannelList() },
    onChannelSelected: (Channel) -> Unit = {},
    onChannelFavoriteToggle: (Channel) -> Unit = {},
    epgListProvider: () -> EpgList = { EpgList() },
    inFavoriteMode: Boolean = false,
    updateTopBarVisibility: (Boolean) -> Unit = {},
    focusFirstOnEntry: Boolean = false,
    onFirstRowUp: (() -> Unit)? = null,
    allowTwoLineName: Boolean = false,
) {
    val channelList = channelListProvider()
    val focusKeys = channelList.map { "channel/${it.name}/${it.lineList.firstOrNull()?.url.orEmpty()}" }
    val defaultFocusKey = if (focusFirstOnEntry) focusKeys.firstOrNull().orEmpty() else ""
    FocusReturnScope(defaultKey = defaultFocusKey, keys = focusKeys) { focus ->

        val coroutineScope = rememberCoroutineScope()
        val childPadding = rememberChildPadding()
        val focusManager = LocalFocusManager.current

        val gridState = rememberLazyGridState()
        val firstItemFocusRequester = remember { FocusRequester() }
        var isFirstItemFocused by remember { mutableStateOf(false) }
        var focusedFavoriteIndex by remember { mutableIntStateOf(-1) }
        var pendingRemoval by remember { mutableStateOf<FocusedFavorite?>(null) }

        // Save only a fingerprint; large playlists must not enter the saved-state Bundle.
        var previousChannels by rememberSaveable { mutableStateOf(focusKeys.hashCode()) }
        LaunchedEffect(focusKeys) {
            val removal = pendingRemoval
            pendingRemoval = null
            val restoreAfterRemoval = removal != null && removal.key !in focusKeys
            if (previousChannels != focusKeys.hashCode()) {
                previousChannels = focusKeys.hashCode()
                if (!restoreAfterRemoval) gridState.scrollToItem(0)
            }
            if (removal != null && restoreAfterRemoval) {
                focus.restore(focusKeys.getOrNull(removal.index.coerceAtMost(focusKeys.lastIndex)).orEmpty())
            }
        }
        LaunchedEffect(focusKeys, focus.target, focus.restoring, focus.leaving) {
            if (focusFirstOnEntry && focus.restoring && !focus.leaving) {
                val index = focusKeys.indexOf(focus.target)
                if (index >= 0 && gridState.layoutInfo.visibleItemsInfo.none { it.index == index }) {
                    gridState.scrollToItem(index)
                }
            }
        }

        val shouldShowTopBar by remember {
            derivedStateOf {
                gridState.firstVisibleItemIndex == 0 && gridState.firstVisibleItemScrollOffset < 10
            }
        }
        LaunchedEffect(shouldShowTopBar) {
            updateTopBarVisibility(shouldShowTopBar)
        }

        BoxWithConstraints(modifier) {
            val columnCount = resolveChannelGridColumns((maxWidth - childPadding.start - childPadding.end).value)
            PaddedLazyVerticalGrid(
                modifier = Modifier
                    .backHandler {
                        if (!isFirstItemFocused) {
                            coroutineScope.launch {
                                gridState.scrollToItem(0)
                                firstItemFocusRequester.saveRequestFocus()
                            }
                        } else {
                            focusManager.moveFocus(FocusDirection.Up)
                        }
                    }
                    .ifElse(
                        settingsVM.uiFocusOptimize && channelList.isNotEmpty() && !focus.restoring,
                        Modifier.saveFocusRestorer { firstItemFocusRequester }
                    ),
                state = gridState,
                columns = GridCells.Fixed(columnCount),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = childPadding.copy(top = 10.dp).paddingValues,
                focusOnSecondRow = true,
            ) {
                itemsIndexed(channelList) { index, channel ->
                    ChannelsChannelItem(
                        modifier = Modifier
                            .ifElse(
                                index < columnCount && onFirstRowUp != null,
                                Modifier.onPreviewKeyEvent { event ->
                                    if (event.key != Key.DirectionUp) return@onPreviewKeyEvent false
                                    if (event.type == KeyEventType.KeyDown) onFirstRowUp?.invoke()
                                    true
                                },
                            )
                            .screenFocus(focusKeys[index])
                            .ifElse(
                                inFavoriteMode,
                                Modifier.onFocusChanged {
                                    if (it.isFocused) focusedFavoriteIndex = index
                                    else if (focusedFavoriteIndex == index) focusedFavoriteIndex = -1
                                },
                            )
                            .ifElse(
                                index == 0,
                                Modifier
                                    .focusRequester(firstItemFocusRequester)
                                    .onFocusChanged { isFirstItemFocused = it.isFocused },
                            ),
                        channelProvider = { channel },
                        onChannelSelected = { focus.selected(focusKeys[index]); onChannelSelected(channel) },
                        onChannelFavoriteToggle = {
                            if (inFavoriteMode && focusedFavoriteIndex == index)
                                pendingRemoval = FocusedFavorite(focusKeys[index], index)
                            onChannelFavoriteToggle(channel)
                        },
                        recentEpgProgrammeProvider = { epgListProvider().recentProgramme(channel) },
                        allowTwoLineName = allowTwoLineName,
                    )
                }
            }
        }
    }
}

@Preview(device = "id:Android TV (720p)")
@Composable
private fun ChannelsChannelGridPreview() {
    MyTvTheme {
        AppScreen {
            ChannelsChannelGrid(
                modifier = Modifier.padding(vertical = 20.dp),
                channelListProvider = { ChannelList.EXAMPLE },
                epgListProvider = { EpgList.example(ChannelList.EXAMPLE) },
            )
        }
        PreviewWithLayoutGrids { }
    }
}
