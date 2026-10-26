package top.yogiczy.mytv.tv.ui.screen.settings.subcategories

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Checkbox
import androidx.tv.material3.Text
import top.yogiczy.mytv.core.data.entities.epgsource.EpgSource
import top.yogiczy.mytv.tv.ui.material.*
import top.yogiczy.mytv.tv.ui.screen.settings.components.SettingsListItem
import top.yogiczy.mytv.tv.ui.utils.focusOnLaunched

@Composable
internal fun SourceEpgField(
    epgUrls: List<String>, sources: List<EpgSource>, defaultSource: EpgSource,
    compact: Boolean, onChange: (List<String>) -> Unit,
) {
    var visible by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val popup = LocalPopupManager.current
    val selectedNames = if (epgUrls.isEmpty()) "使用默认节目单" + defaultSource.name.takeIf { it.isNotBlank() }?.let { "（$it）" }.orEmpty()
        else epgUrls.mapNotNull { url -> sources.firstOrNull { it.url == url }?.name }.joinToString("、")
    SettingsListItem(
        modifier = Modifier.focusRequester(focus), headlineContent = "节目单",
        supportingContent = if (compact) selectedNames else "选择已有节目单；未指定时使用默认节目单",
        trailingContent = { if (!compact) Text(selectedNames, Modifier.widthIn(max = 360.dp), maxLines = 2) },
        link = true, onSelect = { popup.push(focus, true); visible = true },
    )
    SimplePopup(visibleProvider = { visible }, onDismissRequest = { visible = false }) {
        var selection by remember { mutableStateOf(epgUrls) }
        Drawer(modifier = Modifier.width(660.dp), position = DrawerPosition.Center,
            header = { Text("选择节目单") }, onDismissRequest = { visible = false }) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                LazyColumn(Modifier.heightIn(max = 270.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    item {
                        SettingsListItem(modifier = Modifier.focusOnLaunched(), headlineContent = "使用默认节目单",
                            supportingContent = defaultSource.name.ifBlank { "尚未设置默认节目单" },
                            trailingContent = { Checkbox(selection.isEmpty(), null) }, onSelect = { selection = emptyList() })
                    }
                    itemsIndexed(sources, key = { _, source -> source.url }) { _, source ->
                        SettingsListItem(headlineContent = source.name, supportingContent = source.url,
                            trailingContent = { Checkbox(source.url in selection, null) },
                            onSelect = { selection = if (source.url in selection) selection - source.url else selection + source.url })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    SourceDialogButton("确定", onClick = { onChange(selection); visible = false })
                    SourceDialogButton("取消", onClick = { visible = false })
                }
            }
        }
    }
}
