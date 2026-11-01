package top.yogiczy.mytv.tv.ui.screensold.classicchannel.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ListItemDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.LocalTextStyle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import top.yogiczy.mytv.core.data.entities.channel.Channel
import top.yogiczy.mytv.core.data.entities.channel.ChannelGroup
import top.yogiczy.mytv.core.data.entities.channel.ChannelList
import top.yogiczy.mytv.core.data.entities.epg.EpgList
import top.yogiczy.mytv.core.data.entities.epg.EpgList.Companion.recentProgramme
import top.yogiczy.mytv.core.data.entities.epg.EpgProgramme.Companion.progress
import top.yogiczy.mytv.core.data.entities.epg.EpgProgrammeRecent
import top.yogiczy.mytv.tv.ui.screen.channels.components.ChannelsChannelItemLogo
import top.yogiczy.mytv.tv.ui.screen.channels.components.rememberEpgProgrammeRecent
import top.yogiczy.mytv.tv.ui.screen.settings.settingsVM
import top.yogiczy.mytv.tv.ui.theme.MyTvTheme
import top.yogiczy.mytv.tv.ui.utils.handleKeyEvents
import top.yogiczy.mytv.tv.ui.utils.focusOnLaunchedSaveable
import top.yogiczy.mytv.tv.ui.utils.ifElse
import top.yogiczy.mytv.tv.ui.utils.saveFocusRestorer
import top.yogiczy.mytv.tv.ui.utils.saveRequestFocus

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ClassicChannelItemList(
    modifier: Modifier = Modifier,
    channelGroupProvider: () -> ChannelGroup = { ChannelGroup() },
    channelListProvider: () -> ChannelList = { ChannelList() },
    initialChannelProvider: () -> Channel = { Channel() },
    showChannelLogoProvider: () -> Boolean = { false },
    onChannelSelected: (Channel) -> Unit = {},
    onChannelFavoriteToggle: (Channel) -> Unit = {},
    isChannelFavoriteProvider: (Channel) -> Boolean = { false },
    focusEmptyList: Boolean = true,
    focusRequestKey: Int = 0,
    onChannelFocused: (Channel) -> Unit = { },
    epgListProvider: () -> EpgList = { EpgList() },
    showEpgProgrammeProgressProvider: () -> Boolean = { false },
    inFavoriteModeProvider: () -> Boolean = { false },
    onUserAction: () -> Unit = {},
) {
    val focusManager = LocalFocusManager.current
    val channelGroup = channelGroupProvider()
    val channelList = channelListProvider()
    val initialChannel = initialChannelProvider()
    val itemFocusRequesterList =
        remember(channelList) { List(channelList.size) { FocusRequester() } }
    val emptyFocusRequester = remember { FocusRequester() }

    val initialFocusChannel = remember {
        initialChannel.takeIf { it in channelList } ?: channelList.firstOrNull()
    }
    var hasFocused by rememberSaveable { mutableStateOf(channelList.isEmpty()) }
    var focusedChannel by remember(channelList) {
        mutableStateOf(
            if (hasFocused) channelList.firstOrNull() ?: Channel() else initialFocusChannel ?: Channel()
        )
    }

    val listState = remember(channelGroup) {
        LazyListState(
            if (hasFocused) 0
            else channelList.indexOf(initialFocusChannel).coerceAtLeast(0)
        )
    }
    var initiallyPositioned by remember(listState) { mutableStateOf(hasFocused) }
    LaunchedEffect(listState) {
        if (!initiallyPositioned) {
            val initialIndex = channelList.indexOf(initialFocusChannel)
            if (initialIndex >= 0) {
                // Measure the actual row before revealing it. Focusing a row placed at
                // index - 2 used to start a second, visible animation toward the center.
                val layout = snapshotFlow { listState.layoutInfo }.first { info ->
                    info.viewportEndOffset > info.viewportStartOffset &&
                        info.visibleItemsInfo.any { it.index == initialIndex }
                }
                val item = layout.visibleItemsInfo.first { it.index == initialIndex }
                val centeredOffset =
                    (layout.viewportStartOffset + layout.viewportEndOffset - item.size) / 2f
                listState.scrollBy(item.offset - centeredOffset)
                // Let the popup finish attaching/restoring its own focus before
                // requesting the row, including a one-item list that cannot scroll.
                withFrameNanos { }
                hasFocused = true
                itemFocusRequesterList[initialIndex].saveRequestFocus()
            }
            initiallyPositioned = true
        }
    }
    LaunchedEffect(listState, focusRequestKey, focusEmptyList) {
        if (focusRequestKey <= 0 || !focusEmptyList) return@LaunchedEffect
        // A panel container may have several focusable descendants. Restore the
        // exact channel after a group/EPG transition, once its lazy item is attached.
        val index = channelList.indexOf(focusedChannel).coerceAtLeast(0)
        if (listState.layoutInfo.visibleItemsInfo.none { it.index == index }) {
            listState.scrollToItem(index)
        }
        snapshotFlow { listState.layoutInfo.visibleItemsInfo }
            .first { items -> items.any { it.index == index } }
        if (channelList.isEmpty()) emptyFocusRequester.saveRequestFocus()
        else itemFocusRequesterList[index].saveRequestFocus()
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .distinctUntilChanged()
            .collect { _ ->
                onUserAction()
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
            listState.scrollToItem(channelList.lastIndex)
            lastFocusRequester.saveRequestFocus()
        }
    }

    CompositionLocalProvider(LocalBringIntoViewSpec provides CenteredChannelScrollSpec) {
        LazyColumn(
            modifier = modifier
                .fillMaxHeight()
                .width(if (showChannelLogoProvider()) 280.dp else 220.dp)
                .drawWithContent { if (initiallyPositioned) drawContent() }
                .ifElse(
                    settingsVM.uiFocusOptimize,
                    Modifier.saveFocusRestorer {
                        itemFocusRequesterList.getOrElse(channelList.indexOf(focusedChannel)) {
                            FocusRequester.Default
                        }
                    },
                ),
            state = listState,
            contentPadding = PaddingValues(start = 24.dp, end = 0.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (channelList.isEmpty()) {
                item {
                    Text(
                        "暂无频道",
                        modifier = Modifier.fillMaxWidth()
                            .focusRequester(emptyFocusRequester)
                            .ifElse(focusEmptyList, Modifier.focusOnLaunchedSaveable())
                            .focusable()
                            .padding(12.dp),
                    )
                }
            }
            itemsIndexed(channelList, key = { _, channel -> channel.hashCode() }) { index, channel ->
                val isSelected by remember { derivedStateOf { channel == focusedChannel } }

                ClassicChannelItem(
                    modifier = Modifier
                        .ifElse(
                            index == 0,
                            Modifier
                                .focusRequester(firstFocusRequester)
                                .handleKeyEvents(onUp = { scrollToLast() })
                        )
                        .ifElse(
                            index == channelList.lastIndex,
                            Modifier
                                .focusRequester(lastFocusRequester)
                                .handleKeyEvents(onDown = { scrollToFirst() })
                        ),
                    channelProvider = { channel },
                    onChannelSelected = { onChannelSelected(channel) },
                    onChannelFavoriteToggle = {
                        if (inFavoriteModeProvider()) {
                            if (channelList.size == 1) {
                                focusManager.moveFocus(FocusDirection.Left)
                            } else if (channelList.first() == channel) {
                                focusManager.moveFocus(FocusDirection.Down)
                            } else if (channelList.last() == channel) {
                                focusManager.moveFocus(FocusDirection.Up)
                            } else {
                                focusManager.moveFocus(FocusDirection.Down)
                            }
                        }
                        onChannelFavoriteToggle(channel)
                    },
                    isFavoriteProvider = { isChannelFavoriteProvider(channel) },
                    onChannelFocused = {
                        focusedChannel = channel
                        onChannelFocused(channel)
                    },
                    recentEpgProgrammeProvider = { epgListProvider().recentProgramme(channel) },
                    showEpgProgrammeProgressProvider = showEpgProgrammeProgressProvider,
                    focusRequesterProvider = { itemFocusRequesterList[index] },
                    isSelectedProvider = { isSelected },
                    showChannelLogoProvider = showChannelLogoProvider,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
private object CenteredChannelScrollSpec : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        // LazyColumn clamps at the first/last item; touch scrolling does not request focus scrolling.
        return offset - (containerSize - size.coerceAtMost(containerSize)) / 2f
    }
}

@Composable
private fun ClassicChannelItem(
    modifier: Modifier = Modifier,
    channelProvider: () -> Channel = { Channel() },
    showChannelLogoProvider: () -> Boolean = { false },
    onChannelSelected: () -> Unit = {},
    onChannelFavoriteToggle: () -> Unit = {},
    isFavoriteProvider: () -> Boolean = { false },
    onChannelFocused: () -> Unit = {},
    recentEpgProgrammeProvider: () -> EpgProgrammeRecent? = { null },
    showEpgProgrammeProgressProvider: () -> Boolean = { false },
    focusRequesterProvider: () -> FocusRequester = { FocusRequester() },
    isSelectedProvider: () -> Boolean = { false },
) {
    val channel = channelProvider()
    val recentEpgProgramme = rememberEpgProgrammeRecent(recentEpgProgrammeProvider)
    val currentProgramme = recentEpgProgramme?.now
    val hasCurrentProgramme = currentProgramme != null
    val showEpgProgrammeProgress = showEpgProgrammeProgressProvider()
    val focusRequester = focusRequesterProvider()
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

    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showChannelLogoProvider()) {
            Box(
                modifier = Modifier
                    .width(56.dp)
                    .fillMaxHeight()
            ) {
                CompositionLocalProvider(
                    LocalTextStyle provides MaterialTheme.typography.titleLarge
                ) {
                    ChannelsChannelItemLogo(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .aspectRatio(16 / 9f),
                        channelProvider = { channel },
                    ) {
                        Box(
                            modifier = modifier
                                .fillMaxSize()
                                .background(
                                    MaterialTheme.colorScheme.onSurface.copy(0.1f),
                                    MaterialTheme.shapes.small
                                ),
                        ) {
                            Text(
                                channel.no,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .padding(vertical = 4.dp),
                            )
                        }
                    }
                }
            }
        }

        Box(modifier = modifier.clip(ListItemDefaults.shape().shape)) {
            Row(
                modifier = Modifier
                    .focusRequester(focusRequester)
                    .onFocusChanged {
                        isFocused = it.isFocused || it.hasFocus
                        if (isFocused) onChannelFocused()
                    }
                    .focusable()
                    .fillMaxWidth()
                    .background(containerColor, MaterialTheme.shapes.small)
                    .defaultMinSize(minHeight = 48.dp)
                    .padding(horizontal = 12.dp, vertical = 4.dp)
                    .handleKeyEvents(
                        onSelect = onChannelSelected,
                        onLongSelect = onChannelFavoriteToggle
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CompositionLocalProvider(LocalContentColor provides contentColor) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            channel.name,
                            maxLines = if (hasCurrentProgramme) 1 else 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.ifElse(isFocused && hasCurrentProgramme, Modifier.basicMarquee()),
                            style = MaterialTheme.typography.titleSmall,
                        )

                        if (currentProgramme != null) Text(
                            text = currentProgramme.title,
                            maxLines = 1,
                            modifier = Modifier.ifElse(isFocused, Modifier.basicMarquee()),
                            style = MaterialTheme.typography.bodySmall.copy(
                                LocalContentColor.current.copy(
                                    0.8f
                                )
                            ),
                        )
                    }
                    if (isFavoriteProvider()) {
                        Icon(
                            Icons.Default.Star,
                            contentDescription = "已收藏",
                            tint = Color(0xFFFFB300),
                            modifier = Modifier.width(18.dp).height(18.dp),
                        )
                    }
                }
            }

            if (showEpgProgrammeProgress) {
                recentEpgProgramme?.now?.let { nnNowEpgProgramme ->
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth(nnNowEpgProgramme.progress())
                            .height(2.dp)
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f)),
                    )
                }
            }
        }
    }
}

@Preview(device = "id:Android TV (720p)")
@Composable
private fun ClassicChannelItemListPreview() {
    MyTvTheme {
        Row {
            ClassicChannelItemList(
                channelListProvider = { ChannelList.EXAMPLE },
                initialChannelProvider = { ChannelList.EXAMPLE.first() },
                epgListProvider = { EpgList.example(ChannelList.EXAMPLE) },
                showEpgProgrammeProgressProvider = { true },
            )
        }
    }
}

@Preview(device = "id:Android TV (720p)")
@Composable
private fun ClassicChannelItemListWithChannelLogoPreview() {
    MyTvTheme {
        Row {
            ClassicChannelItemList(
                channelListProvider = { ChannelList.EXAMPLE },
                initialChannelProvider = { ChannelList.EXAMPLE.first() },
                epgListProvider = { EpgList.example(ChannelList.EXAMPLE) },
                showEpgProgrammeProgressProvider = { true },
                showChannelLogoProvider = { true },
            )
        }
    }
}
