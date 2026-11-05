package top.yogiczy.mytv.tv.ui.screensold.epg.components

import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import top.yogiczy.mytv.core.data.entities.epg.EpgProgramme
import top.yogiczy.mytv.core.data.entities.epg.EpgProgramme.Companion.isLive
import top.yogiczy.mytv.tv.ui.theme.MyTvTheme
import top.yogiczy.mytv.tv.ui.utils.focusOnLaunchedSaveable
import top.yogiczy.mytv.tv.ui.utils.handleKeyEvents
import top.yogiczy.mytv.tv.ui.utils.ifElse
import java.text.SimpleDateFormat
import java.util.Locale

@Composable
fun EpgProgrammeItem(
    modifier: Modifier = Modifier,
    epgProgrammeProvider: () -> EpgProgramme = { EpgProgramme() },
    supportPlaybackProvider: () -> Boolean = { false },
    isPlaybackProvider: () -> Boolean = { false },
    hasReservedProvider: () -> Boolean = { false },
    onPlayback: () -> Unit = {},
    onLive: () -> Unit = {},
    onReserve: () -> Unit = {},
    onDetails: () -> Unit = {},
    focusOnLive: Boolean = true,
    highlightLive: Boolean = true,
) {
    val programme = epgProgrammeProvider()
    val description = programme.description?.trim()?.takeIf { it.isNotEmpty() }
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val isLive = programme.isLive()
    val isPlayback = isPlaybackProvider()
    val now = System.currentTimeMillis()
    var isFocused by remember { mutableStateOf(false) }
    val colorScheme = MaterialTheme.colorScheme
    val contentColor = if (isFocused) colorScheme.surface else LocalContentColor.current
    val titleStyle = MaterialTheme.typography.titleSmall
    val descriptionStyle = MaterialTheme.typography.bodySmall
    val statusStyle = MaterialTheme.typography.labelSmall
    // Leave room for the longest label (已预约), including font scaling.
    val statusWidth = with(LocalDensity.current) { statusStyle.fontSize.toDp() * 3 } + 20.dp
    // Both title-only and title/synopsis rows share a height, even with large fonts.
    val rowHeight = with(LocalDensity.current) {
        maxOf(
            titleStyle.lineHeight.toDp() * 2,
            titleStyle.lineHeight.toDp() + descriptionStyle.lineHeight.toDp() + 4.dp,
        )
    } + 20.dp
    val status = when {
        isPlayback -> "回放" to Icons.Default.Replay
        isLive -> "直播" to Icons.Default.PlayArrow
        programme.endAt <= now && supportPlaybackProvider() -> "回放" to Icons.Default.Replay
        programme.startAt > now && hasReservedProvider() -> "已预约" to Icons.Default.CheckCircle
        programme.startAt > now -> "预约" to Icons.Default.Schedule
        else -> null
    }

    Row(
        modifier = modifier
            .ifElse(isLive && focusOnLive, Modifier.focusOnLaunchedSaveable())
            .onFocusChanged { isFocused = it.isFocused }
            .focusable()
            .fillMaxWidth()
            .height(rowHeight)
            .background(if (isFocused) colorScheme.onSurface else Color.Transparent, MaterialTheme.shapes.small)
            .semantics(mergeDescendants = true) {
                onLongClick("查看节目详情") { onDetails(); true }
            }
            .handleKeyEvents(
                onSelect = {
                    val selectedAt = System.currentTimeMillis()
                    when {
                        programme.startAt <= selectedAt && selectedAt < programme.endAt -> onLive()
                        programme.endAt <= selectedAt && supportPlaybackProvider() -> onPlayback()
                        programme.startAt > selectedAt -> onReserve()
                        description != null -> onDetails()
                    }
                },
                onLongSelect = onDetails,
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(timeFormat.format(programme.startAt), Modifier.width(48.dp),
            style = MaterialTheme.typography.labelLarge, color = contentColor, maxLines = 1)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                programme.title,
                modifier = Modifier.ifElse(description != null && isFocused, Modifier.basicMarquee(
                    iterations = Int.MAX_VALUE,
                    initialDelayMillis = 1_000,
                    repeatDelayMillis = 1_200,
                    velocity = 30.dp,
                )),
                style = titleStyle, color = contentColor,
                maxLines = if (description == null) 2 else 1,
                softWrap = description == null,
                overflow = TextOverflow.Ellipsis,
            )
            if (description != null) {
                Text(description, style = descriptionStyle, color = contentColor.copy(alpha = 0.7f),
                    maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
            }
        }
        Row(Modifier.width(statusWidth), horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.Start),
            verticalAlignment = Alignment.CenterVertically) {
            status?.let { (label, icon) ->
                val statusColor = if (!isFocused && (isPlayback || (isLive && highlightLive))) {
                    colorScheme.primary
                } else contentColor
                Icon(icon, contentDescription = null, Modifier.size(14.dp), tint = statusColor)
                Text(label, style = statusStyle, color = statusColor, maxLines = 1)
            }
        }
    }
}

@Preview
@Composable
private fun EpgProgrammeItemPreview() {
    MyTvTheme {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            EpgProgrammeItem(
                epgProgrammeProvider = { EpgProgramme.EXAMPLE },
            )
            EpgProgrammeItem(
                epgProgrammeProvider = {
                    EpgProgramme.EXAMPLE.copy(
                        startAt = System.currentTimeMillis() - 200000,
                        endAt = System.currentTimeMillis() - 100000,
                    )
                },
            )
            EpgProgrammeItem(
                epgProgrammeProvider = {
                    EpgProgramme.EXAMPLE.copy(
                        startAt = System.currentTimeMillis() + 100000,
                        endAt = System.currentTimeMillis() + 200000,
                    )
                },
            )
            EpgProgrammeItem(
                epgProgrammeProvider = {
                    EpgProgramme.EXAMPLE.copy(
                        startAt = System.currentTimeMillis() + 100000,
                        endAt = System.currentTimeMillis() + 200000,
                    )
                },
                hasReservedProvider = { true },
            )
        }
    }
}
