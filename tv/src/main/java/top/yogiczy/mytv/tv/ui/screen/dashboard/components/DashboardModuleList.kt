package top.yogiczy.mytv.tv.ui.screen.dashboard.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material.icons.outlined.ViewCozy
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import top.yogiczy.mytv.tv.ui.screen.dashboard.rememberDashboardRowState
import top.yogiczy.mytv.tv.ui.screen.dashboard.LocalDashboardFocus
import top.yogiczy.mytv.tv.ui.screen.dashboard.dashboardFocus
import top.yogiczy.mytv.tv.ui.material.LazyRow
import top.yogiczy.mytv.tv.ui.rememberChildPadding
import top.yogiczy.mytv.tv.ui.screen.components.AppScreen
import top.yogiczy.mytv.tv.ui.theme.MyTvTheme
import top.yogiczy.mytv.tv.ui.utils.handleKeyEvents

@Composable
fun DashboardModuleList(
    modifier: Modifier = Modifier,
    toLiveScreen: () -> Unit = {},
    toChannelsScreen: () -> Unit = {},
    toFavoritesScreen: () -> Unit = {},
    toSearchScreen: () -> Unit = {},
    toMultiViewScreen: () -> Unit = {},
    toPushScreen: () -> Unit = {},
    toAboutScreen: () -> Unit = {},
) {
    val childPadding = rememberChildPadding()
    val focus = LocalDashboardFocus.current

    LazyRow(
            state = rememberDashboardRowState(listOf("live", "channels", "favorites", "search", "multiview", "push", "about").map { "module/$it" }),
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        contentPadding = PaddingValues(start = childPadding.start, end = childPadding.end),
        preservePaddingOnFocus = true,
        restoreFocus = focus?.restoring != true,
    ) { runtime ->
        item {
            DashboardModuleItem(
                modifier = Modifier
                    .dashboardFocus("module/live").focusRequester(runtime.firstItemFocusRequester)
                    .handleKeyEvents(onLeft = { runtime.scrollToLast() }),
                imageVector = Icons.Outlined.Tv,
                title = "直播",
                onSelected = { focus?.selected("module/live"); toLiveScreen() },
            )
        }

        item {
            DashboardModuleItem(
                modifier = Modifier.dashboardFocus("module/channels"),
                imageVector = Icons.Outlined.GridView,
                title = "全部频道",
                onSelected = { focus?.selected("module/channels"); toChannelsScreen() },
            )
        }

        item {
            DashboardModuleItem(
                modifier = Modifier.dashboardFocus("module/favorites"),
                imageVector = Icons.Outlined.StarBorder,
                title = "我的收藏",
                onSelected = { focus?.selected("module/favorites"); toFavoritesScreen() },
            )
        }

        item {
            DashboardModuleItem(
                modifier = Modifier.dashboardFocus("module/search"),
                imageVector = Icons.Outlined.Search,
                title = "搜索",
                onSelected = { focus?.selected("module/search"); toSearchScreen() },
            )
        }

        item {
            DashboardModuleItem(
                modifier = Modifier.dashboardFocus("module/multiview"),
                imageVector = Icons.Outlined.ViewCozy,
                title = "多屏同播",
                onSelected = { focus?.selected("module/multiview"); toMultiViewScreen() },
            )
        }

        item {
            DashboardModuleItem(
                modifier = Modifier.dashboardFocus("module/push"),
                imageVector = Icons.AutoMirrored.Outlined.Send,
                title = "推送",
                onSelected = { focus?.selected("module/push"); toPushScreen() },
            )
        }

        item {
            DashboardModuleItem(
                modifier = Modifier
                    .dashboardFocus("module/about").focusRequester(runtime.lastItemFocusRequester)
                    .handleKeyEvents(onRight = { runtime.scrollToFirst() }),
                imageVector = Icons.Outlined.Info,
                title = "关于",
                onSelected = { focus?.selected("module/about"); toAboutScreen() },
            )
        }
    }
}

@Preview(device = "id:Android TV (720p)")
@Composable
private fun DashboardModuleListPreview() {
    MyTvTheme {
        AppScreen {
            DashboardModuleList(
                modifier = Modifier.padding(vertical = 20.dp),
            )
        }
        // PreviewWithLayoutGrids { }
    }
}
