package top.yogiczy.mytv.tv.ui.screen.dashboard.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.tv.material3.Text
import top.yogiczy.mytv.tv.ui.screen.settings.settingsVM
import top.yogiczy.mytv.tv.ui.theme.MyTvTheme
import top.yogiczy.mytv.tv.ui.utils.rememberWallClockFormat
import top.yogiczy.mytv.tv.ui.utils.rememberWallClockTimestamp

@Composable
fun DashboardTime(
    modifier: Modifier = Modifier,
    showSeconds: Boolean = settingsVM.uiTimeShowSeconds,
) {
    val timestamp = rememberWallClockTimestamp()
    val timeFormat = rememberWallClockFormat(showSeconds, includeDate = true)
    Text(timeFormat.format(timestamp), modifier = modifier)
}

@Preview
@Composable
private fun DashboardTimePreview() {
    MyTvTheme {
        DashboardTime(showSeconds = true)
    }
}
