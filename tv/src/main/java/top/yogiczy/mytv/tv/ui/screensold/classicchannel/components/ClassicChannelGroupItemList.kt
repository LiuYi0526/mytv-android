package top.yogiczy.mytv.tv.ui.screensold.classicchannel.components

import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import top.yogiczy.mytv.core.data.entities.channel.ChannelGroup
import top.yogiczy.mytv.core.data.entities.channel.ChannelGroupList
import top.yogiczy.mytv.tv.ui.material.rememberDebounceState
import top.yogiczy.mytv.tv.ui.screen.settings.settingsVM
import top.yogiczy.mytv.tv.ui.theme.MyTvTheme
import top.yogiczy.mytv.tv.ui.utils.focusOnLaunchedSaveable
import top.yogiczy.mytv.tv.ui.utils.handleKeyEvents
import top.yogiczy.mytv.tv.ui.utils.ifElse
import top.yogiczy.mytv.tv.ui.utils.saveFocusRestorer
import top.yogiczy.mytv.tv.ui.utils.saveRequestFocus
import kotlin.math.max

@Composable
fun ClassicChannelGroupItemList(
    modifier: Modifier = Modifier,
    channelGroupListProvider: () -> ChannelGroupList = { ChannelGroupList() },
    initialChannelGroupProvider: () -> ChannelGroup = { ChannelGroup() },
    onChannelGroupFocused: (ChannelGroup) -> Unit = {},
    onChannelGroupSelected: (ChannelGroup) -> Unit = {},
    onUserAction: () -> Unit = {},
) {
    val channelGroupList = channelGroupListProvider()
    val initialChannelGroup = remember { initialChannelGroupProvider() }
    val itemFocusRequesterList = remember(channelGroupList) {
        List(channelGroupList.size) { FocusRequester() }
    }

    var focusedChannelGroup by remember { mutableStateOf(initialChannelGroup) }
    val onChannelGroupFocusedDebounce = rememberDebounceState(wait = 100L) {
        onChannelGroupFocused(focusedChannelGroup)
    }

    val listState = rememberLazyListState(max(0, channelGroupList.indexOf(initialChannelGroup) - 2))
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .distinctUntilChanged()
            .collect { _ ->
                onUserAction()
                onChannelGroupFocusedDebounce.send()
            }
    }

    val coroutineScope = rememberCoroutineScope()
    val firstFocusRequester = remember { FocusRequester() }
    val lastFocusRequester = remember { FocusRequester() }
    fun scrollToFirst() {
        coroutineScope.launch {
            listState.scrollToItem(0)
            firstFocusRequester.saveRequestFocus()
        }
    }

    fun scrollToLast() {
        coroutineScope.launch {
            listState.scrollToItem(channelGroupList.lastIndex)
            lastFocusRequester.saveRequestFocus()
        }
    }

    LazyColumn(
        modifier = modifier
            .width(140.dp)
            .fillMaxHeight()
            .ifElse(
                settingsVM.uiFocusOptimize,
                Modifier.saveFocusRestorer {
                    itemFocusRequesterList.getOrElse(channelGroupList.indexOf(focusedChannelGroup)) { FocusRequester.Default }
                },
            ),
        state = listState,
        contentPadding = PaddingValues(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        itemsIndexed(channelGroupList) { index, channelGroup ->
            val isSelected by remember { derivedStateOf { channelGroup == focusedChannelGroup } }

            ClassicChannelGroupItem(
                modifier = Modifier
                    .ifElse(channelGroup == initialChannelGroup, Modifier.focusOnLaunchedSaveable())
                    .focusRequester(itemFocusRequesterList[index])
                    .ifElse(
                        index == 0,
                        Modifier
                            .focusRequester(firstFocusRequester)
                            .handleKeyEvents(onUp = { scrollToLast() })
                    )
                    .ifElse(
                        index == channelGroupList.lastIndex,
                        Modifier
                            .focusRequester(lastFocusRequester)
                            .handleKeyEvents(onDown = { scrollToFirst() })
                    ),
                channelGroupProvider = { channelGroup },
                isSelectedProvider = { isSelected },
                onFocused = {
                    focusedChannelGroup = channelGroup
                    onChannelGroupFocusedDebounce.send()
                },
                onSelected = { onChannelGroupSelected(channelGroup) },
            )
        }
    }
}

@Composable
private fun ClassicChannelGroupItem(
    modifier: Modifier = Modifier,
    channelGroupProvider: () -> ChannelGroup = { ChannelGroup() },
    isSelectedProvider: () -> Boolean = { false },
    onFocused: () -> Unit = {},
    onSelected: () -> Unit = {},
) {
    val channelGroup = channelGroupProvider()
    val isSelected = isSelectedProvider()

    var isFocused by remember { mutableStateOf(false) }

    val colorScheme = MaterialTheme.colorScheme
    val localContentColor = LocalContentColor.current
    val containerColor = remember(isFocused, isSelected) {
        if (isFocused) colorScheme.onSurface
        else if (isSelected) colorScheme.surfaceVariant.copy(alpha = 0.5f)
        else Color.Transparent
    }
    val contentColor = remember(isFocused, isSelected) {
        if (isFocused) colorScheme.surface
        else if (isSelected) colorScheme.onSurface
        else localContentColor
    }

    Box(
        modifier = modifier
            .onFocusChanged {
                isFocused = it.isFocused || it.hasFocus
                if (isFocused) onFocused()
            }
            .focusable()
            .background(containerColor, MaterialTheme.shapes.small)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .classicPanelDirections(onRight = onSelected)
            .handleKeyEvents(
                onSelect = onSelected,
            ),
    ) {
        Text(
            text = channelGroup.name,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier
                .fillMaxWidth()
                .ifElse(isFocused, Modifier.basicMarquee()),
            color = contentColor,
            style = MaterialTheme.typography.titleSmall,
        )
    }
}

@Preview
@Composable
private fun ClassicChannelGroupItemListPreview() {
    MyTvTheme {
        ClassicChannelGroupItemList(
            channelGroupListProvider = { ChannelGroupList.EXAMPLE },
            initialChannelGroupProvider = { ChannelGroupList.EXAMPLE.first() },
        )
    }
}
