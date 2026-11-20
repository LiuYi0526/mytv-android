package top.yogiczy.mytv.tv.ui.screensold.quickop.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.FormatListBulleted
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.AspectRatio
import androidx.compose.material.icons.outlined.Audiotrack
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material.icons.outlined.ControlCamera
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Hd
import androidx.compose.material.icons.outlined.Sd
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SlowMotionVideo
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import top.yogiczy.mytv.core.data.entities.channel.Channel
import top.yogiczy.mytv.core.util.utils.humanizeAudioChannels
import top.yogiczy.mytv.core.util.utils.humanizeBitrate
import top.yogiczy.mytv.core.util.utils.humanizeLanguage
import top.yogiczy.mytv.tv.ui.material.LazyRow
import top.yogiczy.mytv.tv.ui.rememberChildPadding
import top.yogiczy.mytv.tv.ui.screen.settings.settingsVM
import top.yogiczy.mytv.tv.ui.screen.timingclose.LocalTimingCloseViewModel
import top.yogiczy.mytv.tv.ui.screensold.videoplayer.VideoPlayerDisplayMode
import top.yogiczy.mytv.tv.ui.screensold.videoplayer.player.VideoPlayer
import top.yogiczy.mytv.tv.ui.theme.MyTvTheme
import top.yogiczy.mytv.tv.ui.utils.Configs
import top.yogiczy.mytv.tv.ui.utils.saveRequestFocus

@Composable
fun QuickOpBtnList(
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    channelProvider: () -> Channel = { Channel.EMPTY },
    channelLineIdxProvider: () -> Int = { 0 },
    playerDisplayModeProvider: () -> VideoPlayerDisplayMode = { VideoPlayerDisplayMode.ORIGINAL },
    preferSoftwareDecodeProvider: () -> Boolean = { false },
    onToggleDecodePreference: () -> Unit = {},
    playerMetadataProvider: () -> VideoPlayer.Metadata = { VideoPlayer.Metadata() },
    tracksReadyProvider: () -> Boolean = { false },
    initialFocusKeyProvider: () -> String = { "epg" },
    onShowEpg: () -> Unit = {},
    onShowChannelLine: () -> Unit = {},
    onShowVideoPlayerController: () -> Unit = {},
    onShowVideoPlayerDisplayMode: () -> Unit = {},
    onShowVideoTracks: () -> Unit = {},
    onShowAudioTracks: () -> Unit = {},
    onShowSubtitleTracks: () -> Unit = {},
    onShowTimingClose: () -> Unit = {},
    onShowMoreSettings: () -> Unit = {},
    toDashboardScreen: () -> Unit = {},
    onUserAction: () -> Unit = {},
    onDismissRequest: () -> Unit = {},
) {
    val childPadding = rememberChildPadding()
    val playerMetadata = playerMetadataProvider()
    val tracksReady = tracksReadyProvider()
    val hasVideo = playerMetadata.videoTracks.isNotEmpty()
    val hasAudio = playerMetadata.audioTracks.isNotEmpty()
    val hasSubtitle = playerMetadata.subtitleTracks.isNotEmpty()
    // IJK can report the playing format without exposing selectable track lists.
    val knowsVideo = hasVideo || playerMetadata.video?.let { (it.width ?: 0) > 0 || !it.mimeType.isNullOrBlank() } == true
    val knowsAudio = hasAudio || playerMetadata.audio?.let { (it.channels ?: 0) > 0 || !it.mimeType.isNullOrBlank() } == true
    var focusedAction by remember { mutableStateOf("") }
    val firstFocus = remember { FocusRequester() }
    val keys = remember { listOf("epg", "line", "controller", "engine", "decode", "video", "audio", "subtitle", "display", "timing", "dashboard", "settings") }
    val initialFocusKey = remember {
        initialFocusKeyProvider().takeIf { it in keys && when (it) {
            "video" -> hasVideo
            "audio" -> hasAudio
            "subtitle" -> hasSubtitle
            else -> true
        } } ?: "epg"
    }
    val initialFocus = remember { FocusRequester() }
    var rowPlaced by remember { mutableStateOf(false) }
    var initialFocusPlaced by remember { mutableStateOf(false) }
    fun focusKey(key: String) = Modifier.onFocusChanged { if (it.hasFocus) focusedAction = key }
        .then(if (key == initialFocusKey) Modifier.focusRequester(initialFocus)
            .onGloballyPositioned { initialFocusPlaced = true } else Modifier)
    val timingCloseViewModel = LocalTimingCloseViewModel.current
    val timingCloseStatus by timingCloseViewModel.status.collectAsState()

    LaunchedEffect(listState) {
        val index = keys.indexOf(initialFocusKey)
        // Keep the restored viewport; only reveal the target if it is no longer visible.
        snapshotFlow { rowPlaced }.first { it }
        if (listState.layoutInfo.visibleItemsInfo.none { it.index == index }) {
            listState.scrollToItem(index)
        }
        snapshotFlow { initialFocusPlaced }.first { it }
        initialFocus.saveRequestFocus()
    }

    LaunchedEffect(hasVideo, hasAudio, hasSubtitle) {
        val lostTrack = when (focusedAction) {
            "video" -> !hasVideo
            "audio" -> !hasAudio
            "subtitle" -> !hasSubtitle
            else -> false
        }
        if (lostTrack) {
            listState.scrollToItem(0)
            snapshotFlow { listState.layoutInfo.visibleItemsInfo.any { it.index == 0 } }.first { it }
            firstFocus.saveRequestFocus()
        }
    }

    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.distinctUntilChanged()
            .collect { _ -> onUserAction() }
    }

    LazyRow(
        modifier = modifier.onGloballyPositioned { rowPlaced = true },
        state = listState,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(start = childPadding.start, end = childPadding.end),
        preservePaddingOnFocus = true,
        restoreFocus = false,
    ) {
        item(key = "epg") {
            QuickOpBtn(
                modifier = focusKey("epg").focusRequester(firstFocus),
                title = "节目单",
                imageVector = Icons.Outlined.Book,
                onSelect = onShowEpg,
            )
        }

        item(key = "line") {
            val lineName by remember {
                derivedStateOf {
                    channelProvider().lineList.getOrNull(channelLineIdxProvider())?.name
                        ?: "线路 %d".format(channelLineIdxProvider() + 1)
                }
            }

            QuickOpBtn(
                title = lineName,
                modifier = focusKey("line"),
                imageVector = Icons.AutoMirrored.Outlined.FormatListBulleted,
                onSelect = onShowChannelLine,
            )
        }

        item(key = "controller") {
            QuickOpBtn(
                title = "播放控制",
                modifier = focusKey("controller"),
                imageVector = Icons.Outlined.ControlCamera,
                onSelect = onShowVideoPlayerController,
            )
        }

        item(key = "engine") {
            val settingsViewModel = settingsVM

            QuickOpBtn(
                title = settingsVM.videoPlayerCore.label,
                modifier = focusKey("engine"),
                imageVector = Icons.Outlined.SlowMotionVideo,
                onSelect = {
                    settingsViewModel.videoPlayerCore = when (settingsViewModel.videoPlayerCore) {
                        Configs.VideoPlayerCore.MEDIA3 -> Configs.VideoPlayerCore.IJK
                        Configs.VideoPlayerCore.IJK -> Configs.VideoPlayerCore.MEDIA3
                    }
                },
            )
        }

        item(key = "decode") {
            DecodePreferenceButton(
                modifier = focusKey("decode"),
                preferred = preferSoftwareDecodeProvider(),
                onSelect = {
                    onUserAction()
                    onToggleDecodePreference()
                },
            )
        }

        item(key = "video") {
            val video = playerMetadata.video
            val unavailable = if (tracksReady) "无视轨" else "加载中"
            QuickOpBtn(
                modifier = focusKey("video"),
                enabled = hasVideo,
                title = if (!knowsVideo) "视轨\n$unavailable" else listOfNotNull(
                    video?.takeIf { (it.width ?: 0) > 0 && (it.height ?: 0) > 0 }
                        ?.let { "${it.width} × ${it.height}" },
                    video?.bitrate?.takeIf { it > 0 }?.humanizeBitrate(),
                ).joinToString("\n").ifBlank { "视频轨道" },
                description = "视频轨道，${if (knowsVideo) video?.shortLabel ?: "选择视频轨道" else unavailable}",
                imageVector = Icons.Outlined.Videocam,
                onSelect = onShowVideoTracks,
            )
        }

        item(key = "audio") {
            val audio = playerMetadata.audio
            val unavailable = if (tracksReady) "无音轨" else "加载中"
            QuickOpBtn(
                modifier = focusKey("audio"),
                enabled = hasAudio,
                title = if (!knowsAudio) "音轨\n$unavailable" else listOfNotNull(
                    audio?.channelsLabel?.takeIf { it.isNotBlank() }
                        ?: audio?.channels?.takeIf { it > 0 }?.humanizeAudioChannels(),
                    audio?.language?.takeIf { it.isNotBlank() }?.humanizeLanguage(),
                ).joinToString("\n").ifBlank { "音轨" },
                description = "音轨，${if (knowsAudio) audio?.shortLabel ?: "选择音轨" else unavailable}",
                imageVector = Icons.Outlined.Audiotrack,
                onSelect = onShowAudioTracks,
            )
        }

        item(key = "subtitle") {
            val unavailable = if (tracksReady) "无字幕" else "加载中"
            QuickOpBtn(
                modifier = focusKey("subtitle"),
                enabled = hasSubtitle,
                title = if (!hasSubtitle) "字幕\n$unavailable" else playerMetadata.subtitle?.shortLabel?.ifBlank { "字幕" } ?: "字幕关闭",
                description = "字幕，${if (hasSubtitle) playerMetadata.subtitle?.shortLabel ?: "关闭" else unavailable}",
                imageVector = Icons.Outlined.Subtitles,
                onSelect = onShowSubtitleTracks,
            )
        }

        item(key = "display") {
            QuickOpBtn(
                title = playerDisplayModeProvider().label,
                modifier = focusKey("display"),
                imageVector = Icons.Outlined.AspectRatio,
                onSelect = onShowVideoPlayerDisplayMode,
            )
        }

        item(key = "timing") {
            QuickOpBtn(
                title = if (timingCloseStatus.isActive) "定时关闭\n${timingCloseStatus.remainingMinutes} 分钟" else "定时关闭",
                modifier = focusKey("timing"),
                imageVector = Icons.Outlined.Alarm,
                onSelect = onShowTimingClose,
            )
        }

        item(key = "dashboard") {
            QuickOpBtn(
                title = "主界面",
                modifier = focusKey("dashboard"),
                imageVector = Icons.Outlined.Home,
                onSelect = toDashboardScreen,
            )
        }

        item(key = "settings") {
            QuickOpBtn(
                title = "设置",
                modifier = focusKey("settings"),
                imageVector = Icons.Outlined.Settings,
                onSelect = onShowMoreSettings,
            )
        }

    }
}

@Composable
internal fun DecodePreferenceButton(
    preferred: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    QuickOpBtn(
        modifier = modifier,
        title = if (preferred) "软解优先" else "硬解优先",
        imageVector = if (preferred) Icons.Outlined.Sd else Icons.Outlined.Hd,
        onSelect = onSelect,
    )
}

@Preview
@Composable
private fun QuickOpBtnListPreview() {
    MyTvTheme {
        QuickOpBtnList()
    }
}
