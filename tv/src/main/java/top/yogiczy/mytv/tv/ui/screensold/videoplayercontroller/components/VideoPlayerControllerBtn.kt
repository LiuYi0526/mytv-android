package top.yogiczy.mytv.tv.ui.screensold.videoplayercontroller.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.tv.material3.Icon
import androidx.tv.material3.IconButton
import androidx.tv.material3.ButtonColors
import androidx.tv.material3.IconButtonDefaults
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.LocalContentColor
import top.yogiczy.mytv.tv.ui.utils.handleKeyEvents

@Composable
fun VideoPlayerControllerBtn(
    modifier: Modifier = Modifier,
    onSelect: () -> Unit = {},
    colors: ButtonColors = IconButtonDefaults.colors(
        containerColor = Color.White.copy(0.08f),
        contentColor = LocalContentColor.current,
        disabledContainerColor = Color.White.copy(0.03f),
    ),
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    IconButton(
        modifier = modifier
            .handleKeyEvents(onSelect = { if (enabled) onSelect() }),
        onClick = {},
        enabled = enabled,
        shape = IconButtonDefaults.shape(shape = RoundedCornerShape(10.dp)),
        colors = colors,
    ) {
        content()
    }
}

@Composable
fun VideoPlayerControllerBtn(
    modifier: Modifier = Modifier,
    imageVector: ImageVector,
    onSelect: () -> Unit = {},
    enabled: Boolean = true,
) {
    VideoPlayerControllerBtn(
        modifier = modifier,
        onSelect = onSelect,
        enabled = enabled,
        content = { Icon(imageVector = imageVector, contentDescription = null) },
    )
}
