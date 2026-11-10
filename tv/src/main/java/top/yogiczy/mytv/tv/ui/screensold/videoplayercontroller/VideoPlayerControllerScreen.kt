package top.yogiczy.mytv.tv.ui.screensold.videoplayercontroller

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
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
import top.yogiczy.mytv.tv.ui.screen.channels.components.ChannelsChannelItemLogo
import top.yogiczy.mytv.tv.ui.screen.live.channels.components.LiveChannelsChannelInfoTags
import top.yogiczy.mytv.tv.ui.screensold.videoplayer.player.VideoPlayer
import top.yogiczy.mytv.tv.ui.screensold.videoplayercontroller.components.VideoPlayerControllerBtn
import top.yogiczy.mytv.tv.ui.screensold.videoplayercontroller.components.VideoPlayerControllerPositionCtrl
import top.yogiczy.mytv.tv.ui.screensold.videoplayercontroller.components.VideoPlayerControllerStateCtrl
import top.yogiczy.mytv.tv.ui.theme.MyTvTheme
import top.yogiczy.mytv.tv.ui.utils.backHandler
import top.yogiczy.mytv.tv.ui.utils.clickableNoIndication
import top.yogiczy.mytv.tv.ui.utils.focusOnLaunchedSaveable
import java.text.SimpleDateFormat
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VideoPlayerControllerScreen(
    modifier: Modifier = Modifier,
    isVideoPlayerPlayingProvider: () -> Boolean = { false },
    isVideoPlayerBufferingProvider: () -> Boolean = { false },
    videoPlayerCurrentPositionProvider: () -> Long = { 0L },
    videoPlayerDurationProvider: () -> Pair<Long, Long> = { 0L to 0L },
    videoPlayerPositionIsAbsoluteProvider: () -> Boolean = { false },
    videoPlayerSeekEnabledProvider: () -> Boolean = { true },
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
    var more by remember { mutableStateOf(false) }
    fun dismissMore() { more = false; progressFocus.requestFocus() }
    fun playProgramme(target: EpgProgramme?, focusProgress: Boolean = true) {
        if (target == null || !canStartProgramme(target)) return
        more = false
        if (focusProgress) progressFocus.requestFocus()
        onProgrammePlayback(target)
    }

    CompositionLocalProvider(LocalContentColor provides Color.White) {
        Box(modifier.fillMaxSize().backHandler {
            if (more) dismissMore() else onClose()
        }.clickableNoIndication { onClose() }) {
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(0.72f), Color.Black.copy(0.94f))))
                    .clickableNoIndication { }
                    .padding(start = padding.start, end = padding.end, top = 32.dp, bottom = padding.bottom),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PlaybackControllerInfo(
                    channelProvider, channelLineIdxProvider, recentEpgProgrammeProvider,
                    currentPlaybackEpgProgrammeProvider, playerMetadataProvider, showChannelLogo,
                )
                VideoPlayerControllerPositionCtrl(
                    modifier = Modifier.fillMaxWidth().focusRequester(progressFocus).then(
                        if (hasProgress) Modifier.focusOnLaunchedSaveable() else Modifier
                    ),
                    currentPositionProvider = { position },
                    durationProvider = videoPlayerDurationProvider,
                    positionIsAbsoluteProvider = videoPlayerPositionIsAbsoluteProvider,
                    seekEnabledProvider = videoPlayerSeekEnabledProvider,
                    onSeekTo = onVideoPlayerSeekTo,
                    toolbar = { positionLabel, adjust, cancelPreview ->
                        if (more) {
                            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                                listOf(-600_000L to "后退10分钟", -60_000L to "后退1分钟",
                                    60_000L to "前进1分钟", 600_000L to "前进10分钟").forEachIndexed { index, (delta, label) ->
                                    VideoPlayerControllerBtn(
                                        modifier = Modifier.size(96.dp, 44.dp)
                                            .then(if (index == 0) Modifier.focusOnLaunchedSaveable() else Modifier)
                                            .semantics { contentDescription = label },
                                        enabled = hasProgress,
                                        onSelect = { adjust(delta); dismissMore() },
                                    ) { Text(label, style = MaterialTheme.typography.labelLarge) }
                                }
                            }
                        }
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
                                        modifier = if (hasProgress) Modifier else Modifier.focusOnLaunchedSaveable(),
                                        isPlayingProvider = isVideoPlayerPlayingProvider,
                                        isBufferingProvider = isVideoPlayerBufferingProvider,
                                        onPlay = onVideoPlayerPlay,
                                        onPause = onVideoPlayerPause,
                                        prominent = true,
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
                                    VideoPlayerControllerBtn(
                                        modifier = Modifier.semantics { contentDescription = "更多定位" },
                                        imageVector = Icons.Default.MoreHoriz,
                                        enabled = hasProgress,
                                        onSelect = { if (more) dismissMore() else more = true },
                                    )
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlaybackControllerInfo(
    channelProvider: () -> Channel,
    channelLineIdxProvider: () -> Int,
    recentProvider: () -> EpgProgrammeRecent?,
    replayProvider: () -> EpgProgramme?,
    metadataProvider: () -> VideoPlayer.Metadata,
    showLogo: Boolean,
) {
    val channel = channelProvider()
    val recent = recentProvider()
    val programme = replayProvider() ?: recent?.now
    val clock = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
        if (showLogo) ChannelsChannelItemLogo(
            modifier = Modifier.size(112.dp, 72.dp),
            channelProvider = channelProvider,
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                programme?.title ?: channel.name,
                style = MaterialTheme.typography.headlineSmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                programme?.let {
                    Text(clock.format(it.startAt) + " — " + clock.format(it.endAt),
                        style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(0.75f))
                    Text(channel.name, style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.widthIn(max = 280.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                LiveChannelsChannelInfoTags(
                    channelProvider = channelProvider, channelLineIdxProvider = channelLineIdxProvider,
                    currentPlaybackEpgProgrammeProvider = replayProvider, playerMetadataProvider = metadataProvider,
                )
            }
            programme?.description?.trim()?.takeIf { it.isNotEmpty() && it != programme.title.trim() }?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(0.7f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            recent?.next?.let {
                Text(clock.format(it.startAt) + " — " + clock.format(it.endAt) + "   " + it.title,
                    style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(0.65f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
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
