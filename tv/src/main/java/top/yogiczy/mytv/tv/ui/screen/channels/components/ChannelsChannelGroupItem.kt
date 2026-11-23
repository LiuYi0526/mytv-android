package top.yogiczy.mytv.tv.ui.screen.channels.components

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import top.yogiczy.mytv.core.data.entities.channel.ChannelGroup
import top.yogiczy.mytv.tv.ui.theme.MyTvTheme
import top.yogiczy.mytv.tv.ui.utils.handleKeyEvents
import top.yogiczy.mytv.tv.ui.utils.ifElse

@Composable
fun ChannelsChannelGroupItem(
    modifier: Modifier = Modifier,
    channelGroupProvider: () -> ChannelGroup = { ChannelGroup() },
    isSelectedProvider: () -> Boolean = { false },
    selectOnFocus: Boolean = false,
    onChannelGroupSelected: () -> Unit = {},
) {
    val channelGroup = channelGroupProvider()
    val isSelected = isSelectedProvider()

    var isFocused by remember { mutableStateOf(false) }

    val colorScheme = MaterialTheme.colorScheme
    val localContentColor = LocalContentColor.current
    val containerColor = remember(isFocused) {
        if (isFocused) colorScheme.onSurface
        else colorScheme.onSurface.copy(0.1f)
    }
    val contentColor = remember(isFocused) {
        if (isFocused) colorScheme.surface
        else localContentColor
    }

    Box(
        modifier = modifier
            .handleKeyEvents(onSelect = onChannelGroupSelected)
            .onFocusChanged {
                val gainedFocus = it.isFocused && !isFocused
                isFocused = it.hasFocus
                if (selectOnFocus && gainedFocus && !isSelected) onChannelGroupSelected()
            }
            .focusable()
            .background(containerColor, MaterialTheme.shapes.extraLarge),
    ) {
        Row(
            modifier = Modifier
                .ifElse(
                    isSelected,
                    Modifier.padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                    Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                ),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CompositionLocalProvider(LocalContentColor provides contentColor){
                Text(channelGroup.name, style = MaterialTheme.typography.labelLarge)

                if (isSelected) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

@Preview
@Composable
private fun ChannelsChannelGroupItemPreview() {
    MyTvTheme {
        Row(
            modifier = Modifier.padding(20.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            ChannelsChannelGroupItem(
                channelGroupProvider = { ChannelGroup.EXAMPLE },
            )

            ChannelsChannelGroupItem(
                channelGroupProvider = { ChannelGroup.EXAMPLE },
                isSelectedProvider = { true },
            )
        }
    }
}