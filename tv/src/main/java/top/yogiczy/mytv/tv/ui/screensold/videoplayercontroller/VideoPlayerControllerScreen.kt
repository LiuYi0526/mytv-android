package top.yogiczy.mytv.tv.ui.screensold.videoplayercontroller

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.tv.material3.Icon
import top.yogiczy.mytv.core.data.entities.channel.Channel
import top.yogiczy.mytv.core.data.entities.epg.EpgProgramme
import top.yogiczy.mytv.core.data.entities.epg.EpgProgrammeRecent
import top.yogiczy.mytv.tv.ui.rememberChildPadding
import top.yogiczy.mytv.tv.ui.screen.live.channels.components.LiveChannelsChannelInfo
import top.yogiczy.mytv.tv.ui.screensold.videoplayer.player.VideoPlayer
import top.yogiczy.mytv.tv.ui.screensold.videoplayercontroller.components.VideoPlayerControllerBtn
import top.yogiczy.mytv.tv.ui.screensold.videoplayercontroller.components.VideoPlayerControllerPositionCtrl
import top.yogiczy.mytv.tv.ui.screensold.videoplayercontroller.components.VideoPlayerControllerStateCtrl
import top.yogiczy.mytv.tv.ui.theme.MyTvTheme
import top.yogiczy.mytv.tv.ui.utils.backHandler
import top.yogiczy.mytv.tv.ui.utils.clickableNoIndication
import top.yogiczy.mytv.tv.ui.utils.focusOnLaunchedSaveable

@Composable
fun VideoPlayerControllerScreen(
    modifier: Modifier = Modifier,
    isVideoPlayerPlayingProvider: () -> Boolean = { false },
    isVideoPlayerBufferingProvider: () -> Boolean = { false },
    videoPlayerCurrentPositionProvider: () -> Long = { 0L },
    videoPlayerDurationProvider: () -> Pair<Long, Long> = { 0L to 0L },
    videoPlayerPositionIsAbsoluteProvider: () -> Boolean = { false },
    videoPlayerSeekEnabledProvider: () -> Boolean = { true },
    videoPlayerAvailableUntilProvider: () -> Long? = { null },
    channelProvider: () -> Channel = { Channel() },
    channelLineIdxProvider: () -> Int = { 0 },
    recentEpgProgrammeProvider: () -> EpgProgrammeRecent? = { null },
    currentPlaybackEpgProgrammeProvider: () -> EpgProgramme? = { null },
    previousProgrammeProvider: () -> EpgProgramme? = { null },
    nextProgrammeProvider: () -> EpgProgramme? = { null },
    canStartProgramme: (EpgProgramme) -> Boolean = { false },
    onProgrammePlayback: (EpgProgramme) -> Unit = {},
    playerMetadataProvider: () -> VideoPlayer.Metadata = { VideoPlayer.Metadata() },
    showChannelLogo: Boolean = true,
    onVideoPlayerPlay: () -> Unit = {},
    onVideoPlayerPause: () -> Unit = {},
    onVideoPlayerSeekTo: (Long) -> Unit = {},
    onReturnToLive: () -> Unit = {},
    onClose: () -> Unit = {},
) {
    val duration = videoPlayerDurationProvider()
    val hasProgress = duration.second > duration.first && videoPlayerSeekEnabledProvider()
    val replay = currentPlaybackEpgProgrammeProvider() != null
    val position = videoPlayerCurrentPositionProvider()
    val programme = currentPlaybackEpgProgrammeProvider() ?: recentEpgProgrammeProvider()?.now
    val previous = previousProgrammeProvider()
    val next = nextProgrammeProvider()
    val padding = rememberChildPadding()
    val progressFocus = remember { FocusRequester() }
    fun playProgramme(target: EpgProgramme?, focusProgress: Boolean = true) {
        if (target == null || !canStartProgramme(target)) return
        if (focusProgress) progressFocus.requestFocus()
        onProgrammePlayback(target)
    }

    CompositionLocalProvider(LocalContentColor provides Color.White) {
        Box(modifier.fillMaxSize().backHandler { onClose() }.clickableNoIndication { onClose() }) {
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(0.72f), Color.Black.copy(0.94f))))
                    .clickableNoIndication { }
                    .padding(start = padding.start, end = padding.end, top = 32.dp, bottom = padding.bottom),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LiveChannelsChannelInfo(
                    channelProvider = channelProvider,
                    channelLineIdxProvider = channelLineIdxProvider,
                    recentEpgProgrammeProvider = recentEpgProgrammeProvider,
                    currentPlaybackEpgProgrammeProvider = currentPlaybackEpgProgrammeProvider,
                    playerMetadataProvider = playerMetadataProvider,
                    showChannelLogo = showChannelLogo,
                    showProgrammeDescription = true,
                    showNetSpeed = false,
                )
                VideoPlayerControllerPositionCtrl(
                    modifier = Modifier.fillMaxWidth().focusRequester(progressFocus),
                    currentPositionProvider = { position },
                    durationProvider = videoPlayerDurationProvider,
                    positionIsAbsoluteProvider = videoPlayerPositionIsAbsoluteProvider,
                    seekEnabledProvider = videoPlayerSeekEnabledProvider,
                    availableUntilProvider = videoPlayerAvailableUntilProvider,
                    onSeekTo = onVideoPlayerSeekTo,
                    toolbar = { positionLabel, adjust, cancelPreview ->
                        PlaybackControllerFooter(
                            positionLabel = positionLabel,
                            transport = {
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    VideoPlayerControllerBtn(
                                        modifier = Modifier.semantics { contentDescription = "上一节目" },
                                        imageVector = Icons.Default.SkipPrevious,
                                        enabled = previous?.let(canStartProgramme) == true,
                                        onSelect = { cancelPreview(); playProgramme(previous, focusProgress = false) },
                                    )
                                    VideoPlayerControllerBtn(
                                        modifier = Modifier.semantics { contentDescription = "后退10秒" },
                                        imageVector = Icons.Default.Replay10,
                                        enabled = hasProgress,
                                        onSelect = { adjust(-10_000L) },
                                    )
                                    VideoPlayerControllerStateCtrl(
                                        modifier = Modifier.focusOnLaunchedSaveable(),
                                        isPlayingProvider = isVideoPlayerPlayingProvider,
                                        isBufferingProvider = isVideoPlayerBufferingProvider,
                                        onPlay = onVideoPlayerPlay,
                                        onPause = onVideoPlayerPause,
                                    )
                                    VideoPlayerControllerBtn(
                                        modifier = Modifier.semantics { contentDescription = "前进10秒" },
                                        imageVector = Icons.Default.Forward10,
                                        enabled = hasProgress,
                                        onSelect = { adjust(10_000L) },
                                    )
                                    VideoPlayerControllerBtn(
                                        modifier = Modifier.semantics { contentDescription = "下一节目" },
                                        imageVector = Icons.Default.SkipNext,
                                        enabled = next?.let(canStartProgramme) == true,
                                        onSelect = { cancelPreview(); playProgramme(next, focusProgress = false) },
                                    )
                                }
                            },
                            tools = {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    VideoPlayerControllerBtn(
                                        modifier = Modifier.size(80.dp, 40.dp).semantics { contentDescription = "从头观看" },
                                        enabled = programme?.let(canStartProgramme) == true,
                                        onSelect = { cancelPreview(); playProgramme(programme) },
                                    ) {
                                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Default.Replay, contentDescription = null, modifier = Modifier.size(20.dp))
                                            Text("从头", style = MaterialTheme.typography.labelLarge)
                                        }
                                    }
                                    VideoPlayerControllerBtn(
                                        modifier = Modifier.size(80.dp, 40.dp).semantics { contentDescription = "回到直播" },
                                        enabled = replay,
                                        onSelect = { cancelPreview(); onReturnToLive() },
                                    ) {
                                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Default.LiveTv, contentDescription = null, modifier = Modifier.size(20.dp))
                                            Text("直播", style = MaterialTheme.typography.labelLarge)
                                        }
                                    }
                                }
                            },
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun PlaybackControllerFooter(
    positionLabel: String,
    transport: @Composable () -> Unit,
    tools: @Composable () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 2.dp)) {
        val availableWidth = maxWidth
        if (availableWidth < 760.dp) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (availableWidth < 500.dp) {
                    Text(positionLabel, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { transport() }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { tools() }
                } else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(positionLabel, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                        tools()
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { transport() }
                }
            }
        } else {
            Box(Modifier.fillMaxWidth()) {
                Text(positionLabel, Modifier.align(Alignment.CenterStart).widthIn(max = 180.dp),
                    style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                Box(Modifier.align(Alignment.Center)) { transport() }
                Box(Modifier.align(Alignment.CenterEnd)) { tools() }
            }
        }
    }
}

@Preview(device = "id:Android TV (720p)")
@Composable
private fun VideoPlayerControllerScreenPreview() {
    MyTvTheme {
        VideoPlayerControllerScreen(
            channelProvider = { Channel.EXAMPLE },
            recentEpgProgrammeProvider = { EpgProgrammeRecent.EXAMPLE },
            videoPlayerCurrentPositionProvider = { System.currentTimeMillis() },
            videoPlayerPositionIsAbsoluteProvider = { true },
            videoPlayerDurationProvider = { System.currentTimeMillis() - 600_000 to System.currentTimeMillis() + 600_000 },
        )
    }
}
