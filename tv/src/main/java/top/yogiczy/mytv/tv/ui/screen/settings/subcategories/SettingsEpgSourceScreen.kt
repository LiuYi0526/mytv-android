package top.yogiczy.mytv.tv.ui.screen.settings.subcategories

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.ListItem
import androidx.tv.material3.Text
import top.yogiczy.mytv.core.data.entities.epgsource.EpgSource
import top.yogiczy.mytv.core.data.entities.epgsource.EpgSourceList
import top.yogiczy.mytv.tv.ui.rememberChildPadding
import top.yogiczy.mytv.tv.ui.screen.components.AppScaffoldHeaderBtn
import top.yogiczy.mytv.tv.ui.screen.components.AppScreen
import top.yogiczy.mytv.tv.ui.utils.focusOnLaunched
import top.yogiczy.mytv.tv.ui.utils.handleKeyEvents

@Composable
fun SettingsEpgSourceScreen(
    modifier: Modifier = Modifier,
    currentEpgSourceProvider: () -> EpgSource = { EpgSource() },
    epgSourceListProvider: () -> EpgSourceList = { EpgSourceList() },
    onSave: (EpgSource?, EpgSource) -> Unit = { _, _ -> },
    onSetCurrent: (EpgSource) -> Unit = {},
    onDelete: (EpgSource) -> Unit = {},
    onClearCache: (EpgSource) -> Unit = {},
    onBackPressed: () -> Unit = {},
) {
    val sources = epgSourceListProvider()
    var selectedUrl by rememberSaveable { mutableStateOf<String?>(null) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var lastIndex by rememberSaveable { mutableIntStateOf(0) }
    val selected = sources.firstOrNull { it.url == selectedUrl }
    if (adding || selected != null) {
        key(adding, selectedUrl) {
            SettingsEpgSourceDetailScreen(
                original = selected, sources = sources,
                isDefault = selected?.url == currentEpgSourceProvider().url,
                onSave = { updated -> onSave(selected, updated); adding = false; selectedUrl = null },
                onSetDefault = { selected?.let(onSetCurrent) },
                onDelete = { selected?.let(onDelete); selectedUrl = null },
                onClearCache = { selected?.let(onClearCache) },
                onBackPressed = { adding = false; selectedUrl = null },
            )
        }
        return
    }
    val padding = rememberChildPadding()
    AppScreen(modifier = modifier, header = { Text("节目单") }, canBack = true, onBackPressed = onBackPressed,
        headerExtra = { AppScaffoldHeaderBtn(title = "添加节目单", imageVector = Icons.Outlined.Add, onSelect = { adding = true }) }) {
        LazyColumn(contentPadding = padding.copy(top = 10.dp).paddingValues, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (sources.isEmpty()) item { Text("暂无节目单，请选择右上角“添加节目单”", Modifier.padding(16.dp)) }
            itemsIndexed(sources, key = { _, source -> source.url }) { index, source ->
                val isDefault = source.url == currentEpgSourceProvider().url
                ListItem(
                    modifier = (if (index == lastIndex.coerceAtMost(sources.lastIndex)) Modifier.focusOnLaunched() else Modifier)
                        .handleKeyEvents(onSelect = { lastIndex = index; selectedUrl = source.url }),
                    selected = false, onClick = {},
                    leadingContent = { Text("#${index + 1}") }, headlineContent = { Text(source.name) },
                    supportingContent = { Text(source.url, maxLines = 2) },
                    trailingContent = {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (isDefault) { Text("默认"); Icon(Icons.Default.CheckCircle, "默认节目单", Modifier.size(18.dp)) }
                            Icon(Icons.Default.ChevronRight, "编辑节目单")
                        }
                    },
                )
            }
        }
    }
}
