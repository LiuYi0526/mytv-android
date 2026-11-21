package top.yogiczy.mytv.tv.ui.screensold.datetime.components

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import top.yogiczy.mytv.tv.ui.screen.settings.settingsVM
import top.yogiczy.mytv.tv.ui.theme.MyTvTheme
import top.yogiczy.mytv.tv.ui.utils.rememberWallClockFormat
import top.yogiczy.mytv.tv.ui.utils.rememberWallClockTimestamp
import java.text.SimpleDateFormat
import java.util.Locale

@Composable
fun DateTimeDetail(
    modifier: Modifier = Modifier,
    timestamp: Long = rememberWallClockTimestamp(),
    showSeconds: Boolean = settingsVM.uiTimeShowSeconds,
) {
    val timeFormat = rememberWallClockFormat(showSeconds)
    val dateFormat = SimpleDateFormat("MM/dd EEE", Locale.getDefault())

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = dateFormat.format(timestamp),
            style = MaterialTheme.typography.labelMedium,
        )
        Text(
            text = timeFormat.format(timestamp),
            style = MaterialTheme.typography.titleLarge,
        )
    }
}

@Preview
@Composable
private fun DateTimeDetailPreview() {
    MyTvTheme {
        DateTimeDetail(showSeconds = true)
    }
}
