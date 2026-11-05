package top.yogiczy.mytv.tv.ui.screensold.epg.components

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.LocalTextStyle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import top.yogiczy.mytv.tv.ui.theme.MyTvTheme
import top.yogiczy.mytv.tv.ui.utils.handleKeyEvents
import java.text.SimpleDateFormat
import java.util.Locale

@Composable
fun EpgDayItem(
    modifier: Modifier = Modifier,
    dayProvider: () -> String = { "" }, // 格式：E MM-dd
    isSelectedProvider: () -> Boolean = { false },
    onDaySelected: () -> Unit = {},
) {
    val day = dayProvider()
    val isSelected = isSelectedProvider()

    val dateFormat = SimpleDateFormat("E MM-dd", Locale.getDefault())
    val today = dateFormat.format(System.currentTimeMillis())
    val tomorrow =
        dateFormat.format(System.currentTimeMillis() + 24 * 3600 * 1000)
    val dayAfterTomorrow =
        dateFormat.format(System.currentTimeMillis() + 48 * 3600 * 1000)

    var isFocused by remember(day) { mutableStateOf(false) }

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

    Box(
        modifier = modifier
            .handleKeyEvents(onSelect = onDaySelected)
            .onFocusChanged {
                val focused = it.isFocused || it.hasFocus
                val gainedFocus = focused && !isFocused
                isFocused = focused
                if (gainedFocus) onDaySelected()
            }
            .focusable()
            .background(containerColor, MaterialTheme.shapes.small)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Column {
            val lines = day.split(" ")

            CompositionLocalProvider(
                LocalContentColor provides contentColor,
                LocalTextStyle provides MaterialTheme.typography.titleSmall,
            ) {
                Text(
                    when (day) {
                        today -> "今天"
                        tomorrow -> "明天"
                        dayAfterTomorrow -> "后天"
                        else -> lines[0]
                    },
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    softWrap = false,
                )
                Text(
                    lines[1],
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

@Preview
@Composable
private fun EpgDayItemPreview() {
    MyTvTheme {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            EpgDayItem(
                dayProvider = { "周一 07-09" },
            )

            EpgDayItem(
                dayProvider = { "周一 07-09" },
                isSelectedProvider = { true },
            )
        }
    }
}
