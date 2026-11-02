package top.yogiczy.mytv.tv.ui.screen.dashboard

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import top.yogiczy.mytv.core.data.entities.channel.Channel
import top.yogiczy.mytv.tv.ui.utils.LocalScreenFocus
import top.yogiczy.mytv.tv.ui.utils.ScreenFocusState
import top.yogiczy.mytv.tv.ui.utils.rememberScreenFocus
import top.yogiczy.mytv.tv.ui.utils.screenFocus

internal val LocalDashboardFocus = LocalScreenFocus

@Composable
internal fun rememberDashboardFocus(keys: List<String>): ScreenFocusState =
    rememberScreenFocus("module/live", keys)

@Composable
internal fun Modifier.dashboardFocus(key: String): Modifier = screenFocus(key)

internal fun dashboardChannelKey(section: String, channel: Channel) =
    "$section/${channel.name}/${channel.lineList.firstOrNull()?.url.orEmpty()}"

@Composable
internal fun rememberDashboardRowState(keys: List<String>): LazyListState {
    val focus = LocalDashboardFocus.current ?: return rememberLazyListState()
    // Keep the selected card's visible slot even when playback reorders the recent-history list.
    return rememberSaveable(saver = listSaver(
        save = { state: LazyListState -> listOf(state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset,
            keys.indexOf(focus.currentKey).takeIf { it >= 0 }?.minus(state.firstVisibleItemIndex) ?: -1) },
        restore = { saved ->
            val targetIndex = keys.indexOf(focus.target)
            val index = if (targetIndex >= 0 && saved[2] >= 0) targetIndex - saved[2] else saved[0]
            LazyListState(index.coerceIn(0, (keys.size - 1).coerceAtLeast(0)), saved[1])
        },
    )) { LazyListState() }
}
