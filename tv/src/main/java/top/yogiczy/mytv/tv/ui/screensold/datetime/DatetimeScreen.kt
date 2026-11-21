package top.yogiczy.mytv.tv.ui.screensold.datetime

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import top.yogiczy.mytv.core.data.utils.Constants
import top.yogiczy.mytv.tv.ui.rememberChildPadding
import top.yogiczy.mytv.tv.ui.theme.MyTvTheme
import top.yogiczy.mytv.tv.ui.tooling.PreviewWithLayoutGrids
import top.yogiczy.mytv.tv.ui.utils.Configs
import top.yogiczy.mytv.tv.ui.utils.rememberWallClockFormat
import top.yogiczy.mytv.tv.ui.utils.rememberWallClockTimestamp

@Composable
fun DatetimeScreen(
    modifier: Modifier = Modifier,
    showModeProvider: () -> Configs.UiTimeShowMode = { Configs.UiTimeShowMode.HIDDEN },
    showSeconds: Boolean = true,
) {
    val childPadding = rememberChildPadding()

    val mode = showModeProvider()
    val timestamp = if (mode == Configs.UiTimeShowMode.HIDDEN) 0L else rememberWallClockTimestamp()
    val visible = when (mode) {
        Configs.UiTimeShowMode.HIDDEN -> false
        Configs.UiTimeShowMode.ALWAYS -> true
        Configs.UiTimeShowMode.EVERY_HOUR, Configs.UiTimeShowMode.HALF_HOUR -> {
            val interval = if (mode == Configs.UiTimeShowMode.EVERY_HOUR) 3_600_000L else 1_800_000L
            val position = timestamp % interval
            val duration = Constants.UI_TIME_SCREEN_SHOW_DURATION
            position <= duration + 1000 || position >= interval - duration
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (visible) {
            Text(
                text = rememberWallClockFormat(showSeconds).format(timestamp),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = childPadding.top, end = childPadding.end),
            )
        }
    }
}


@Preview(device = "id:Android TV (720p)")
@Composable
private fun DatetimeScreenPreview() {
    MyTvTheme {
        PreviewWithLayoutGrids {
            DatetimeScreen(
                showModeProvider = { Configs.UiTimeShowMode.ALWAYS }
            )
        }
    }
}