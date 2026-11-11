package top.yogiczy.mytv.tv.ui.screensold.videoplayercontroller.components

import android.view.KeyEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.KeyboardDoubleArrowLeft
import androidx.compose.material.icons.filled.KeyboardDoubleArrowRight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import top.yogiczy.mytv.tv.ui.theme.MyTvTheme
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.math.min

@Composable
fun VideoPlayerControllerPositionCtrl(
    modifier: Modifier = Modifier,
    currentPositionProvider: () -> Long = { 0L },
    durationProvider: () -> Pair<Long, Long> = { 0L to 0L },
    positionIsAbsoluteProvider: () -> Boolean = { false },
    seekEnabledProvider: () -> Boolean = { true },
    availableUntilProvider: () -> Long? = { null },
    onSeekTo: (Long) -> Unit = {},
    toolbar: (@Composable (positionLabel: String, adjust: (Long) -> Unit, cancelPreview: () -> Unit) -> Unit)? = null,
) {
    val range = durationProvider()
    val absolute = positionIsAbsoluteProvider()
    val seekEnabled = seekEnabledProvider()
    // A new programme/time base must never receive an old pending seek.
    var pending by remember(range.first, absolute, seekEnabled) { mutableStateOf<Long?>(null) }
    var dragging by remember(range.first, absolute, seekEnabled) { mutableStateOf(false) }
    var keyHeld by remember(range.first, absolute, seekEnabled) { mutableStateOf(false) }
    var revision by remember { mutableStateOf(0L) }
    val latestSeek by rememberUpdatedState(onSeekTo)
    val latestPosition by rememberUpdatedState(currentPositionProvider)
    val latestRange by rememberUpdatedState(range)
    val latestAbsolute by rememberUpdatedState(absolute)
    val latestSeekEnabled by rememberUpdatedState(seekEnabled)

    fun clamp(position: Long): Long {
        val (start, end) = latestRange
        val limit = if (latestAbsolute) min(end, System.currentTimeMillis()) else end
        return position.coerceIn(start, limit.coerceAtLeast(start))
    }
    fun commit() {
        val target = pending ?: return
        pending = null
        if (latestSeekEnabled && latestRange.second > latestRange.first) latestSeek(clamp(target))
    }
    fun adjust(delta: Long) {
        if (!latestSeekEnabled || latestRange.second <= latestRange.first) return
        pending = clamp((pending ?: latestPosition()) + delta)
        revision++
    }
    // Touch commits on release; held remote keys only commit after release/idle.
    LaunchedEffect(pending, revision, dragging, keyHeld, range, absolute) {
        if (pending != null && !dragging && !keyHeld) {
            delay(1_000)
            commit()
        }
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        VideoPlayerControllerPositionProgress(
            currentPosition = pending ?: currentPositionProvider(),
            range = range,
            absolute = absolute,
            availableUntil = availableUntilProvider().takeIf { absolute },
            seekEnabled = seekEnabled,
            showTimeLabels = toolbar == null,
            onRemoteAdjust = { direction ->
                keyHeld = true
                adjust(600_000L * direction)
            },
            onRemoteRelease = { keyHeld = false },
            onConfirm = { keyHeld = false; commit() },
            onDragStart = { keyHeld = false; dragging = true },
            onDrag = { fraction -> adjust(((latestRange.second - latestRange.first) * fraction).toLong()) },
            onDragEnd = { dragging = false; commit() },
            onDragCancel = { dragging = false; pending = null },
            onSetProgress = { fraction ->
                pending = clamp(latestRange.first + ((latestRange.second - latestRange.first) * fraction).toLong())
                commit()
            },
        )
        if (toolbar != null) {
            val position = pending ?: currentPositionProvider()
            val elapsed = (position - range.first).coerceAtLeast(0L)
            val total = (range.second - range.first).coerceAtLeast(0L)
            val clock = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
            val label = when {
                total <= 0L -> "直播"
                absolute -> "${clock.format(position)} / ${clock.format(range.second)}"
                else -> "${formatElapsed(elapsed.coerceAtMost(total))} / ${formatElapsed(total)}"
            }
            toolbar(label, ::adjust) { pending = null; dragging = false; keyHeld = false }
        } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            VideoPlayerControllerBtn(
                modifier = Modifier.semantics { contentDescription = "后退10分钟" },
                imageVector = Icons.Default.KeyboardDoubleArrowLeft,
                onSelect = { adjust(-600_000L) },
            )
            VideoPlayerControllerBtn(
                modifier = Modifier.semantics { contentDescription = "后退1分钟" },
                imageVector = Icons.Default.ChevronLeft,
                onSelect = { adjust(-60_000L) },
            )
            VideoPlayerControllerBtn(
                modifier = Modifier.semantics { contentDescription = "前进1分钟" },
                imageVector = Icons.Default.ChevronRight,
                onSelect = { adjust(60_000L) },
            )
            VideoPlayerControllerBtn(
                modifier = Modifier.semantics { contentDescription = "前进10分钟" },
                imageVector = Icons.Default.KeyboardDoubleArrowRight,
                onSelect = { adjust(600_000L) },
            )
        }
    }
}

@Composable
private fun VideoPlayerControllerPositionProgress(
    currentPosition: Long,
    range: Pair<Long, Long>,
    absolute: Boolean,
    availableUntil: Long?,
    seekEnabled: Boolean,
    showTimeLabels: Boolean,
    onRemoteAdjust: (Int) -> Unit,
    onRemoteRelease: () -> Unit,
    onConfirm: () -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
    onSetProgress: (Float) -> Unit,
) {
    val valid = seekEnabled && range.second > range.first
    val availableFraction = availableUntil?.takeIf { range.second > range.first }?.let {
        ((it - range.first).toDouble() / (range.second - range.first)).toFloat().coerceIn(0f, 1f)
    }
    val fraction = if (range.second > range.first)
        ((currentPosition - range.first).toDouble() / (range.second - range.first)).toFloat().coerceIn(0f, 1f)
            .coerceAtMost(availableFraction ?: 1f)
    else 0f
    val timeFormat = remember(absolute) {
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).apply {
            if (!absolute) timeZone = TimeZone.getTimeZone("UTC")
        }
    }
    val focusRequester = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    val latestStart by rememberUpdatedState(onDragStart)
    val latestDrag by rememberUpdatedState(onDrag)
    val latestEnd by rememberUpdatedState(onDragEnd)
    val latestCancel by rememberUpdatedState(onDragCancel)
    val color = MaterialTheme.colorScheme.primary
    val availableColor = LocalContentColor.current.copy(alpha = 0.55f)
    val trackColor = LocalContentColor.current.copy(alpha = 0.25f)

    Column {
        Canvas(
            Modifier.fillMaxWidth().height(48.dp)
                .focusRequester(focusRequester)
                .onFocusChanged { focused = it.isFocused; if (!it.isFocused) onRemoteRelease() }
                .onPreviewKeyEvent {
                    val event = it.nativeKeyEvent
                    when (event.keyCode) {
                        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            if (!valid) false else {
                                if (event.action == KeyEvent.ACTION_DOWN) {
                                    onRemoteAdjust(if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1)
                                } else if (event.action == KeyEvent.ACTION_UP) onRemoteRelease()
                                true
                            }
                        }
                        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                            if (event.action == KeyEvent.ACTION_UP) onConfirm()
                            true
                        }
                        else -> false
                    }
                }
                .semantics {
                    contentDescription = "播放进度"
                    progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
                    if (valid) setProgress { onSetProgress(it.coerceIn(0f, 1f)); true }
                }
                .focusable(enabled = valid)
                .pointerInput(range, absolute, seekEnabled) {
                    if (!valid) return@pointerInput
                    detectHorizontalDragGestures(
                        onDragStart = { focusRequester.requestFocus(); latestStart() },
                        onDragEnd = { latestEnd() },
                        onDragCancel = { latestCancel() },
                    ) { change, delta ->
                        change.consume()
                        val trackWidth = size.width - 16.dp.toPx()
                        if (trackWidth > 0) latestDrag(delta / trackWidth)
                    }
                },
        ) {
            val inset = 8.dp.toPx()
            val left = Offset(inset, size.height / 2)
            val right = Offset((size.width - inset).coerceAtLeast(inset), left.y)
            val thumb = Offset(left.x + (right.x - left.x) * fraction, left.y)
            drawLine(trackColor, left, right, 6.dp.toPx(), StrokeCap.Round)
            // Past broadcasts are available up to the live clock; the remaining
            // programme duration is still in the future, not buffered media.
            availableFraction?.takeIf { it > 0f }?.let {
                val liveEdge = Offset(left.x + (right.x - left.x) * it, left.y)
                drawLine(availableColor, left, liveEdge, 6.dp.toPx(), StrokeCap.Round)
            }
            drawLine(color, left, thumb, 6.dp.toPx(), StrokeCap.Round)
            drawCircle(color, (if (focused) 8.dp else 5.dp).toPx(), thumb)
        }
        if (showTimeLabels) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(timeFormat.format(range.first), maxLines = 1)
            Text(timeFormat.format(currentPosition) + " / " + timeFormat.format(range.second), maxLines = 1)
        }
    }
}

private fun formatElapsed(milliseconds: Long): String {
    val seconds = milliseconds / 1_000
    return if (seconds >= 3_600) String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3_600, seconds / 60 % 60, seconds % 60)
    else String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60)
}

@Preview(device = "id:Android TV (720p)")
@Composable
private fun VideoPlayerControllerPositionCtrlPreview() {
    MyTvTheme {
        Box(Modifier.width(600.dp)) {
            VideoPlayerControllerPositionCtrl(
                currentPositionProvider = { 120_000L },
                durationProvider = { 0L to 3_600_000L },
            )
        }
    }
}
