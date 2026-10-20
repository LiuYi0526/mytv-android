package top.yogiczy.mytv.tv.ui.screen.settings.categories

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.tooling.preview.Preview
import androidx.tv.material3.Switch
import androidx.tv.material3.Text
import top.yogiczy.mytv.tv.ui.screen.settings.SettingsViewModel
import top.yogiczy.mytv.tv.ui.screen.settings.components.SettingsCategoryScreen
import top.yogiczy.mytv.tv.ui.screen.settings.components.SettingsListItem
import top.yogiczy.mytv.tv.ui.screen.settings.settingsVM
import top.yogiczy.mytv.tv.ui.theme.MyTvTheme

@Composable
fun SettingsControlScreen(
    modifier: Modifier = Modifier,
    settingsViewModel: SettingsViewModel = settingsVM,
    toControlKeyActionsScreen: () -> Unit = {},
    onBackPressed: () -> Unit = {},
) {
    SettingsCategoryScreen(
        modifier = modifier,
        header = { Text("设置 / 控制") },
        onBackPressed = onBackPressed,
    ) { firstItemFocusRequester ->
        item {
            SettingsListItem(
                modifier = Modifier.focusRequester(firstItemFocusRequester),
                headlineContent = "按键操作映射",
                supportingContent = "自定义直播界面的按键短按、长按和屏幕分区操作",
                link = true,
                onSelect = toControlKeyActionsScreen,
            )
        }
        item {
            val enable = settingsViewModel.iptvChannelNoSelectEnable

            SettingsListItem(
                headlineContent = "数字选台",
                supportingContent = "通过数字键选择频道",
                trailingContent = {
                    Switch(enable, null)
                },
                onSelect = {
                    settingsViewModel.iptvChannelNoSelectEnable = !enable
                },
            )
        }

        item {
            val flip = settingsViewModel.iptvChannelChangeFlip

            SettingsListItem(
                headlineContent = "换台反转",
                supportingContent = "反转未自定义的上/下键及上下滑动的换台方向",
                trailingContent = {
                    Switch(flip, null)
                },
                onSelect = {
                    settingsViewModel.iptvChannelChangeFlip = !flip
                },
            )
        }

        item {
            val loop = settingsViewModel.iptvChannelChangeListLoop

            SettingsListItem(
                headlineContent = "换台列表首尾循环",
                trailingContent = {
                    Switch(loop, null)
                },
                onSelect = {
                    settingsViewModel.iptvChannelChangeListLoop = !loop
                },
            )
        }
    }
}

@Preview(device = "id:Android TV (720p)")
@Composable
private fun SettingsControlScreenPreview() {
    MyTvTheme {
        SettingsControlScreen()
    }
}
