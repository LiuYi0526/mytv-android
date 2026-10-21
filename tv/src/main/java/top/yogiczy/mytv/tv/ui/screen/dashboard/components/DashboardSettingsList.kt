package top.yogiczy.mytv.tv.ui.screen.dashboard.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import top.yogiczy.mytv.tv.ui.material.LazyRow
import top.yogiczy.mytv.tv.ui.material.items
import top.yogiczy.mytv.tv.ui.rememberChildPadding
import top.yogiczy.mytv.tv.ui.screen.settings.SettingsCategories
import top.yogiczy.mytv.tv.ui.screen.settings.SettingsCategoryItem

@Composable
fun DashboardSettingsList(onCategorySelected: (SettingsCategories) -> Unit) {
    val padding = rememberChildPadding()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        DashboardSectionHeader("设置")
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            contentPadding = PaddingValues(start = padding.start, end = padding.end),
        ) { runtime ->
            items(SettingsCategories.entries, runtime) { itemModifier, category ->
                SettingsCategoryItem(
                    modifier = itemModifier, title = category.title, imageVector = category.icon,
                    onSelected = { onCategorySelected(category) },
                )
            }
        }
    }
}
