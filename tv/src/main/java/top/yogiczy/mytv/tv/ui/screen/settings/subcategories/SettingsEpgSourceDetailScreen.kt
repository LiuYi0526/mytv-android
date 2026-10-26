package top.yogiczy.mytv.tv.ui.screen.settings.subcategories

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import top.yogiczy.mytv.core.data.entities.epgsource.EpgSource
import top.yogiczy.mytv.core.data.entities.epgsource.EpgSourceCatalog
import top.yogiczy.mytv.tv.ui.material.*
import top.yogiczy.mytv.tv.ui.screen.settings.components.SettingsListItem
import top.yogiczy.mytv.tv.ui.utils.focusOnLaunched
import top.yogiczy.mytv.tv.utlis.HttpServer

@Composable
internal fun SettingsEpgSourceDetailScreen(
    original: EpgSource?, sources: List<EpgSource>, isDefault: Boolean,
    onSave: (EpgSource) -> Unit, onSetDefault: () -> Unit, onClearCache: () -> Unit,
    onDelete: () -> Unit, onBackPressed: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(original?.name.orEmpty()) }
    var url by rememberSaveable { mutableStateOf(original?.url.orEmpty()) }
    var deleting by remember { mutableStateOf(false) }
    SourceDetailLayout(
        adding = original == null, headerExtra = null, onBackPressed = onBackPressed,
        addTitle = "添加节目单", editTitle = "节目单", qrUrl = "${HttpServer.serverUrl}/#epg",
        qrDescription = "使用手机扫码配置节目单",
    ) { firstFocus ->
        item {
            SourceEditField(modifier = Modifier.focusRequester(firstFocus).focusOnLaunched(),
                title = "节目单名称", value = name, hint = "请输入名称", description = "用于区分不同节目单，请勿重复。",
                compact = original == null, onChange = { name = it })
        }
        item {
            SourceEditField(title = "节目单链接", value = url, hint = "请输入节目单链接",
                description = "支持 XML、XML.GZ 节目单的 HTTP 或 HTTPS 链接。", uri = true,
                compact = original == null, onChange = { url = it })
        }
        item {
            SettingsListItem(headlineContent = if (original == null) "添加节目单" else "保存修改", onSelect = {
                try { onSave(EpgSourceCatalog.prepare(name, url, sources.filter { it.url != original?.url })) }
                catch (ex: IllegalArgumentException) { Snackbar.show(ex.message ?: "请检查输入内容", type = SnackbarType.ERROR) }
            })
        }
        if (original != null) {
            item { SettingsListItem(headlineContent = if (isDefault) "默认节目单" else "设为默认节目单",
                supportingContent = if (isDefault) "未单独指定节目单的订阅源使用此节目单" else null,
                onSelect = { if (!isDefault) onSetDefault() }) }
            item { SettingsListItem(headlineContent = "清除缓存", onSelect = onClearCache) }
            item { SettingsListItem(headlineContent = "删除节目单", onSelect = { deleting = true }) }
        }
    }
    SimplePopup(visibleProvider = { deleting }, onDismissRequest = { deleting = false }) {
        Drawer(modifier = Modifier.width(460.dp), position = DrawerPosition.Center,
            header = { Text("删除节目单") }, onDismissRequest = { deleting = false }) {
            Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Text("确定删除“${original?.name}”吗？引用它的订阅源将移除此关联；未指定节目单时使用默认节目单。")
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    SourceDialogButton("取消", Modifier.focusOnLaunched(), onClick = { deleting = false })
                    SourceDialogButton("删除", onClick = { deleting = false; onDelete() })
                }
            }
        }
    }
}
