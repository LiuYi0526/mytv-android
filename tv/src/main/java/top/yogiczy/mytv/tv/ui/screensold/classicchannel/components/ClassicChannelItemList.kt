package top.yogiczy.mytv.tv.ui.screensold.classicchannel.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ListItemDefaults
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.LocalTextStyle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import top.yogiczy.mytv.core.data.entities.channel.Channel
import top.yogiczy.mytv.core.data.entities.channel.ChannelGroup
import top.yogiczy.mytv.core.data.entities.channel.ChannelList
import top.yogiczy.mytv.core.data.entities.epg.EpgList
import top.yogiczy.mytv.core.data.entities.epg.EpgList.Companion.recentProgramme
import top.yogiczy.mytv.core.data.entities.epg.EpgProgramme.Companion.progress
import top.yogiczy.mytv.core.data.entities.epg.EpgProgrammeRecent
import top.yogiczy.mytv.tv.ui.material.rememberDebounceState
import top.yogiczy.mytv.tv.ui.screen.channels.components.ChannelsChannelItemLogo
import top.yogiczy.mytv.tv.ui.screen.channels.components.rememberEpgProgrammeRecent
import top.yogiczy.mytv.tv.ui.screen.settings.settingsVM
import top.yogiczy.mytv.tv.ui.theme.MyTvTheme
import top.yogiczy.mytv.tv.ui.utils.handleKeyEvents
import top.yogiczy.mytv.tv.ui.utils.ifElse
import top.yogiczy.mytv.tv.ui.utils.saveFocusRestorer
import top.yogiczy.mytv.tv.ui.utils.saveRequestFocus
import kotlin.math.max

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

    var hasFocused by rememberSaveable { mutableStateOf(!channelList.contains(initialChannel)) }
    var focusedChannel by remember(channelList) {
        mutableStateOf(
            if (hasFocused) channelList.firstOrNull() ?: Channel() else initialChannel
        )
    }
    val onChannelFocusedDebounce = rememberDebounceState(wait = 100L) {
        onChannelFocused(focusedChannel)
    }

    val listState = remember(channelGroup) {
        LazyListState(
            if (hasFocused) 0
            else max(0, channelList.indexOf(initialChannel) - 2)
        )
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .distinctUntilChanged()
            .collect { _ ->
                onUserAction()
                onChannelFocusedDebounce.send()
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
                .background(MaterialTheme.colorScheme.surface.copy(0.8f))
                .ifElse(
                    settingsVM.uiFocusOptimize,
                    Modifier.saveFocusRestorer {
                        itemFocusRequesterList.getOrElse(channelList.indexOf(focusedChannel)) {
                            FocusRequester.Default
                        }
                    },
                ),
            state = listState,
            contentPadding = PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            itemsIndexed(channelList, key = { _, channel -> channel.hashCode() }) { index, channel ->
                val isSelected by remember { derivedStateOf { channel == focusedChannel } }
                val initialFocused by remember {
                    derivedStateOf { !hasFocused && channel == initialChannel }
                }

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
                    onChannelFocused = {
                        focusedChannel = channel
                        onChannelFocusedDebounce.send()
                    },
                    recentEpgProgrammeProvider = { epgListProvider().recentProgramme(channel) },
                    showEpgProgrammeProgressProvider = showEpgProgrammeProgressProvider,
                    focusRequesterProvider = { itemFocusRequesterList[index] },
                    initialFocusedProvider = { initialFocused },
                    onInitialFocused = { hasFocused = true },
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
    onChannelFocused: () -> Unit = {},
    recentEpgProgrammeProvider: () -> EpgProgrammeRecent? = { null },
    showEpgProgrammeProgressProvider: () -> Boolean = { false },
    focusRequesterProvider: () -> FocusRequester = { FocusRequester() },
    initialFocusedProvider: () -> Boolean = { false },
    onInitialFocused: () -> Unit = {},
    isSelectedProvider: () -> Boolean = { false },
) {
    val channel = channelProvider()
    val recentEpgProgramme = rememberEpgProgrammeRecent(recentEpgProgrammeProvider)
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

    LaunchedEffect(Unit) {
        if (initialFocusedProvider()) {
            onInitialFocused()
            focusRequester.saveRequestFocus()
        }
    }

    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showChannelLogoProvider()) {
            Box(
                modifier = Modifier
                    .width(60.dp)
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
                    .defaultMinSize(minHeight = 56.dp)
                    .padding(horizontal = 12.dp, vertical = 4.dp)
                    .handleKeyEvents(
                        onSelect = onChannelSelected,
                        onLongSelect = onChannelFavoriteToggle
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CompositionLocalProvider(LocalContentColor provides contentColor) {
                    Column {
                        Text(
                            channel.name,
                            maxLines = 1,
                            modifier = Modifier.ifElse(isFocused, Modifier.basicMarquee()),
                            style = MaterialTheme.typography.titleSmall,
                        )

                        Text(
                            text = recentEpgProgramme?.now?.title ?: "",
                            maxLines = 1,
                            modifier = Modifier.ifElse(isFocused, Modifier.basicMarquee()),
                            style = MaterialTheme.typography.bodySmall.copy(
                                LocalContentColor.current.copy(
                                    0.8f
                                )
                            ),
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
                            .height(3.dp)
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
