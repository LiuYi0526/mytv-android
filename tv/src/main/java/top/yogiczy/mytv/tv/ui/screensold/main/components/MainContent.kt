package top.yogiczy.mytv.tv.ui.screensold.main.components

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import kotlinx.coroutines.delay
import top.yogiczy.mytv.core.data.entities.channel.Channel
import top.yogiczy.mytv.core.data.entities.channel.ChannelGroupList
import top.yogiczy.mytv.core.data.entities.channel.ChannelGroupList.Companion.channelList
import top.yogiczy.mytv.core.data.entities.channel.ChannelLine
import top.yogiczy.mytv.core.data.entities.channel.ChannelList
import top.yogiczy.mytv.core.data.entities.control.ControlAction
import top.yogiczy.mytv.core.data.entities.control.ControlKey
import top.yogiczy.mytv.core.data.entities.control.ControlKeyActions
import top.yogiczy.mytv.core.data.entities.epg.EpgList
import top.yogiczy.mytv.core.data.entities.epg.EpgList.Companion.displayGuide
import top.yogiczy.mytv.core.data.entities.epg.EpgList.Companion.recentProgramme
import top.yogiczy.mytv.core.data.entities.epg.EpgProgrammeReserveList
import top.yogiczy.mytv.core.data.media.PlaybackProgrammeNavigation
import top.yogiczy.mytv.core.data.media.PlaybackControllerTimeline
import top.yogiczy.mytv.core.data.utils.ChannelPlayback
import top.yogiczy.mytv.tv.ui.material.PopupContent
import top.yogiczy.mytv.tv.ui.material.Snackbar
import top.yogiczy.mytv.tv.ui.material.Visibility
import top.yogiczy.mytv.tv.ui.material.popupable
import top.yogiczy.mytv.tv.ui.screen.settings.SettingsSubCategories
import top.yogiczy.mytv.tv.ui.screen.settings.SettingsViewModel
import top.yogiczy.mytv.tv.ui.screen.settings.settingsVM
import top.yogiczy.mytv.tv.ui.screen.timingclose.LocalTimingCloseViewModel
import top.yogiczy.mytv.tv.ui.screensold.audiotracks.AudioTracksScreen
import top.yogiczy.mytv.tv.ui.screensold.channel.ChannelNumberSelectScreen
import top.yogiczy.mytv.tv.ui.screensold.channel.ChannelScreen
import top.yogiczy.mytv.tv.ui.screensold.channel.ChannelTempScreen
import top.yogiczy.mytv.tv.ui.screensold.channel.rememberChannelNumberSelectState
import top.yogiczy.mytv.tv.ui.screensold.channelline.ChannelLineScreen
import top.yogiczy.mytv.tv.ui.screensold.classicchannel.ClassicChannelScreen
import top.yogiczy.mytv.tv.ui.screensold.datetime.DatetimeScreen
import top.yogiczy.mytv.tv.ui.screensold.epg.EpgProgrammeProgressScreen
import top.yogiczy.mytv.tv.ui.screensold.epg.EpgScreen
import top.yogiczy.mytv.tv.ui.screensold.epgreverse.EpgReverseScreen
import top.yogiczy.mytv.tv.ui.screensold.quickop.QuickOpScreen
import top.yogiczy.mytv.tv.ui.screensold.quickop.QuickOpNavigationState
import top.yogiczy.mytv.tv.ui.screensold.subtitletracks.SubtitleTracksScreen
import top.yogiczy.mytv.tv.ui.screensold.videoplayer.VideoPlayerScreen
import top.yogiczy.mytv.tv.ui.screensold.videoplayer.player.VideoPlayer
import top.yogiczy.mytv.tv.ui.screensold.videoplayer.rememberVideoPlayerState
import top.yogiczy.mytv.tv.ui.screensold.videoplayercontroller.VideoPlayerControllerScreen
import top.yogiczy.mytv.tv.ui.screensold.videoplayerdiaplaymode.VideoPlayerDisplayModeScreen
import top.yogiczy.mytv.tv.ui.screensold.videotracks.VideoTracksScreen
import top.yogiczy.mytv.tv.ui.screensold.webview.WebViewScreen
import top.yogiczy.mytv.tv.ui.utils.backHandler
import top.yogiczy.mytv.tv.ui.utils.handleLiveControls

@Composable
fun MainContent(
    modifier: Modifier = Modifier,
    videoSurfaceVisible: Boolean = true,
    filteredChannelGroupListProvider: () -> ChannelGroupList = { ChannelGroupList() },
    favoriteChannelListProvider: () -> ChannelList = { ChannelList() },
    epgListProvider: () -> EpgList = { EpgList() },
    settingsViewModel: SettingsViewModel = settingsVM,
    onChannelFavoriteToggle: (Channel) -> Unit = {},
    toSettingsScreen: (SettingsSubCategories?) -> Unit = {},
    toDashboardScreen: () -> Unit = {},
    onBackPressed: () -> Unit = {},
) {
    val timingCloseViewModel = LocalTimingCloseViewModel.current
    val quickOpNavigation = remember { QuickOpNavigationState() }

    val videoPlayerState =
        rememberVideoPlayerState(defaultDisplayModeProvider = { settingsViewModel.videoPlayerDisplayMode })
    val mainContentState = rememberMainContentState(
        videoPlayerState = videoPlayerState,
        channelGroupListProvider = filteredChannelGroupListProvider,
        favoriteChannelListProvider = favoriteChannelListProvider,
        epgListProvider = epgListProvider,
    )
    val channelNumberSelectState = rememberChannelNumberSelectState {
        val idx = it.toInt() - 1
        filteredChannelGroupListProvider().channelList.getOrNull(idx)?.let { channel ->
            mainContentState.changeCurrentChannel(channel)
        }
    }

    fun openToolbarChild(key: String, open: () -> Unit) {
        quickOpNavigation.openChild(key)
        mainContentState.isQuickOpScreenVisible = false
        open()
    }

    fun closeToolbarChild(key: String, close: () -> Unit) {
        close()
        if (quickOpNavigation.returnFromChild(key)) mainContentState.isQuickOpScreenVisible = true
    }

    LaunchedEffect(timingCloseViewModel.showPicker) {
        if (!timingCloseViewModel.showPicker && quickOpNavigation.returnFromChild("timing")) {
            mainContentState.isQuickOpScreenVisible = true
        }
    }

    fun performControl(key: ControlKey, number: Int?) {
        val action = ControlKeyActions.actionFor(key, settingsViewModel.controlKeyActions, settingsViewModel.iptvChannelChangeFlip)
        if (action == ControlAction.QUICK_OP) quickOpNavigation.openToolbar() else quickOpNavigation.leaveToolbar()
        when (action) {
            ControlAction.NONE -> Unit
            ControlAction.PREVIOUS_CHANNEL -> mainContentState.changeCurrentChannelToPrev()
            ControlAction.NEXT_CHANNEL -> mainContentState.changeCurrentChannelToNext()
            ControlAction.PREVIOUS_LINE, ControlAction.NEXT_LINE -> {
                if (mainContentState.currentChannel.lineList.size > 1) {
                    val step = if (action == ControlAction.PREVIOUS_LINE) -1 else 1
                    mainContentState.changeCurrentChannel(mainContentState.currentChannel, mainContentState.currentChannelLineIdx + step)
                }
            }
            ControlAction.CHANNEL_NO_SELECT -> if (number != null && settingsViewModel.iptvChannelNoSelectEnable) channelNumberSelectState.input(number)
            ControlAction.CHANNELS -> mainContentState.isChannelScreenVisible = true
            ControlAction.QUICK_OP -> mainContentState.isQuickOpScreenVisible = true
            ControlAction.CHANNEL_SOURCE_LIST -> toSettingsScreen(SettingsSubCategories.IPTV_SOURCE)
            ControlAction.EPG_PROGRAMME_LIST -> mainContentState.isEpgScreenVisible = true
            ControlAction.CHANNEL_LINE_LIST -> mainContentState.isChannelLineScreenVisible = true
            ControlAction.VIDEO_PLAYER_DISPLAY_MODE_LIST -> mainContentState.isVideoPlayerDisplayModeScreenVisible = true
            ControlAction.VIDEO_PLAYER_FACTORY_LIST -> toSettingsScreen(SettingsSubCategories.VIDEO_PLAYER_CORE)
            ControlAction.VIDEO_PLAYER_VIDEO_TRACK_LIST -> mainContentState.isVideoTracksScreenVisible = true
            ControlAction.VIDEO_PLAYER_AUDIO_TRACK_LIST -> mainContentState.isAudioTracksScreenVisible = true
            ControlAction.VIDEO_PLAYER_SUBTITLE_TRACK_LIST -> mainContentState.isSubtitleTracksScreenVisible = true
            ControlAction.VIDEO_PLAYER_CONTROLLER -> mainContentState.isVideoPlayerControllerScreenVisible = true
            ControlAction.TIMING_CLOSE -> timingCloseViewModel.showPicker = true
        }
    }

    Box(
        modifier = modifier
            .popupable()
            .backHandler { onBackPressed() }
            .handleLiveControls(
                onControl = ::performControl,
                onQuickOp = { quickOpNavigation.openToolbar(); mainContentState.isQuickOpScreenVisible = true },
            ),
    ) {
        VideoPlayerScreen(
            state = videoPlayerState,
            surfaceVisible = videoSurfaceVisible,
            showMetadataProvider = { settingsViewModel.debugShowVideoPlayerMetadata },
        )

        Visibility({ mainContentState.currentChannelLine.url.startsWith("webview://") }) {
            WebViewScreen(
                urlProvider = { mainContentState.currentChannelLine.url },
                onVideoResolutionChanged = { width, height ->
                    videoPlayerState.metadata = videoPlayerState.metadata.copy(
                        video = (videoPlayerState.metadata.video
                            ?: VideoPlayer.Metadata.Video()).copy(
                            width = width,
                            height = height,
                        ),
                    )
                    mainContentState.isTempChannelScreenVisible = false
                },
            )
        }
    }

    Visibility({ settingsViewModel.uiShowEpgProgrammePermanentProgress && !mainContentState.isVideoPlayerControllerScreenVisible }) {
        EpgProgrammeProgressScreen(
            currentEpgProgrammeProvider = {
                mainContentState.currentPlaybackEpgProgramme ?: epgListProvider().recentProgramme(
                    mainContentState.currentChannel
                )?.now
            },
            videoPlayerCurrentPositionProvider = { videoPlayerState.currentPosition },
        )
    }

    Visibility({
        !mainContentState.isTempChannelScreenVisible
                && !mainContentState.isChannelScreenVisible
                && !mainContentState.isQuickOpScreenVisible
                && !mainContentState.isEpgScreenVisible
                && !mainContentState.isChannelLineScreenVisible
                && !mainContentState.isVideoPlayerControllerScreenVisible
                && channelNumberSelectState.channelNumber.isEmpty()
    }) {
        DatetimeScreen(
            showModeProvider = { settingsViewModel.uiTimeShowMode },
            showSeconds = settingsViewModel.uiTimeShowSeconds,
        )
    }

    ChannelNumberSelectScreen(channelNumberProvider = { channelNumberSelectState.channelNumber })

    Visibility({
        mainContentState.isTempChannelScreenVisible
                && !mainContentState.isChannelScreenVisible
                && !mainContentState.isQuickOpScreenVisible
                && !mainContentState.isEpgScreenVisible
                && !mainContentState.isChannelLineScreenVisible
                && !mainContentState.isVideoPlayerControllerScreenVisible
                && channelNumberSelectState.channelNumber.isEmpty()
    }) {
        ChannelTempScreen(
            channelProvider = { mainContentState.currentChannel },
            channelLineIdxProvider = { mainContentState.currentChannelLineIdx },
            recentEpgProgrammeProvider = {
                epgListProvider().recentProgramme(mainContentState.currentChannel, mainContentState.currentPlaybackEpgProgramme)
            },
            currentPlaybackEpgProgrammeProvider = { mainContentState.currentPlaybackEpgProgramme },
            playerMetadataProvider = { videoPlayerState.metadata },
        )
    }

    PopupContent(
        visibleProvider = { mainContentState.isEpgScreenVisible },
        onDismissRequest = { closeToolbarChild("epg") { mainContentState.isEpgScreenVisible = false } },
    ) {
        EpgScreen(
            epgProvider = {
                epgListProvider().displayGuide(mainContentState.currentChannel)
            },
            epgProgrammeReserveListProvider = {
                EpgProgrammeReserveList(settingsViewModel.epgChannelReserveList.filter {
                    it.channel == mainContentState.currentChannel.name
                })
            },
            supportPlaybackProvider = { mainContentState.supportPlayback() },
            currentPlaybackEpgProgrammeProvider = { mainContentState.currentPlaybackEpgProgramme },
            onEpgProgrammePlayback = {
                closeToolbarChild("epg") { mainContentState.isEpgScreenVisible = false }
                mainContentState.changeCurrentChannel(
                    mainContentState.currentChannel,
                    mainContentState.currentChannelLineIdx,
                    it,
                )
            },
            onEpgProgrammeReserve = { programme ->
                mainContentState.reverseEpgProgrammeOrNot(
                    mainContentState.currentChannel, programme
                )
            },
            onLive = {
                closeToolbarChild("epg") { mainContentState.isEpgScreenVisible = false }
                mainContentState.changeCurrentChannel(
                    mainContentState.currentChannel,
                    mainContentState.currentChannelLineIdx,
                )
            },
            onClose = { closeToolbarChild("epg") { mainContentState.isEpgScreenVisible = false } },
        )
    }

    PopupContent(
        visibleProvider = { mainContentState.isChannelLineScreenVisible },
        onDismissRequest = { closeToolbarChild("line") { mainContentState.isChannelLineScreenVisible = false } },
    ) {
        ChannelLineScreen(
            channelProvider = { mainContentState.currentChannel },
            currentLineProvider = { mainContentState.currentChannelLine },
            onLineSelected = {
                closeToolbarChild("line") { mainContentState.isChannelLineScreenVisible = false }
                mainContentState.changeCurrentChannel(
                    mainContentState.currentChannel,
                    mainContentState.currentChannel.lineList.indexOf(it),
                )
            },
            onClose = { closeToolbarChild("line") { mainContentState.isChannelLineScreenVisible = false } },
        )
    }

    PopupContent(
        visibleProvider = { mainContentState.isVideoPlayerControllerScreenVisible },
        onDismissRequest = { closeToolbarChild("controller") { mainContentState.isVideoPlayerControllerScreenVisible = false } },
    ) {
        val controllerNow by produceState(System.currentTimeMillis()) {
            while (true) { value = System.currentTimeMillis(); delay(1_000) }
        }
        val liveProgramme = epgListProvider().recentProgramme(mainContentState.currentChannel)?.now
        val timeline = PlaybackControllerTimeline.resolve(
            position = videoPlayerState.currentPosition,
            duration = videoPlayerState.duration,
            nativeAbsolute = videoPlayerState.positionIsAbsolute,
            replay = mainContentState.currentPlaybackEpgProgramme,
            live = liveProgramme,
            supportsReplay = ChannelPlayback.isSupported(mainContentState.currentChannelLine),
            now = controllerNow,
        )
        VideoPlayerControllerScreen(
            previousProgrammeProvider = {
                PlaybackProgrammeNavigation.adjacent(
                    epgListProvider().displayGuide(mainContentState.currentChannel,
                        mainContentState.currentPlaybackEpgProgramme?.startAt ?: System.currentTimeMillis()).programmeList,
                    mainContentState.currentPlaybackEpgProgramme ?: epgListProvider().recentProgramme(mainContentState.currentChannel)?.now,
                    -1,
                )
            },
            nextProgrammeProvider = {
                PlaybackProgrammeNavigation.adjacent(
                    epgListProvider().displayGuide(mainContentState.currentChannel,
                        mainContentState.currentPlaybackEpgProgramme?.startAt ?: System.currentTimeMillis()).programmeList,
                    mainContentState.currentPlaybackEpgProgramme ?: epgListProvider().recentProgramme(mainContentState.currentChannel)?.now,
                    1,
                )
            },
            canStartProgramme = { PlaybackProgrammeNavigation.canStart(it, mainContentState.currentChannelLine) },
            onProgrammePlayback = mainContentState::playProgrammeFromStart,
            channelProvider = { mainContentState.currentChannel },
            channelLineIdxProvider = { mainContentState.currentChannelLineIdx },
            currentPlaybackEpgProgrammeProvider = { mainContentState.currentPlaybackEpgProgramme },
            recentEpgProgrammeProvider = {
                epgListProvider().recentProgramme(mainContentState.currentChannel, mainContentState.currentPlaybackEpgProgramme)
            },
            playerMetadataProvider = { videoPlayerState.metadata },
            showChannelLogo = settingsViewModel.uiShowChannelLogo,
            onReturnToLive = {
                // Dispose the controller first so a pending preview cannot seek back into replay.
                closeToolbarChild("controller") { mainContentState.isVideoPlayerControllerScreenVisible = false }
                mainContentState.changeCurrentChannel(
                    mainContentState.currentChannel, mainContentState.currentChannelLineIdx,
                )
            },
            isVideoPlayerPlayingProvider = { videoPlayerState.playRequested },
            isVideoPlayerBufferingProvider = { videoPlayerState.isBuffering },
            videoPlayerCurrentPositionProvider = { timeline.position },
            videoPlayerPositionIsAbsoluteProvider = { timeline.absolute },
            videoPlayerDurationProvider = { timeline.range },
            videoPlayerSeekEnabledProvider = { timeline.seekEnabled },
            videoPlayerAvailableUntilProvider = { controllerNow.takeIf { timeline.absolute } },
            onVideoPlayerPlay = { videoPlayerState.play() },
            onVideoPlayerPause = { videoPlayerState.pause() },
            onVideoPlayerSeekTo = {
                mainContentState.seekTo(it, liveProgramme, absoluteTarget = timeline.absolute)
            },
            onClose = { closeToolbarChild("controller") { mainContentState.isVideoPlayerControllerScreenVisible = false } },
        )
    }

    PopupContent(
        visibleProvider = { mainContentState.isVideoPlayerDisplayModeScreenVisible },
        onDismissRequest = { closeToolbarChild("display") { mainContentState.isVideoPlayerDisplayModeScreenVisible = false } },
    ) {
        VideoPlayerDisplayModeScreen(
            currentDisplayModeProvider = { videoPlayerState.displayMode },
            onDisplayModeChanged = mainContentState::changeDisplayMode,
            onFollowGlobal = {
                mainContentState.followGlobalDisplayMode()
                Snackbar.show("此频道已跟随全局显示模式")
            },
            onApplyToGlobal = {
                closeToolbarChild("display") { mainContentState.isVideoPlayerDisplayModeScreenVisible = false }
                settingsViewModel.videoPlayerDisplayMode = videoPlayerState.displayMode
                mainContentState.followGlobalDisplayMode()
                Snackbar.show("已应用到全局")
            },
            onClose = { closeToolbarChild("display") { mainContentState.isVideoPlayerDisplayModeScreenVisible = false } },
        )
    }

    PopupContent(
        visibleProvider = { mainContentState.isVideoTracksScreenVisible },
        onDismissRequest = { closeToolbarChild("video") { mainContentState.isVideoTracksScreenVisible = false } },
    ) {
        VideoTracksScreen(
            trackListProvider = { videoPlayerState.metadata.videoTracks },
            onTrackChanged = {
                videoPlayerState.selectVideoTrack(it)
                closeToolbarChild("video") { mainContentState.isVideoTracksScreenVisible = false }
            },
            onClose = { closeToolbarChild("video") { mainContentState.isVideoTracksScreenVisible = false } },
        )
    }

    PopupContent(
        visibleProvider = { mainContentState.isAudioTracksScreenVisible },
        onDismissRequest = { closeToolbarChild("audio") { mainContentState.isAudioTracksScreenVisible = false } },
    ) {
        AudioTracksScreen(
            trackListProvider = { videoPlayerState.metadata.audioTracks },
            onTrackChanged = {
                videoPlayerState.selectAudioTrack(it)
                closeToolbarChild("audio") { mainContentState.isAudioTracksScreenVisible = false }
            },
            onClose = { closeToolbarChild("audio") { mainContentState.isAudioTracksScreenVisible = false } },
        )
    }

    PopupContent(
        visibleProvider = { mainContentState.isSubtitleTracksScreenVisible },
        onDismissRequest = { closeToolbarChild("subtitle") { mainContentState.isSubtitleTracksScreenVisible = false } },
    ) {
        SubtitleTracksScreen(
            trackListProvider = { videoPlayerState.metadata.subtitleTracks },
            onTrackChanged = {
                videoPlayerState.selectSubtitleTrack(it)
                closeToolbarChild("subtitle") { mainContentState.isSubtitleTracksScreenVisible = false }
            },
            onClose = { closeToolbarChild("subtitle") { mainContentState.isSubtitleTracksScreenVisible = false } },
        )
    }

    PopupContent(
        visibleProvider = { mainContentState.isQuickOpScreenVisible },
        onDismissRequest = { quickOpNavigation.leaveToolbar(); mainContentState.isQuickOpScreenVisible = false },
    ) {
        QuickOpScreen(
            buttonListState = quickOpNavigation.listState,
            currentChannelProvider = { mainContentState.currentChannel },
            currentChannelLineIdxProvider = { mainContentState.currentChannelLineIdx },
            currentChannelNumberProvider = {
                (filteredChannelGroupListProvider().channelList.indexOf(mainContentState.currentChannel) + 1).toString()
            },
            epgListProvider = epgListProvider,
            currentPlaybackEpgProgrammeProvider = { mainContentState.currentPlaybackEpgProgramme },
            playerDisplayModeProvider = { videoPlayerState.displayMode },
            preferSoftwareDecodeProvider = { videoPlayerState.preferSoftwareDecode },
            onToggleDecodePreference = {
                videoPlayerState.preferSoftwareDecode = !videoPlayerState.preferSoftwareDecode
            },
            videoPlayerMetadataProvider = { videoPlayerState.metadata },
            tracksReadyProvider = { videoPlayerState.tracksReady },
            initialFocusKeyProvider = { quickOpNavigation.focusKey },
            onShowEpg = {
                openToolbarChild("epg") { mainContentState.isEpgScreenVisible = true }
            },
            onShowChannelLine = {
                openToolbarChild("line") { mainContentState.isChannelLineScreenVisible = true }
            },
            onShowVideoPlayerController = {
                openToolbarChild("controller") { mainContentState.isVideoPlayerControllerScreenVisible = true }
            },
            onShowVideoPlayerDisplayMode = {
                openToolbarChild("display") { mainContentState.isVideoPlayerDisplayModeScreenVisible = true }
            },
            onShowVideoTracks = {
                openToolbarChild("video") { mainContentState.isVideoTracksScreenVisible = true }
            },
            onShowAudioTracks = {
                openToolbarChild("audio") { mainContentState.isAudioTracksScreenVisible = true }
            },
            onShowSubtitleTracks = {
                openToolbarChild("subtitle") { mainContentState.isSubtitleTracksScreenVisible = true }
            },
            onShowTimingClose = { openToolbarChild("timing") { timingCloseViewModel.showPicker = true } },
            toSettingsScreen = {
                quickOpNavigation.leaveToolbar()
                mainContentState.isQuickOpScreenVisible = false
                toSettingsScreen(it)
            },
            toDashboardScreen = {
                quickOpNavigation.leaveToolbar()
                mainContentState.isQuickOpScreenVisible = false
                toDashboardScreen()
            },
            onClose = { quickOpNavigation.leaveToolbar(); mainContentState.isQuickOpScreenVisible = false },
        )
    }

    PopupContent(
        visibleProvider = { mainContentState.isChannelScreenVisible && !settingsViewModel.uiUseClassicPanelScreen },
        onDismissRequest = { mainContentState.isChannelScreenVisible = false },
    ) {
        ChannelScreen(
            channelGroupListProvider = filteredChannelGroupListProvider,
            favoriteChannelListProvider = favoriteChannelListProvider,
            currentChannelProvider = { mainContentState.currentChannel },
            currentChannelLineIdxProvider = { mainContentState.currentChannelLineIdx },
            showChannelLogoProvider = { settingsViewModel.uiShowChannelLogo },
            onChannelSelected = {
                mainContentState.isChannelScreenVisible = false
                mainContentState.changeCurrentChannel(it)
            },
            onChannelFavoriteToggle = onChannelFavoriteToggle,
            epgListProvider = epgListProvider,
            showEpgProgrammeProgressProvider = { settingsViewModel.uiShowEpgProgrammeProgress },
            currentPlaybackEpgProgrammeProvider = { mainContentState.currentPlaybackEpgProgramme },
            videoPlayerMetadataProvider = { videoPlayerState.metadata },
            channelFavoriteEnabledProvider = { settingsViewModel.iptvChannelFavoriteEnable },
            channelFavoriteListVisibleProvider = { settingsViewModel.iptvChannelFavoriteListVisible },
            onChannelFavoriteListVisibleChange = {
                settingsViewModel.iptvChannelFavoriteListVisible = it
            },
            onClose = { mainContentState.isChannelScreenVisible = false },
        )
    }

    PopupContent(
        visibleProvider = { mainContentState.isChannelScreenVisible && settingsViewModel.uiUseClassicPanelScreen },
        onDismissRequest = { mainContentState.isChannelScreenVisible = false },
    ) {
        ClassicChannelScreen(
            channelGroupListProvider = filteredChannelGroupListProvider,
            favoriteChannelListProvider = favoriteChannelListProvider,
            currentChannelProvider = { mainContentState.currentChannel },
            currentChannelLineIdxProvider = { mainContentState.currentChannelLineIdx },
            showChannelLogoProvider = { settingsViewModel.uiShowChannelLogo },
            onChannelSelected = {
                mainContentState.isChannelScreenVisible = false
                mainContentState.changeCurrentChannel(it)
            },
            onChannelFavoriteToggle = onChannelFavoriteToggle,
            epgListProvider = epgListProvider,
            epgProgrammeReserveListProvider = {
                EpgProgrammeReserveList(settingsViewModel.epgChannelReserveList)
            },
            showEpgProgrammeProgressProvider = { settingsViewModel.uiShowEpgProgrammeProgress },
            supportPlaybackProvider = { mainContentState.supportPlayback(it, null) },
            currentPlaybackEpgProgrammeProvider = { mainContentState.currentPlaybackEpgProgramme },
            onEpgProgrammePlayback = { channel, programme ->
                mainContentState.isChannelScreenVisible = false
                mainContentState.changeCurrentChannel(channel, null, programme)
            },
            onEpgProgrammeReserve = { channel, programme ->
                mainContentState.reverseEpgProgrammeOrNot(channel, programme)
            },
            videoPlayerMetadataProvider = { videoPlayerState.metadata },
            channelFavoriteEnabledProvider = { settingsViewModel.iptvChannelFavoriteEnable },
            channelFavoriteListVisibleProvider = { settingsViewModel.iptvChannelFavoriteListVisible },
            onChannelFavoriteListVisibleChange = {
                settingsViewModel.iptvChannelFavoriteListVisible = it
            },
            onClose = { mainContentState.isChannelScreenVisible = false },
        )
    }

    EpgReverseScreen(
        epgProgrammeReserveListProvider = { settingsViewModel.epgChannelReserveList },
        onConfirmReserve = { reserve ->
            filteredChannelGroupListProvider().channelList.firstOrNull { it.name == reserve.channel }
                ?.let {
                    mainContentState.changeCurrentChannel(it)
                }
        },
        onDeleteReserve = { reserve ->
            settingsViewModel.epgChannelReserveList =
                EpgProgrammeReserveList(settingsViewModel.epgChannelReserveList - reserve)
        },
    )
}
