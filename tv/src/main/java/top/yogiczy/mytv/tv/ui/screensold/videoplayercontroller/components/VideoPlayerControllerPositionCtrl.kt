package top.yogiczy.mytv.tv.ui.screensold.videoplayercontroller.components

import androidx.annotation.IntRange
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.KeyboardDoubleArrowLeft
import androidx.compose.material.icons.filled.KeyboardDoubleArrowRight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.debounce
import top.yogiczy.mytv.tv.ui.material.ProgressBar
import top.yogiczy.mytv.tv.ui.theme.MyTvTheme
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.math.max
import kotlin.math.min

@Composable
fun VideoPlayerControllerPositionCtrl(
    modifier: Modifier = Modifier,
    currentPositionProvider: () -> Long = { 0L },
    durationProvider: () -> Pair<Long, Long> = { 0L to 0L },
    positionIsAbsoluteProvider: () -> Boolean = { false },
    onSeekTo: (Long) -> Unit = {},
) {
    var seekToPosition by remember { mutableStateOf<Long?>(null) }

    val debounce = rememberDebounce(
        wait = 1000L,
        func = {
            seekToPosition?.let { nnSeekToPosition ->
                // Keep the provider's time base, including UTC for Media3 replay.
                onSeekTo(nnSeekToPosition)
                seekToPosition = null
            }
        },
    )
    LaunchedEffect(seekToPosition) {
        if (seekToPosition != null) debounce.active()
    }

    fun seekForward(ms: Long) {
        val currentPosition = currentPositionProvider()
        val startPosition = durationProvider().first
        seekToPosition = max(startPosition, (seekToPosition ?: currentPosition) - ms)
    }

    fun seekNext(ms: Long) {
        val currentPosition = currentPositionProvider()
        val endPosition = durationProvider().second
        seekToPosition = min(
            if (endPosition <= 0L) Long.MAX_VALUE
            else if (positionIsAbsoluteProvider()) min(endPosition, System.currentTimeMillis())
            else endPosition,
            (seekToPosition ?: currentPosition) + ms
        )
    }

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VideoPlayerControllerBtn(
            modifier = Modifier.semantics { contentDescription = "后退10分钟" },
            imageVector = Icons.Default.KeyboardDoubleArrowLeft,
            onSelect = { seekForward(1000L * 60 * 10) },
        )
        VideoPlayerControllerBtn(
            modifier = Modifier.semantics { contentDescription = "后退1分钟" },
            imageVector = Icons.Default.ChevronLeft,
            onSelect = { seekForward(1000L * 60 * 1) },
        )

        VideoPlayerControllerBtn(
            modifier = Modifier.semantics { contentDescription = "前进1分钟" },
            imageVector = Icons.Default.ChevronRight,
            onSelect = { seekNext(1000L * 60 * 1) },
        )
        VideoPlayerControllerBtn(
            modifier = Modifier.semantics { contentDescription = "前进10分钟" },
            imageVector = Icons.Default.KeyboardDoubleArrowRight,
            onSelect = { seekNext(1000L * 60 * 10) },
        )

        VideoPlayerControllerPositionProgress(
            modifier = Modifier.padding(start = 10.dp),
            currentPositionProvider = { seekToPosition ?: currentPositionProvider() },
            durationProvider = durationProvider,
            positionIsAbsoluteProvider = positionIsAbsoluteProvider,
        )
    }
}

@Composable
private fun VideoPlayerControllerPositionProgress(
    modifier: Modifier = Modifier,
    currentPositionProvider: () -> Long = { 0L },
    durationProvider: () -> Pair<Long, Long> = { 0L to 0L },
    positionIsAbsoluteProvider: () -> Boolean = { false },
) {
    val currentPosition = currentPositionProvider()
    val duration = durationProvider()
    val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).apply {
        // Elapsed media positions are durations, not dates in the device's local time zone.
        if (!positionIsAbsoluteProvider()) timeZone = TimeZone.getTimeZone("UTC")
    }

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = timeFormat.format(duration.first),
        )

        ProgressBar(
            process = if (duration.second > duration.first)
                ((currentPosition - duration.first) / (duration.second - duration.first).toFloat()).coerceIn(0f, 1f)
            else 0f,
            modifier = Modifier
                .weight(1f)
                .height(6.dp),
        )

        Text(
            text = "${timeFormat.format(currentPosition)} / ${timeFormat.format(duration.second)}",
        )
    }
}

@Stable
class Debounce internal constructor(
    @IntRange(from = 0) private val wait: Long,
    private val func: () -> Unit = {},
) {
    fun active() {
        channel.trySend(wait)
    }

    private val channel = Channel<Long>(Channel.CONFLATED)

    @OptIn(FlowPreview::class)
    suspend fun observe() {
        channel.consumeAsFlow().debounce { it }.collect {
            func()
        }
    }
}

@Composable
fun rememberDebounce(
    @IntRange(from = 0) wait: Long,
    func: () -> Unit = {},
) = rememberUpdatedState(func).let { latest ->
    remember(wait) { Debounce(wait = wait, func = { latest.value() }) }
}.also {
    LaunchedEffect(it) { it.observe() }
}

@Preview(device = "id:Android TV (720p)")
@Composable
private fun VideoPlayerControllerPositionCtrlPreview() {
    MyTvTheme {
        Box(modifier = Modifier.width(600.dp)) {
            VideoPlayerControllerPositionCtrl(
                currentPositionProvider = { System.currentTimeMillis() },
                durationProvider = {
                    System.currentTimeMillis() - 1000L * 60 * 60 to System.currentTimeMillis() + 1000L * 60 * 60
                },
            )
        }
    }
}
