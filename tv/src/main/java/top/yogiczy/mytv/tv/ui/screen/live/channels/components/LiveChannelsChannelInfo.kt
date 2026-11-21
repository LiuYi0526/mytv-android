package top.yogiczy.mytv.tv.ui.screen.live.channels.components

import android.net.TrafficStats
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import top.yogiczy.mytv.core.data.entities.channel.Channel
import top.yogiczy.mytv.core.data.entities.epg.EpgDisplaySchedule
import top.yogiczy.mytv.core.data.entities.epg.EpgProgramme
import top.yogiczy.mytv.core.data.entities.epg.EpgProgramme.Companion.progress
import top.yogiczy.mytv.core.data.entities.epg.EpgProgramme.Companion.remainingMinutes
import top.yogiczy.mytv.core.data.entities.epg.EpgProgrammeRecent
import top.yogiczy.mytv.core.data.utils.ChannelUtil
import top.yogiczy.mytv.core.util.utils.humanizeAudioChannels
import top.yogiczy.mytv.core.util.utils.isIPv6
import top.yogiczy.mytv.tv.ui.material.ProgressBar
import top.yogiczy.mytv.tv.ui.material.ProgressBarColors
import top.yogiczy.mytv.tv.ui.material.Tag
import top.yogiczy.mytv.tv.ui.material.TagDefaults
import top.yogiczy.mytv.tv.ui.screen.channels.components.ChannelsChannelItemLogo
import top.yogiczy.mytv.tv.ui.screen.settings.settingsVM
import top.yogiczy.mytv.tv.ui.screensold.videoplayer.player.VideoPlayer
import top.yogiczy.mytv.tv.ui.theme.MyTvTheme
import top.yogiczy.mytv.tv.ui.utils.gridColumns
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun LiveChannelsChannelInfo(
    modifier: Modifier = Modifier,
    channelProvider: () -> Channel = { Channel() },
    channelLineIdxProvider: () -> Int = { 0 },
    recentEpgProgrammeProvider: () -> EpgProgrammeRecent? = { null },
    isInTimeShiftProvider: () -> Boolean = { false },
    currentPlaybackEpgProgrammeProvider: () -> EpgProgramme? = { null },
    playerMetadataProvider: () -> VideoPlayer.Metadata = { VideoPlayer.Metadata() },
    showChannelLogo: Boolean = settingsVM.uiShowChannelLogo,
    showProgrammeDescription: Boolean = false,
    showNetSpeed: Boolean = true,
    channelNameFirst: Boolean = false,
) {
    val channel = channelProvider()
    val currentPlaybackEpgProgramme = currentPlaybackEpgProgrammeProvider()
    val recentProgramme = recentEpgProgrammeProvider()
    val displayedProgramme = currentPlaybackEpgProgramme ?: recentProgramme?.now
    val programmeTitle = displayedProgramme?.title?.takeIf { it.isNotBlank() }
        ?: EpgDisplaySchedule.UNKNOWN_TITLE
    val channelIsHeadline = channelNameFirst || displayedProgramme == null
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val secondaryColor = LocalContentColor.current.copy(alpha = 0.75f)
    val channelIdentity: @Composable (TextStyle, Modifier) -> Unit = { style, titleModifier ->
        Text(
            channel.name,
            modifier = titleModifier,
            style = style,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        LiveChannelsChannelInfoTags(
            channelProvider = { channel },
            channelLineIdxProvider = channelLineIdxProvider,
            isInTimeShiftProvider = isInTimeShiftProvider,
            currentPlaybackEpgProgrammeProvider = { currentPlaybackEpgProgramme },
            playerMetadataProvider = playerMetadataProvider,
        )
        if (showNetSpeed) {
            LiveChannelsChannelInfoNetSpeed()
        }
    }

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        if (showChannelLogo) {
            ChannelsChannelItemLogo(
                modifier = Modifier
                    .height(76.dp)
                    .aspectRatio(16 / 9f),
                channelProvider = channelProvider,
            ) {
                LiveChannelsChannelInfoNo(channelProvider = channelProvider)
            }
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ChannelInfoLine { titleWidth ->
                val headlineStyle = MaterialTheme.typography.headlineSmall
                if (channelIsHeadline) {
                    channelIdentity(headlineStyle, titleWidth)
                } else {
                    Text(
                        programmeTitle,
                        style = headlineStyle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (channelNameFirst || displayedProgramme != null) {
                ChannelInfoLine { titleWidth ->
                    displayedProgramme?.let { programme ->
                        Text(
                            programme.formatTimeRange(timeFormat),
                            modifier = Modifier.alignByBaseline(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = secondaryColor,
                            maxLines = 1,
                        )
                        if (currentPlaybackEpgProgramme == null) {
                            ProgrammeProgress(programme, secondaryColor)
                        }
                    }
                    val supportingStyle = MaterialTheme.typography.titleMedium
                    val titleModifier = titleWidth.alignByBaseline().sizeIn(maxWidth = 280.dp)
                    if (channelNameFirst) {
                        Text(
                            programmeTitle,
                            modifier = titleModifier,
                            style = supportingStyle,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    } else {
                        channelIdentity(supportingStyle, titleModifier)
                    }
                }
            }
            if (showProgrammeDescription) {
                LiveChannelsChannelInfoDescription(displayedProgramme)
            }
            recentProgramme?.next?.let { next ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        next.formatTimeRange(timeFormat),
                        style = MaterialTheme.typography.bodyMedium,
                        color = secondaryColor,
                        maxLines = 1,
                    )
                    Text(
                        next.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = secondaryColor,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChannelInfoLine(content: @Composable RowScope.(titleWidth: Modifier) -> Unit) {
    BoxWithConstraints {
        val spacing = Arrangement.spacedBy(12.dp)
        if (maxWidth >= 420.dp) {
            Row(horizontalArrangement = spacing, verticalAlignment = Alignment.CenterVertically) {
                // Measure the labels first and give the title the remaining space.
                content(Modifier.weight(1f, fill = false))
            }
        } else {
            // A small multiview tile may not have room for all the metadata on one line.
            FlowRow(
                horizontalArrangement = spacing,
                verticalArrangement = Arrangement.spacedBy(4.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                content(Modifier)
            }
        }
    }
}

private fun EpgProgramme.formatTimeRange(timeFormat: SimpleDateFormat): String =
    "${timeFormat.format(startAt)} — ${timeFormat.format(endAt)}"

@Composable
private fun LiveChannelsChannelInfoDescription(programme: EpgProgramme?) {
    val description = programme?.description?.trim()
        ?.takeIf { it.isNotEmpty() && it != programme.title.trim() } ?: return

    Text(
        text = description,
        modifier = Modifier.fillMaxWidth(),
        style = MaterialTheme.typography.bodyMedium,
        color = LocalContentColor.current.copy(alpha = 0.75f),
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun LiveChannelsChannelInfoNo(
    modifier: Modifier = Modifier,
    channelProvider: () -> Channel = { Channel() },
) {
    val channel = channelProvider()

    Box(
        modifier = modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.inverseOnSurface.copy(0.5f), MaterialTheme.shapes.medium),
    ) {
        Text(
            channel.no,
            style = MaterialTheme.typography.headlineLarge,
            modifier = Modifier.align(Alignment.Center),
        )
    }
}

@Composable
private fun LiveChannelsChannelInfoTags(
    modifier: Modifier = Modifier,
    channelProvider: () -> Channel = { Channel() },
    channelLineIdxProvider: () -> Int = { 0 },
    isInTimeShiftProvider: () -> Boolean = { false },
    currentPlaybackEpgProgrammeProvider: () -> EpgProgramme? = { null },
    playerMetadataProvider: () -> VideoPlayer.Metadata = { VideoPlayer.Metadata() },
) {
    val channel = channelProvider()
    val channelLineIdx = channelLineIdxProvider()
    val line = channel.lineList.getOrNull(channelLineIdx)
    val isInTimeShift = isInTimeShiftProvider()
    val currentPlaybackEpgProgramme = currentPlaybackEpgProgrammeProvider()
    val playerMetadata = playerMetadataProvider()

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val tagColors = TagDefaults.colors(
            containerColor = MaterialTheme.colorScheme.inverseSurface.copy(0.8f),
            contentColor = MaterialTheme.colorScheme.surface,
        )

        if (isInTimeShift) {
            Tag("时移", colors = tagColors)
        }

        if (currentPlaybackEpgProgramme != null) {
            Tag("回放", colors = tagColors)
        }

        if (channel.lineList.size > 1) {
            Tag("${channelLineIdx + 1}/${channel.lineList.size}", colors = tagColors)
        }

        if (line?.url?.startsWith("webview://") == true) {
            Tag(ChannelUtil.getHybridWebViewUrlProvider(line.url), colors = tagColors)
        } else {
            if (line?.url?.isIPv6() == true) Tag("IPv6", colors = tagColors)
        }

        playerMetadata.video?.let { nnVideo ->
            if ((nnVideo.height ?: 0) > 0) {
                Tag(
                    when (nnVideo.height) {
                        240 -> "240p"
                        360 -> "360p"
                        480 -> "480p"
                        720 -> "HD"
                        1080 -> "FHD"
                        1440 -> "QHD"
                        2160 -> "4K UHD"
                        4320 -> "8K UHD"
                        else -> "${nnVideo.width}x${nnVideo.height}"
                    },
                    colors = tagColors,
                )
            }

            if ((nnVideo.frameRate ?: 0f) > 0f) {
                Tag("${nnVideo.frameRate?.roundToInt()}FPS", colors = tagColors)
            }
        }

        playerMetadata.audio?.let { nnAudio ->
            nnAudio.channels?.takeIf { it > 0 }?.let { nnChannels ->
                Tag(
                    nnAudio.channelsLabel ?: nnChannels.humanizeAudioChannels(),
                    colors = tagColors,
                )
            }
        }
    }
}

@Composable
private fun ProgrammeProgress(programme: EpgProgramme, color: Color) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProgressBar(
            process = programme.progress().takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f,
            modifier = Modifier.size(44.dp, 4.dp),
            colors = ProgressBarColors(
                barColor = MaterialTheme.colorScheme.onSurface.copy(0.2f),
                progressColor = MaterialTheme.colorScheme.onSurface,
            ),
        )
        Text(
            "剩余 ${programme.remainingMinutes().coerceAtLeast(0)} 分钟",
            style = MaterialTheme.typography.bodyMedium,
            color = color,
            maxLines = 1,
        )
    }
}

@Composable
private fun LiveChannelsChannelInfoNetSpeed(
    modifier: Modifier = Modifier,
    netSpeed: Long = rememberNetSpeed(),
) {
    Text(
        text = if (netSpeed < 1024 * 999) "${netSpeed / 1024}KB/s"
        else "${DecimalFormat("#.#").format(netSpeed / 1024 / 1024f)}MB/s",
        modifier = modifier.sizeIn(minWidth = 60.dp),
        maxLines = 1,
    )
}

@Composable
private fun rememberNetSpeed(): Long {
    var netSpeed by remember { mutableLongStateOf(0) }

    LaunchedEffect(Unit) {
        var lastTotalRxBytes = TrafficStats.getUidRxBytes(android.os.Process.myUid())
        var lastTimeStamp = System.currentTimeMillis()

        while (true) {
            delay(1000)
            val nowTotalRxBytes = TrafficStats.getUidRxBytes(android.os.Process.myUid())
            val nowTimeStamp = System.currentTimeMillis()
            val speed = (nowTotalRxBytes - lastTotalRxBytes) / (nowTimeStamp - lastTimeStamp) * 1000
            lastTimeStamp = nowTimeStamp
            lastTotalRxBytes = nowTotalRxBytes

            netSpeed = speed
        }
    }

    return netSpeed
}

@Preview(device = "id:Android TV (720p)")
@Composable
private fun LiveChannelsChannelInfoPreview() {
    MyTvTheme {
        LiveChannelsChannelInfo(
            modifier = Modifier.width(800.dp),
            channelProvider = { Channel.EXAMPLE.copy(name = "CCTV-1 法治与法治".repeat(3)) },
            channelLineIdxProvider = { 1 },
            recentEpgProgrammeProvider = {
                EpgProgrammeRecent.EXAMPLE.copy(
                    now = EpgProgrammeRecent.EXAMPLE.now?.copy(
                        title = "2023/2024赛季中国男子篮球职业联赛季后赛12进8第五场".repeat(
                            2
                        ),
                        description = "两支球队再度交锋，争夺晋级资格。节目简介仅显示两行，超出部分以省略号结束。".repeat(4),
                    )
                )
            },
            playerMetadataProvider = {
                VideoPlayer.Metadata(
                    video = VideoPlayer.Metadata.Video(
                        width = 7680,
                        height = 4320,
                        frameRate = 50f,
                    ),

                    audio = VideoPlayer.Metadata.Audio(
                        channels = 10,
                    ),
                )
            },
            showChannelLogo = true,
            showProgrammeDescription = true,
        )
    }
}

@Preview(device = "id:Android TV (720p)")
@Composable
private fun LiveChannelsChannelInfoPlaybackPreview() {
    MyTvTheme {
        LiveChannelsChannelInfo(
            channelProvider = { Channel.EXAMPLE },
            channelLineIdxProvider = { 1 },
            currentPlaybackEpgProgrammeProvider = { EpgProgramme.EXAMPLE },
            showChannelLogo = true,
        )
    }
}

@Preview(device = "id:Android TV (720p)")
@Composable
private fun LiveChannelsChannelInfoNoLogoPreview() {
    MyTvTheme {
        settingsVM.uiShowChannelLogo = false
        LiveChannelsChannelInfo(
            modifier = Modifier.width(5.gridColumns()),
            channelProvider = { Channel.EXAMPLE.copy(name = "中文".repeat(10)) },
            channelLineIdxProvider = { 1 },
            recentEpgProgrammeProvider = { EpgProgrammeRecent.EXAMPLE },
            playerMetadataProvider = {
                VideoPlayer.Metadata(
                    video = VideoPlayer.Metadata.Video(
                        width = 7680,
                        height = 4320,
                        frameRate = 50f,
                    ),
                )
            },
        )
    }
}
