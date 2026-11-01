package top.yogiczy.mytv.tv.ui.screen.settings.subcategories

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.ListItem
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import top.yogiczy.mytv.core.data.entities.epgsource.EpgSource
import top.yogiczy.mytv.core.data.entities.iptvsource.IptvSource
import top.yogiczy.mytv.core.data.entities.iptvsource.IptvSourceCacheInfo
import top.yogiczy.mytv.core.data.entities.iptvsource.IptvSourceList
import top.yogiczy.mytv.core.data.repositories.iptv.IptvRepository
import top.yogiczy.mytv.core.util.utils.humanizeBytes
import top.yogiczy.mytv.tv.ui.material.PaddedLazyColumn
import top.yogiczy.mytv.tv.ui.material.Snackbar
import top.yogiczy.mytv.tv.ui.material.Tag
import top.yogiczy.mytv.tv.ui.material.TagDefaults
import top.yogiczy.mytv.tv.ui.rememberChildPadding
import top.yogiczy.mytv.tv.ui.screen.components.AppScaffoldHeaderBtn
import top.yogiczy.mytv.tv.ui.screen.components.AppScreen
import top.yogiczy.mytv.tv.ui.utils.focusOnLaunched
import top.yogiczy.mytv.tv.ui.utils.handleKeyEvents

@Composable
fun SettingsIptvSourceScreen(
    modifier: Modifier = Modifier,
    currentIptvSourceProvider: () -> IptvSource = { IptvSource() },
    iptvSourceListProvider: () -> IptvSourceList = { IptvSourceList() },
    epgSourcesProvider: () -> List<EpgSource> = { emptyList() },
    defaultEpgSourceProvider: () -> EpgSource = { EpgSource() },
    onSave: (IptvSource?, IptvSource) -> Unit = { _, _ -> },
    onSetCurrent: (IptvSource) -> Unit = {},
    onDelete: (IptvSource) -> Unit = {},
    onUpdate: suspend (IptvSource) -> Unit = {},
    onBackPressed: () -> Unit = {},
) {
    val sources = iptvSourceListProvider()
    var selectedUrl by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedName by rememberSaveable { mutableStateOf<String?>(null) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var lastIndex by rememberSaveable { mutableIntStateOf(0) }
    var refreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val selected = sources.firstOrNull { it.url == selectedUrl && it.name == selectedName }
    if (sources.isEmpty() || adding || selected != null) {
        key(adding, selectedUrl, selectedName) {
            SettingsIptvSourceDetailScreen(
                original = selected,
                sources = sources,
                epgSources = epgSourcesProvider(),
                defaultEpgSource = defaultEpgSourceProvider(),
                isCurrent = selected == currentIptvSourceProvider(),
                onSave = { updated ->
                    onSave(selected, updated)
                    adding = false
                    selectedUrl = null
                    selectedName = null
                },
                onSetCurrent = { selected?.let(onSetCurrent) },
                onUpdate = { selected?.let { onUpdate(it) } },
                onDelete = { selected?.let(onDelete); selectedUrl = null; selectedName = null },
                onBackPressed = {
                    if (sources.isEmpty()) onBackPressed()
                    else { adding = false; selectedUrl = null; selectedName = null }
                },
            )
        }
        return
    }
    val padding = rememberChildPadding()
    var cacheInfo by remember { mutableStateOf<Map<IptvSource, IptvSourceCacheInfo?>>(emptyMap()) }
    LaunchedEffect(sources) {
        for (source in sources) {
            cacheInfo = cacheInfo + (source to IptvRepository(source).getCachedInfo())
        }
    }
    AppScreen(
        modifier = modifier,
        header = { Text("订阅源") },
        headerExtra = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppScaffoldHeaderBtn(title = if (refreshing) "刷新中" else "刷新全部", imageVector = Icons.Outlined.Refresh, onSelect = {
                    if (!refreshing) scope.launch {
                        refreshing = true
                        var failures = 0
                        try {
                            for (source in sources.toList()) {
                                try {
                                    val repository = IptvRepository(source)
                                    repository.getChannelGroupList(0)
                                    cacheInfo = cacheInfo + (source to repository.getCachedInfo())
                                }
                                catch (ex: CancellationException) { throw ex }
                                catch (_: Exception) { failures++ }
                            }
                            Snackbar.show(if (failures == 0) "订阅源已刷新" else "刷新完成，${failures}个订阅源失败")
                        } finally { refreshing = false }
                    }
                })
                AppScaffoldHeaderBtn(title = "添加订阅源", imageVector = Icons.Outlined.Add, onSelect = { adding = true })
            }
        },
        canBack = true,
        onBackPressed = onBackPressed,
    ) {
        PaddedLazyColumn(
            state = listState,
            contentPadding = padding.copy(top = 10.dp).paddingValues,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (sources.isEmpty()) item {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("暂无订阅源")
                    Text("选择右上角“添加订阅源”，扫码或手动配置。")
                }
            }
            itemsIndexed(sources, key = { _, source -> source.name + "\n" + source.url }) { index, source ->
                val open = { lastIndex = index; selectedUrl = source.url; selectedName = source.name }
                ListItem(
                    modifier = (if (index == lastIndex) Modifier.focusOnLaunched() else Modifier).handleKeyEvents(onSelect = open),
                    selected = false,
                    onClick = {},
                    leadingContent = { Text("#${index + 1}") },
                    headlineContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(source.name, Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.width(8.dp))
                            Tag(if (source.isLocal) "本地" else "远程", colors = TagDefaults.colors(
                                containerColor = LocalContentColor.current.copy(alpha = 0.1f),
                            ))
                        }
                    },
                    supportingContent = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(source.url, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            val info = cacheInfo[source]
                            val statistics = remember(info, cacheInfo.containsKey(source)) {
                                when {
                                    !cacheInfo.containsKey(source) -> "正在读取缓存…"
                                    info == null -> "暂无缓存统计，请更新订阅源"
                                    else -> "分组：${info.groupCount}，频道：${info.channelCount} | 缓存：${info.cacheBytes.humanizeBytes()} | 更新：" +
                                        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(info.updatedAt))
                                }
                            }
                            Text(statistics, style = MaterialTheme.typography.bodySmall,
                                color = LocalContentColor.current.copy(alpha = 0.7f),
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (source == currentIptvSourceProvider()) Icon(Icons.Default.CheckCircle, "当前订阅源", Modifier.size(18.dp))
                            Icon(Icons.Default.ChevronRight, "查看订阅源")
                        }
                    },
                )
            }
        }
    }
}
