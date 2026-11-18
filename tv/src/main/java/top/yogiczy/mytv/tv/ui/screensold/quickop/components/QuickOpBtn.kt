package top.yogiczy.mytv.tv.ui.screensold.quickop.components

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import top.yogiczy.mytv.tv.ui.utils.handleKeyEvents

@Composable
fun QuickOpBtn(
    modifier: Modifier = Modifier,
    title: String,
    imageVector: ImageVector? = null,
    onSelect: () -> Unit = {},
    onLongSelect: () -> Unit = {},
    description: String = title,
    enabled: Boolean = true,
) {
    var isFocused by remember { mutableStateOf(false) }

    val colorScheme = MaterialTheme.colorScheme
    val contentColor = colorScheme.onSurface.copy(alpha = if (!enabled) 0.3f else if (isFocused) 1f else 0.7f)

    Column(
        modifier = modifier
            .width(88.dp)
            .height(94.dp)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = description
                if (enabled) onClick { onSelect(); true } else disabled()
            }
            .handleKeyEvents(onSelect = onSelect.takeIf { enabled }, onLongSelect = onLongSelect.takeIf { enabled })
            .onFocusChanged { isFocused = it.hasFocus || it.isFocused }
            .focusProperties { canFocus = enabled }
            .focusable(enabled = enabled)
            .padding(vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier.size(44.dp).background(
                if (isFocused) colorScheme.inverseSurface else Color.Transparent,
                RoundedCornerShape(12.dp),
            ),
            contentAlignment = Alignment.Center,
        ) {
            imageVector?.let {
                Icon(it, null, Modifier.size(24.dp),
                    tint = if (isFocused) colorScheme.inverseOnSurface else contentColor)
            }
        }
        Text(
            text = title,
            modifier = Modifier.padding(horizontal = 2.dp),
            color = contentColor,
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.Center,
            minLines = 2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
