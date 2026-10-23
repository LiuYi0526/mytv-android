package top.yogiczy.mytv.tv.ui.screen.settings.subcategories

import android.app.Activity
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Text
import top.yogiczy.mytv.core.data.entities.iptvsource.IptvSource
import top.yogiczy.mytv.core.data.entities.iptvsource.IptvSourceEdit
import top.yogiczy.mytv.tv.ui.material.*
import top.yogiczy.mytv.tv.ui.screen.components.AppScaffoldHeaderBtn
import top.yogiczy.mytv.tv.ui.screen.settings.components.SettingsCategoryScreen
import top.yogiczy.mytv.tv.ui.screen.settings.components.SettingsListItem
import top.yogiczy.mytv.tv.ui.utils.focusOnLaunched
import top.yogiczy.mytv.tv.ui.utils.handleKeyEvents
import top.yogiczy.mytv.tv.ui.utils.clickableNoIndication

@Composable
fun SettingsIptvSourceDetailScreen(
    original: IptvSource?,
    sources: List<IptvSource>,
    isCurrent: Boolean,
    onSave: (IptvSource) -> Unit,
    onSetCurrent: () -> Unit,
    onClearCache: () -> Unit,
    onDelete: () -> Unit,
    onBackPressed: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(original?.name.orEmpty()) }
    var url by rememberSaveable { mutableStateOf(original?.url.orEmpty()) }
    var epg by rememberSaveable { mutableStateOf(original?.epgUrls?.joinToString("\n").orEmpty()) }
    var deleting by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    val popupManager = LocalPopupManager.current
    val deleteFocus = remember { FocusRequester() }
    val moreFocus = remember { FocusRequester() }

    fun save() {
        val updated = try {
            IptvSourceEdit.prepare(original ?: IptvSource(), name, url, epg, sources.filter { it != original })
        } catch (ex: Exception) {
            Snackbar.show(ex.message ?: "请检查输入内容", type = SnackbarType.ERROR)
            return
        }
        onSave(updated)
    }

    SettingsCategoryScreen(
        header = { Text(if (original == null) "添加订阅源" else "订阅源") },
        headerExtra = if (original != null) {{
            AppScaffoldHeaderBtn(modifier = Modifier.focusRequester(moreFocus), title = "更多", imageVector = Icons.Default.MoreHoriz,
                onSelect = { popupManager.push(moreFocus, true); more = true })
        }} else null,
        onBackPressed = onBackPressed,
    ) { firstFocus ->
        item {
            SourceEditField(
                modifier = Modifier.focusRequester(firstFocus).focusOnLaunched(),
                title = "订阅源名称", value = name, hint = "请输入名称",
                description = "用于区分不同订阅源的名称，请勿重复。",
                onChange = { name = it },
            )
        }
        item {
            SourceEditField(
                title = "订阅源链接", value = url, hint = "请输入订阅源链接",
                description = "订阅源的链接地址，支持网络链接或本地文件路径。",
                uri = true, onChange = { url = it },
            )
        }
        item {
            SourceEditField(
                title = "节目单链接", value = epg, hint = "节目单链接",
                description = "启用“节目单跟随直播源”后使用，留空自动读取。支持 XML、XML.GZ；多个链接换行分隔。",
                multiline = true, uri = true, onChange = { epg = it },
            )
        }
        item { Box(Modifier.fillMaxWidth().padding(vertical = 8.dp).height(1.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))) }
        item { SettingsListItem(headlineContent = if (original == null) "添加订阅源" else "编辑订阅源", supportingContent = "保存以上修改", onSelect = ::save) }
        if (original != null) {
            item {
                SettingsListItem(
                    modifier = Modifier.focusRequester(deleteFocus), headlineContent = "删除订阅源",
                    onSelect = { popupManager.push(deleteFocus, true); deleting = true },
                )
            }
        }
    }
    SimplePopup(visibleProvider = { more }, onDismissRequest = { more = false }) {
        Drawer(modifier = Modifier.width(360.dp), position = DrawerPosition.Center, header = { Text("订阅源操作") }, onDismissRequest = { more = false }) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!isCurrent) SourceDialogButton("设为当前订阅源", modifier = Modifier.fillMaxWidth().focusOnLaunched(), onClick = { more = false; onSetCurrent() })
                SourceDialogButton("清除缓存", modifier = if (isCurrent) Modifier.fillMaxWidth().focusOnLaunched() else Modifier.fillMaxWidth(), onClick = { more = false; onClearCache() })
            }
        }
    }
    SimplePopup(visibleProvider = { deleting }, onDismissRequest = { deleting = false }) {
        Drawer(modifier = Modifier.width(440.dp), position = DrawerPosition.Center, header = { Text("删除订阅源") }, onDismissRequest = { deleting = false }) {
            Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Text("确定删除“${original?.name}”吗？" + if (isCurrent) "删除后将切换到其他可用订阅源。" else "")
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    SourceDialogButton("取消", onClick = { deleting = false }, modifier = Modifier.focusOnLaunched())
                    SourceDialogButton("删除", onClick = { deleting = false; onDelete() })
                }
            }
        }
    }
}

@Composable
private fun SourceEditField(
    modifier: Modifier = Modifier,
    title: String, value: String, hint: String, description: String,
    multiline: Boolean = false, uri: Boolean = false,
    onChange: (String) -> Unit,
) {
    var visible by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val popupManager = LocalPopupManager.current
    SettingsListItem(
        modifier = modifier.focusRequester(focus), headlineContent = title, supportingContent = description,
        trailingContent = {
            Text(
                text = value.ifBlank { hint }, modifier = Modifier.widthIn(max = 410.dp),
                maxLines = if (multiline) 2 else 1, overflow = TextOverflow.Ellipsis,
                color = LocalContentColor.current.copy(alpha = if (value.isBlank()) 0.5f else 1f),
            )
        },
        link = true, onSelect = { popupManager.push(focus, true); visible = true },
    )
    SimplePopup(visibleProvider = { visible }, onDismissRequest = { visible = false }) {
        SourceTextEditor(title, value, multiline, uri, onDismiss = { visible = false }, onConfirm = { onChange(it); visible = false })
    }
}

@Composable
private fun SourceTextEditor(
    title: String, initial: String, multiline: Boolean, uri: Boolean,
    onDismiss: () -> Unit, onConfirm: (String) -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(initial) }
    val keyboard = LocalSoftwareKeyboardController.current
    val activity = LocalContext.current as? Activity
    DisposableEffect(activity) {
        val window = activity?.window
        val previous = window?.attributes?.softInputMode
        if (window != null && previous != null) {
            // Insets below position the editor; system panning would move it a second time.
            window.setSoftInputMode((previous and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST.inv()) or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        }
        onDispose { if (window != null && previous != null) window.setSoftInputMode(previous) }
    }
    val color = MaterialTheme.colorScheme.onSurface
    Box(Modifier.fillMaxSize().imePadding().padding(12.dp), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.large)
                .clickableNoIndication { }.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            BasicTextField(
                value = text, onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp, max = 100.dp)
                    .border(1.dp, color.copy(alpha = 0.6f), MaterialTheme.shapes.small)
                    .padding(8.dp).focusOnLaunched(),
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = color),
                cursorBrush = SolidColor(color), singleLine = !multiline,
                keyboardOptions = KeyboardOptions(keyboardType = if (uri) KeyboardType.Uri else KeyboardType.Text, imeAction = if (multiline) ImeAction.Default else ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { keyboard?.hide(); onConfirm(text) }),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                SourceDialogButton("确定", onClick = { keyboard?.hide(); onConfirm(text) })
                SourceDialogButton("取消", onClick = { keyboard?.hide(); onDismiss() })
            }
        }
    }
}

@Composable
private fun SourceDialogButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = modifier.handleKeyEvents(onSelect = onClick)) { Text(text) }
}
