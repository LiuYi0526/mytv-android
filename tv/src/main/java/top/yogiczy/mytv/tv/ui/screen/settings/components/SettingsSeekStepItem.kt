package top.yogiczy.mytv.tv.ui.screen.settings.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.ListItem
import androidx.tv.material3.Text
import top.yogiczy.mytv.core.data.media.PlaybackSeekSteps
import top.yogiczy.mytv.core.util.utils.humanizeMs
import top.yogiczy.mytv.tv.ui.material.Drawer
import top.yogiczy.mytv.tv.ui.material.DrawerPosition
import top.yogiczy.mytv.tv.ui.material.LocalPopupManager
import top.yogiczy.mytv.tv.ui.material.SimplePopup
import top.yogiczy.mytv.tv.ui.utils.focusOnLaunchedSaveable
import top.yogiczy.mytv.tv.ui.utils.handleKeyEvents
import top.yogiczy.mytv.tv.ui.utils.ifElse

@Composable
internal fun SettingsSeekStepItem(title: String, seconds: Int, onChange: (Int) -> Unit) {
    var picking by remember { mutableStateOf(false) }
    val returnFocus = remember { FocusRequester() }
    val popupManager = LocalPopupManager.current

    SettingsListItem(
        modifier = Modifier.focusRequester(returnFocus),
        headlineContent = title,
        trailingContent = { Text((seconds * 1_000L).humanizeMs()) },
        trailingIcon = Icons.Default.ChevronRight,
        onSelect = {
            popupManager.push(returnFocus, emitter = true)
            picking = true
        },
    )

    SimplePopup(visibleProvider = { picking }, onDismissRequest = { picking = false }) {
        Drawer(
            modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth(),
            position = DrawerPosition.Center,
            header = { Text(title) },
            onDismissRequest = { picking = false },
        ) {
            val options = PlaybackSeekSteps.optionsSeconds
            LazyVerticalGrid(
                columns = GridCells.Adaptive(108.dp),
                modifier = Modifier.heightIn(max = 212.dp),
                state = rememberLazyGridState(initialFirstVisibleItemIndex = options.indexOf(seconds).coerceAtLeast(0)),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(options, key = { it }) { option ->
                    val selected = option == seconds
                    ListItem(
                        modifier = Modifier.ifElse(selected, Modifier.focusOnLaunchedSaveable())
                            .handleKeyEvents(onSelect = { onChange(option); picking = false }),
                        selected = selected,
                        onClick = {},
                        headlineContent = { Text((option * 1_000L).humanizeMs(), maxLines = 1) },
                        trailingContent = { if (selected) Icon(Icons.Default.Check, contentDescription = null) },
                    )
                }
            }
        }
    }
}
