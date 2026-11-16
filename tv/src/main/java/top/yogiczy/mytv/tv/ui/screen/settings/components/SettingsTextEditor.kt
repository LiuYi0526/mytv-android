package top.yogiczy.mytv.tv.ui.screen.settings.components

import android.app.Activity
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import top.yogiczy.mytv.tv.ui.utils.clickableNoIndication
import top.yogiczy.mytv.tv.ui.utils.focusOnLaunched
import top.yogiczy.mytv.tv.ui.utils.handleKeyEvents

@Composable
internal fun SettingsTextEditor(
    title: String,
    initial: String,
    multiline: Boolean = false,
    uri: Boolean = false,
    description: String? = null,
    onScanQrCode: (() -> Unit)? = null,
    validationError: (String) -> String? = { null },
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(initial) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val keyboard = LocalSoftwareKeyboardController.current
    val confirm = {
        error = validationError(text)
        if (error == null) {
            keyboard?.hide()
            onConfirm(text)
        }
    }
    val activity = LocalContext.current as? Activity
    DisposableEffect(activity) {
        val window = activity?.window
        val previous = window?.attributes?.softInputMode
        if (window != null && previous != null) {
            // Insets position the editor; system panning would move it a second time.
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
            description?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            BasicTextField(
                value = text, onValueChange = { text = it; error = null },
                modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp, max = 100.dp)
                    .border(1.dp, color.copy(alpha = 0.6f), MaterialTheme.shapes.small)
                    .padding(8.dp).focusOnLaunched(),
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = color),
                cursorBrush = SolidColor(color), singleLine = !multiline,
                keyboardOptions = KeyboardOptions(keyboardType = if (uri) KeyboardType.Uri else KeyboardType.Text, imeAction = if (multiline) ImeAction.Default else ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { confirm() }),
            )
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                EditorButton("确定", onClick = confirm)
                EditorButton("取消", onClick = { keyboard?.hide(); onDismiss() })
                onScanQrCode?.let { scan ->
                    EditorButton("扫码修改", onClick = { keyboard?.hide(); scan() })
                }
            }
        }
    }
}

@Composable
private fun EditorButton(text: String, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.handleKeyEvents(onSelect = onClick)) { Text(text) }
}
