package top.yogiczy.mytv.tv.ui.screen.settings.components

import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import top.yogiczy.mytv.core.data.utils.Constants
import top.yogiczy.mytv.core.util.utils.headersValid
import top.yogiczy.mytv.tv.ui.material.LocalPopupManager
import top.yogiczy.mytv.tv.ui.material.SimplePopup
import top.yogiczy.mytv.tv.ui.screen.push.PushContent

@Composable
internal fun SettingsUserAgentItem(value: String, onChange: (String) -> Unit) {
    SettingsRequestTextItem(
        title = "User-Agent",
        value = value,
        description = "设置播放器的用户代理，某些视频源需要特定的 UA 才能正常播放。",
        editorDescription = "留空恢复默认值；重新播放后生效。",
        onChange = { onChange(it.trim().ifBlank { Constants.VIDEO_PLAYER_USER_AGENT }) },
    )
}

@Composable
internal fun SettingsHeadersItem(value: String, onChange: (String) -> Unit) {
    SettingsRequestTextItem(
        title = "自定义请求头",
        value = value,
        description = "为播放器添加 HTTP 请求头，每行一个，格式为“键: 值”。",
        editorDescription = "例如：Referer: https://example.com/\n留空清除自定义请求头；重新播放后生效。",
        multiline = true,
        invalidValue = !value.headersValid(),
        validationError = { if (it.trim().headersValid()) null else "格式有误，请每行填写一个“键: 值”，不要留空行。" },
        onChange = { onChange(it.trim()) },
    )
}

@Composable
private fun SettingsRequestTextItem(
    title: String,
    value: String,
    description: String,
    editorDescription: String,
    multiline: Boolean = false,
    invalidValue: Boolean = false,
    validationError: (String) -> String? = { null },
    onChange: (String) -> Unit,
) {
    var visible by remember { mutableStateOf(false) }
    var scanQrCode by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val popupManager = LocalPopupManager.current

    SettingsListItem(
        modifier = Modifier.focusRequester(focus),
        headlineContent = title,
        supportingContent = description,
        trailingContent = {
            Text(
                text = value.ifBlank { "未设置" },
                modifier = Modifier.widthIn(max = 410.dp),
                maxLines = if (multiline) 2 else 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingIcon = if (invalidValue) Icons.Default.ErrorOutline else Icons.Default.Edit,
        onSelect = {
            popupManager.push(focus, emitter = true)
            scanQrCode = false
            visible = true
        },
    )

    SimplePopup(visibleProvider = { visible }, onDismissRequest = { visible = false }) {
        if (scanQrCode) {
            PushContent()
        } else {
            SettingsTextEditor(
                title = title,
                initial = value,
                multiline = multiline,
                description = editorDescription,
                onScanQrCode = { scanQrCode = true },
                validationError = validationError,
                onDismiss = { visible = false },
                onConfirm = {
                    onChange(it)
                    visible = false
                },
            )
        }
    }
}
