package top.yogiczy.mytv.tv.ui.screensold.classicchannel

import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import top.yogiczy.mytv.core.data.entities.channel.Channel
import top.yogiczy.mytv.core.data.entities.channel.ChannelGroup
import top.yogiczy.mytv.core.data.entities.channel.ChannelGroupList
import top.yogiczy.mytv.core.data.entities.channel.ChannelGroupList.Companion.channelGroupIdx
import top.yogiczy.mytv.core.data.entities.channel.ChannelGroupList.Companion.channelList
import top.yogiczy.mytv.core.data.entities.channel.ChannelList
import top.yogiczy.mytv.core.data.entities.epg.EpgList
import top.yogiczy.mytv.core.data.entities.epg.EpgList.Companion.match
import top.yogiczy.mytv.core.data.entities.epg.EpgProgramme
import top.yogiczy.mytv.core.data.entities.epg.EpgProgrammeReserveList
import top.yogiczy.mytv.tv.ui.material.Visibility
import top.yogiczy.mytv.tv.ui.screensold.channel.ChannelScreenTopRight
import top.yogiczy.mytv.tv.ui.screensold.classicchannel.components.ClassicChannelGroupItemList
import top.yogiczy.mytv.tv.ui.screensold.classicchannel.components.ClassicChannelItemList
import top.yogiczy.mytv.tv.ui.screensold.classicchannel.components.ClassicEpgItemList
import top.yogiczy.mytv.tv.ui.screensold.classicchannel.components.classicPanelDirections
import top.yogiczy.mytv.tv.ui.screensold.classicchannel.components.classicPanelSwipes
import top.yogiczy.mytv.tv.ui.screensold.components.rememberScreenAutoCloseState
import top.yogiczy.mytv.tv.ui.screensold.videoplayer.player.VideoPlayer
import top.yogiczy.mytv.tv.ui.theme.MyTvTheme
import top.yogiczy.mytv.tv.ui.theme.SAFE_AREA_VERTICAL_PADDING
import top.yogiczy.mytv.tv.ui.theme.SAFE_AREA_HORIZONTAL_PADDING
import top.yogiczy.mytv.tv.ui.tooling.PreviewWithLayoutGrids
import top.yogiczy.mytv.tv.ui.utils.gridColumns
import top.yogiczy.mytv.tv.ui.utils.saveRequestFocus

@Composable
fun ClassicChannelScreen(
    modifier: Modifier = Modifier,
    channelGroupListProvider: () -> ChannelGroupList = { ChannelGroupList() },
    favoriteChannelListProvider: () -> ChannelList = { ChannelList() },
    currentChannelProvider: () -> Channel = { Channel() },
    currentChannelLineIdxProvider: () -> Int = { 0 },
    showChannelLogoProvider: () -> Boolean = { false },
    onChannelSelected: (Channel) -> Unit = {},
    onChannelFavoriteToggle: (Channel) -> Unit = {},
    epgListProvider: () -> EpgList = { EpgList() },
    epgProgrammeReserveListProvider: () -> EpgProgrammeReserveList = { EpgProgrammeReserveList() },
    showEpgProgrammeProgressProvider: () -> Boolean = { false },
    onEpgProgrammePlayback: (Channel, EpgProgramme) -> Unit = { _, _ -> },
    onEpgProgrammeReserve: (Channel, EpgProgramme) -> Unit = { _, _ -> },
    isInTimeShiftProvider: () -> Boolean = { false },
    supportPlaybackProvider: (Channel) -> Boolean = { false },
    currentPlaybackEpgProgrammeProvider: () -> EpgProgramme? = { null },
    videoPlayerMetadataProvider: () -> VideoPlayer.Metadata = { VideoPlayer.Metadata() },
    channelFavoriteEnabledProvider: () -> Boolean = { false },
    channelFavoriteListVisibleProvider: () -> Boolean = { false },
    onChannelFavoriteListVisibleChange: (Boolean) -> Unit = {},
    onClose: () -> Unit = {},
) {
    val screenAutoCloseState = rememberScreenAutoCloseState(onTimeout = onClose)
    val channelGroupList = channelGroupListProvider()
    val channelFavoriteListVisible = remember { channelFavoriteListVisibleProvider() }

    val initialGroup = remember {
        if (channelFavoriteListVisible) ClassicPanelScreenFavoriteChannelGroup
        else channelGroupList.getOrNull(channelGroupList.channelGroupIdx(currentChannelProvider()))
            ?: channelGroupList.firstOrNull() ?: ChannelGroup()
    }
    var focusedChannelGroup by remember { mutableStateOf(initialGroup) }
    var focusedChannel by remember { mutableStateOf(currentChannelProvider()) }
    var groupListVisible by remember { mutableStateOf(false) }
    var epgListVisible by remember { mutableStateOf(false) }
    val visibleChannels = if (focusedChannelGroup == ClassicPanelScreenFavoriteChannelGroup)
        favoriteChannelListProvider() else focusedChannelGroup.channelList
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val channelNameStyle = MaterialTheme.typography.titleSmall
    val panelWidth = remember(visibleChannels, textMeasurer, density, channelNameStyle) {
        // YYKM samples the first 10 names, adds room for the logo/padding, then
        // clamps the list to 360..588dp. The EPG hint occupies another 20dp.
        val nameWidth = visibleChannels.take(10).maxOfOrNull {
            textMeasurer.measure(it.name, style = channelNameStyle,
                softWrap = false, maxLines = 1).size.width
        } ?: 0
        (with(density) { nameWidth.toDp() } + 150.dp).coerceIn(360.dp, 588.dp) + 20.dp
    }

    var channelListWidth by remember { mutableIntStateOf(0) }
    // Modal details temporarily own focus. Keep the active panel independent
    // of that focus loss so its width and slide offset do not jump underneath.
    var epgPanelActive by remember { mutableStateOf(false) }
    val groupFocusRequester = remember { FocusRequester() }
    var channelFocusRequest by remember { mutableIntStateOf(0) }
    var groupFocusRequest by remember { mutableIntStateOf(0) }
    LaunchedEffect(groupListVisible, groupFocusRequest) {
        if (groupListVisible) groupFocusRequester.saveRequestFocus()
    }
    fun showGroups() {
        groupListVisible = true
        groupFocusRequest++
        screenAutoCloseState.active()
    }
    fun showEpg() {
        if (visibleChannels.isNotEmpty()) {
            groupListVisible = false
            epgListVisible = true
            screenAutoCloseState.active()
        }
    }
    fun returnToChannels() {
        groupListVisible = false
        epgListVisible = false
        epgPanelActive = false
        channelFocusRequest++
        screenAutoCloseState.active()
    }
    fun selectGroup(group: ChannelGroup) {
        if (focusedChannelGroup != group) {
            focusedChannelGroup = group
            focusedChannel = if (group == ClassicPanelScreenFavoriteChannelGroup)
                favoriteChannelListProvider().firstOrNull() ?: Channel()
            else group.channelList.firstOrNull() ?: Channel()
        }
        onChannelFavoriteListVisibleChange(group == ClassicPanelScreenFavoriteChannelGroup)
    }
    val offsetXPx by animateIntAsState(
        targetValue = if (epgListVisible && epgPanelActive) -channelListWidth else 0,
        animationSpec = tween(),
        label = "",
    )

    ClassicChannelScreenWrapper(
        modifier = modifier.offset { IntOffset(x = offsetXPx, y = 0) },
        onClose = onClose,
    ) {
        Row(Modifier.background(Color.Black.copy(alpha = 0.92f))) {
            if (groupListVisible) {
                Column(Modifier.width(170.dp).fillMaxHeight()
                    .classicPanelSwipes(onSwipeLeft = { returnToChannels() })) {
                    Text(
                        "频道分组",
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    ClassicChannelGroupItemList(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .focusRequester(groupFocusRequester)
                            .classicPanelDirections(onLeft = { returnToChannels() }),
                        channelGroupListProvider = {
                            if (channelFavoriteEnabledProvider())
                                ChannelGroupList(listOf(ClassicPanelScreenFavoriteChannelGroup) + channelGroupList)
                            else
                                channelGroupList
                        },
                        initialChannelGroupProvider = {
                            focusedChannelGroup
                        },
                        onChannelGroupFocused = {
                            selectGroup(it)
                        },
                        onChannelGroupSelected = {
                            selectGroup(it)
                            returnToChannels()
                        },
                        onUserAction = { screenAutoCloseState.active() },
                    )
                }
            }

            // YYKM 3.8.4: a group title above a channel panel of at least 380dp.
            Column(
                Modifier.width(panelWidth).fillMaxHeight()
                    .onSizeChanged { channelListWidth = it.width }
                    .classicPanelSwipes(
                        onSwipeRight = { if (epgListVisible) returnToChannels() else showGroups() },
                        onSwipeLeft = { if (groupListVisible) returnToChannels() else showEpg() },
                    )
            ) {
                Text(
                    focusedChannelGroup.name,
                    modifier = Modifier.fillMaxWidth()
                        .pointerInput(groupListVisible) {
                            detectTapGestures(onTap = {
                                if (groupListVisible) returnToChannels()
                                else showGroups()
                            })
                        }
                        .padding(horizontal = 24.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(Modifier.weight(1f)) {
                    ClassicChannelItemList(
                        modifier = Modifier
                            .weight(1f)
                            .classicPanelDirections(
                                onLeft = { if (epgListVisible) returnToChannels() else showGroups() },
                                onRight = { showEpg() },
                            ),
                        channelGroupProvider = { focusedChannelGroup },
                        channelListProvider = { visibleChannels },
                        epgListProvider = epgListProvider,
                        initialChannelProvider = currentChannelProvider,
                        onChannelSelected = onChannelSelected,
                        onChannelFavoriteToggle = onChannelFavoriteToggle,
                        isChannelFavoriteProvider = { channelFavoriteEnabledProvider() && it in favoriteChannelListProvider() },
                        focusEmptyList = !groupListVisible,
                        focusRequestKey = channelFocusRequest,
                        onChannelFocused = { channel ->
                            focusedChannel = channel
                            epgPanelActive = false
                        },
                        showEpgProgrammeProgressProvider = showEpgProgrammeProgressProvider,
                        onUserAction = { screenAutoCloseState.active() },
                        inFavoriteModeProvider = { focusedChannelGroup == ClassicPanelScreenFavoriteChannelGroup },
                        showChannelLogoProvider = showChannelLogoProvider,
                    )
                    if (!epgListVisible && visibleChannels.isNotEmpty()) {
                        ClassicPanelScreenShowEpgTip(
                            modifier = Modifier.width(20.dp),
                            onTap = { showEpg() },
                        )
                    }
                }
            }

            Visibility({ epgListVisible }) {
                ClassicEpgItemList(
                    modifier = Modifier
                        .onFocusChanged { if (it.hasFocus) epgPanelActive = true }
                        .classicPanelSwipes(onSwipeRight = { returnToChannels() })
                        .focusGroup(),
                    programmeListModifier = Modifier
                        .classicPanelDirections(onLeft = { returnToChannels() }),
                    programmeListWidth = if (epgPanelActive) 6.gridColumns() else 5.gridColumns(),
                    epgProvider = { epgListProvider().match(focusedChannel) },
                    channelNameProvider = { focusedChannel.name },
                    epgProgrammeReserveListProvider = {
                        EpgProgrammeReserveList(
                            epgProgrammeReserveListProvider().filter { it.channel == focusedChannel.name }
                        )
                    },
                    supportPlaybackProvider = { supportPlaybackProvider(focusedChannel) },
                    currentPlaybackEpgProgrammeProvider = currentPlaybackEpgProgrammeProvider,
                    onEpgProgrammePlayback = { onEpgProgrammePlayback(focusedChannel, it) },
                    onLive = { onChannelSelected(focusedChannel) },
                    onEpgProgrammeReserve = { onEpgProgrammeReserve(focusedChannel, it) },
                    onUserAction = { screenAutoCloseState.active() },
                )
            }
        }
    }

    ChannelScreenTopRight(channelNumberProvider = { currentChannelProvider().no })

}

@Composable
private fun ClassicChannelScreenWrapper(
    modifier: Modifier = Modifier,
    onClose: () -> Unit = {},
    content: @Composable () -> Unit = {},
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures(onTap = { onClose() }) }
    ) {
        Box(
            modifier = modifier
                // These panels slide sideways; they do not share one viewport.
                // Keep their measured widths before clipping and applying the offset.
                .wrapContentWidth(Alignment.Start, unbounded = true)
                .pointerInput(Unit) { detectTapGestures(onTap = { }) }
                .padding(horizontal = SAFE_AREA_HORIZONTAL_PADDING.dp, vertical = SAFE_AREA_VERTICAL_PADDING.dp)
                .clip(MaterialTheme.shapes.medium),
        ) {
            content()
        }
    }
}

@Composable
private fun ClassicPanelScreenShowEpgTip(
    modifier: Modifier = Modifier,
    onTap: () -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onTap() })
            },
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        "向右查看节目单".map {
            Text(text = it.toString(), style = MaterialTheme.typography.labelSmall)
        }
    }
}

val ClassicPanelScreenFavoriteChannelGroup = ChannelGroup(name = "我的收藏")

@Preview(device = "id:Android TV (720p)")
@Composable
private fun ClassicChannelScreenPreview() {
    MyTvTheme {
        PreviewWithLayoutGrids {
            ClassicChannelScreen(
                channelGroupListProvider = { ChannelGroupList.EXAMPLE },
                currentChannelProvider = { ChannelGroupList.EXAMPLE.first().channelList.first() },
                currentChannelLineIdxProvider = { 0 },
                epgListProvider = { EpgList.example(ChannelGroupList.EXAMPLE.channelList) },
                showEpgProgrammeProgressProvider = { true },
            )
        }
    }
}
