package top.yogiczy.mytv.tv.ui.screen.settings.categories

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.tooling.preview.Preview
import androidx.tv.material3.Switch
import androidx.tv.material3.Text
import top.yogiczy.mytv.core.util.utils.humanizeMs
import top.yogiczy.mytv.tv.ui.screen.settings.SettingsViewModel
import top.yogiczy.mytv.tv.ui.screen.settings.components.SettingsCategoryScreen
import top.yogiczy.mytv.tv.ui.screen.settings.components.SettingsListItem
import top.yogiczy.mytv.tv.ui.screen.settings.components.SettingsUserAgentItem
import top.yogiczy.mytv.tv.ui.screen.settings.components.SettingsHeadersItem
import top.yogiczy.mytv.tv.ui.screen.settings.settingsVM
import top.yogiczy.mytv.tv.ui.theme.MyTvTheme

@Composable
fun SettingsVideoPlayerScreen(
    modifier: Modifier = Modifier,
    settingsViewModel: SettingsViewModel = settingsVM,
    toVideoPlayerCoreScreen: () -> Unit = {},
    toVideoPlayerRenderModeScreen: () -> Unit = {},
    toVideoPlayerDisplayModeScreen: () -> Unit = {},
    toVideoPlayerLoadTimeoutScreen: () -> Unit = {},
    onBackPressed: () -> Unit = {},
) {
    SettingsCategoryScreen(
        modifier = modifier,
        header = { Text("设置 / 播放器") },
        onBackPressed = onBackPressed,
    ) { firstItemFocusRequester ->
        item {
            SettingsListItem(
                modifier = Modifier.focusRequester(firstItemFocusRequester),
                headlineContent = "内核",
                trailingContent = settingsViewModel.videoPlayerCore.label,
                onSelect = toVideoPlayerCoreScreen,
                link = true,
            )
        }

        item {
            SettingsListItem(
                headlineContent = "渲染方式",
                trailingContent = settingsViewModel.videoPlayerRenderMode.label,
                onSelect = toVideoPlayerRenderModeScreen,
                link = true,
            )
        }

        item {
            SettingsListItem(
                headlineContent = "强制音频软解",
                trailingContent = {
                    Switch(settingsViewModel.videoPlayerForceAudioSoftDecode, null)
                },
                onSelect = {
                    settingsViewModel.videoPlayerForceAudioSoftDecode =
                        !settingsViewModel.videoPlayerForceAudioSoftDecode
                },
            )
        }

        item {
            SettingsListItem(
                headlineContent = "停止上一媒体项",
                trailingContent = {
                    Switch(settingsViewModel.videoPlayerStopPreviousMediaItem, null)
                },
                onSelect = {
                    settingsViewModel.videoPlayerStopPreviousMediaItem =
                        !settingsViewModel.videoPlayerStopPreviousMediaItem
                },
            )
        }

        item {
            SettingsListItem(
                headlineContent = "全局显示模式",
                supportingContent = "未单独设置显示模式的频道使用此模式；播放时调整的比例会按频道记忆。",
                trailingContent = settingsViewModel.videoPlayerDisplayMode.label,
                onSelect = toVideoPlayerDisplayModeScreen,
                link = true,
            )
        }

        item {
            SettingsListItem(
                headlineContent = "加载超时",
                supportingContent = "影响超时换源、断线重连",
                trailingContent = settingsViewModel.videoPlayerLoadTimeout.humanizeMs(),
                onSelect = toVideoPlayerLoadTimeoutScreen,
                link = true,
            )
        }

        item {
            SettingsUserAgentItem(
                value = settingsViewModel.videoPlayerUserAgent,
                onChange = { settingsViewModel.videoPlayerUserAgent = it },
            )
        }

        item {
            SettingsListItem(
                headlineContent = "RTSP 强制 TCP",
                supportingContent = "Media3 内核：关闭时优先使用 UDP，无数据时尝试 TCP；下次播放生效。",
                trailingContent = { Switch(settingsViewModel.videoPlayerRtspTcp, null) },
                onSelect = { settingsViewModel.videoPlayerRtspTcp = !settingsViewModel.videoPlayerRtspTcp },
            )
        }

        item {
            SettingsListItem(
                headlineContent = "回放重载 URL 优先",
                supportingContent = "开启后，跳转回看进度时重新请求回看地址；关闭时优先在当前视频内跳转，超出可定位范围时尝试重载。",
                trailingContent = { Switch(settingsViewModel.videoPlayerPlaybackReload, null) },
                onSelect = { settingsViewModel.videoPlayerPlaybackReload = !settingsViewModel.videoPlayerPlaybackReload },
            )
        }

        item {
            SettingsHeadersItem(
                value = settingsViewModel.videoPlayerHeaders,
                onChange = { settingsViewModel.videoPlayerHeaders = it },
            )
        }
    }
}

@Preview(device = "id:Android TV (720p)")
@Composable
private fun SettingsVideoPlayerScreenPreview() {
    MyTvTheme {
        SettingsVideoPlayerScreen(
            settingsViewModel = SettingsViewModel().apply {
                videoPlayerUserAgent =
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36"
                videoPlayerHeaders = "Accept: "
            }
        )
    }
}
