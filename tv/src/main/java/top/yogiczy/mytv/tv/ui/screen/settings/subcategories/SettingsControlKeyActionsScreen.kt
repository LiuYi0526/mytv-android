package top.yogiczy.mytv.tv.ui.screen.settings.subcategories

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import top.yogiczy.mytv.core.data.entities.control.ControlAction
import top.yogiczy.mytv.core.data.entities.control.ControlKey
import top.yogiczy.mytv.core.data.entities.control.ControlKeyActions
import top.yogiczy.mytv.tv.ui.material.SimplePopup
import top.yogiczy.mytv.tv.ui.material.Snackbar
import top.yogiczy.mytv.tv.ui.screen.settings.SettingsViewModel
import top.yogiczy.mytv.tv.ui.screen.settings.components.SettingsCategoryScreen
import top.yogiczy.mytv.tv.ui.screen.settings.components.SettingsListItem
import top.yogiczy.mytv.tv.ui.screen.settings.settingsVM
import top.yogiczy.mytv.tv.ui.theme.colors
import top.yogiczy.mytv.tv.ui.utils.clickableNoIndication
import top.yogiczy.mytv.tv.ui.utils.focusOnLaunchedSaveable
import top.yogiczy.mytv.tv.ui.utils.ifElse

@Composable
fun SettingsControlKeyActionsScreen(
    modifier: Modifier = Modifier,
    settingsViewModel: SettingsViewModel = settingsVM,
    onBackPressed: () -> Unit = {},
) {
    var selectedKey by remember { mutableStateOf<ControlKey?>(null) }
    val overrides = settingsViewModel.controlKeyActions
    fun actionFor(key: ControlKey) = ControlKeyActions.actionFor(key, overrides, settingsViewModel.iptvChannelChangeFlip)

    SettingsCategoryScreen(
        modifier = modifier,
        header = { Text("设置 / 控制 / 按键操作映射") },
        onBackPressed = onBackPressed,
    ) { firstItemFocusRequester ->
        item {
            SettingsListItem(
                modifier = Modifier.focusRequester(firstItemFocusRequester),
                headlineContent = "恢复默认映射",
                supportingContent = "仅作用于直播界面。菜单键、双击屏幕打开快捷操作；返回键保持不变。",
                onSelect = {
                    settingsViewModel.controlKeyActions = emptyMap()
                    Snackbar.show("已恢复默认按键映射")
                },
            )
        }
        val sections = listOf(
            "遥控器 / 键盘" to ControlKey.entries.take(11),
            "屏幕点击" to ControlKey.entries.drop(11).take(11),
            "屏幕长按" to ControlKey.entries.drop(22),
        )
        sections.forEach { (title, keys) ->
            item { Text(title, modifier = Modifier.padding(top = 12.dp)) }
            items(keys, key = { it.name }) { key ->
                SettingsListItem(
                    headlineContent = key.label,
                    supportingContent = if (overrides.containsKey(key.name)) "自定义" else "默认",
                    trailingContent = actionFor(key).label,
                    link = true,
                    onSelect = { selectedKey = key },
                )
            }
        }
    }

    SimplePopup(
        visibleProvider = { selectedKey != null },
        onDismissRequest = { selectedKey = null },
    ) {
        val key = selectedKey ?: return@SimplePopup
        ControlActionPicker(
            key = key,
            currentAction = actionFor(key),
            useDefault = !overrides.containsKey(key.name),
            onSelected = { action ->
                settingsViewModel.controlKeyActions = if (action == null) overrides - key.name
                else overrides + (key.name to action.name)
                selectedKey = null
            },
        )
    }
}

@Composable
private fun ControlActionPicker(
    key: ControlKey,
    currentAction: ControlAction,
    useDefault: Boolean,
    onSelected: (ControlAction?) -> Unit,
) {
    val options = listOf<ControlAction?>(null) + ControlAction.entries.filter { it.supports(key) }
    val selectedIndex = if (useDefault) 0 else options.indexOf(currentAction)
    val gridState = rememberLazyGridState(initialFirstVisibleItemIndex = selectedIndex.coerceAtLeast(0))

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        contentAlignment = Alignment.Center,
    ) {
        val columns = when {
            maxWidth >= 720.dp -> 4
            maxWidth >= 540.dp -> 3
            else -> 2
        }
        val rows = (options.size + columns - 1) / columns
        val gridHeight = (40.dp * rows + 6.dp * (rows - 1) + 8.dp)
            .coerceAtMost((maxHeight - 112.dp).coerceAtLeast(48.dp))

        Column(
            modifier = Modifier
                .widthIn(max = 840.dp)
                .fillMaxWidth()
                .heightIn(max = maxHeight)
                .background(MaterialTheme.colors.surfaceContainer, MaterialTheme.shapes.large)
                .clickableNoIndication { }
                .padding(16.dp),
        ) {
            Text(key.label, style = MaterialTheme.typography.titleLarge)
            Text(
                text = "当前：${currentAction.label} · 共 ${options.size} 个选项",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                modifier = Modifier.padding(top = 2.dp, bottom = 12.dp),
            )
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                state = gridState,
                modifier = Modifier.height(gridHeight),
                contentPadding = PaddingValues(4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                itemsIndexed(options, key = { _, action -> action?.name ?: "default" }) { index, action ->
                    ControlActionOption(
                        modifier = Modifier.ifElse(index == selectedIndex, Modifier.focusOnLaunchedSaveable()),
                        label = action?.label ?: "使用默认操作",
                        selected = index == selectedIndex,
                        onSelected = { onSelected(action) },
                    )
                }
            }
            Text(
                text = if (gridState.canScrollBackward || gridState.canScrollForward) {
                    "上下移动或滑动查看更多 · 确认选择 · 返回关闭"
                } else "方向键移动 · 确认选择 · 返回关闭",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun ControlActionOption(
    label: String,
    selected: Boolean,
    onSelected: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onSelected,
        modifier = modifier.semantics { this.selected = selected },
        colors = CardDefaults.colors(
            containerColor = if (selected) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
            else MaterialTheme.colors.surfaceContainerHigh,
            focusedContainerColor = MaterialTheme.colorScheme.onSurface,
            focusedContentColor = MaterialTheme.colorScheme.surface,
        ),
        scale = CardDefaults.scale(focusedScale = 1f),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (selected) Icon(Icons.Default.Check, contentDescription = "当前映射", modifier = Modifier.size(18.dp))
        }
    }
}
