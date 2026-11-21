package top.yogiczy.mytv.tv.ui.utils

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

@Composable
internal fun rememberWallClockTimestamp(): Long {
    var timestamp by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            timestamp = System.currentTimeMillis()
        }
    }
    return timestamp
}

@Composable
internal fun rememberWallClockFormat(showSeconds: Boolean, includeDate: Boolean = false): SimpleDateFormat {
    val timePattern = if (showSeconds) "HH:mm:ss" else "HH:mm"
    val pattern = if (includeDate) "MM/dd EEE $timePattern" else timePattern
    val locale = Locale.getDefault()
    val zone = TimeZone.getDefault()
    return remember(pattern, locale, zone) {
        SimpleDateFormat(pattern, locale).apply { timeZone = zone }
    }
}
