package top.yogiczy.mytv.tv.ui.screen.dashboard

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import top.yogiczy.mytv.core.data.entities.channel.Channel
import top.yogiczy.mytv.core.data.entities.channel.ChannelFavorite
import top.yogiczy.mytv.tv.ui.utils.LocalScreenFocus
import top.yogiczy.mytv.tv.ui.utils.ScreenFocusState
import top.yogiczy.mytv.tv.ui.utils.rememberScreenFocus
import top.yogiczy.mytv.tv.ui.utils.screenFocus

internal val LocalDashboardFocus = LocalScreenFocus
internal val LocalDashboardVerticalFocus = staticCompositionLocalOf<DashboardVerticalFocus?> { null }

internal class DashboardVerticalFocus(
    private val column: LazyListState,
    private val scope: CoroutineScope,
    private val screenFocus: ScreenFocusState,
    private val lastCardByRow: MutableMap<String, String>,
) {
    class Card {
        val requester = FocusRequester()
        var coordinates: LayoutCoordinates? = null
    }

    class Row(val state: LazyListState, val keys: List<String>)

    val cards = mutableMapOf<String, Card>()
    val rows = mutableMapOf<String, Row>()
    private var focusedKey: String? = null
    private var pendingMove: Job? = null

    fun focused(key: String) {
        focusedKey = key
        if (!screenFocus.leaving && (!screenFocus.restoring || screenFocus.target == key)) {
            lastCardByRow[key.substringBefore('/')] = key
        }
    }

    fun onKeyEvent(event: KeyEvent, rowOrder: List<String>): Boolean {
        if (event.type != KeyEventType.KeyDown || screenFocus.restoring || screenFocus.leaving) return false
        val step = when (event.key) {
            Key.DirectionUp -> -1
            Key.DirectionDown -> 1
            else -> return false
        }
        val source = focusedKey ?: return false
        val currentIndex = rowOrder.indexOf(source.substringBefore('/'))
        if (currentIndex < 0) return false
        val targetIndex = currentIndex + step
        val targetRow = rowOrder.getOrNull(targetIndex) ?: return false
        if (pendingMove?.isActive == true) return true

        if (focusInRow(targetRow)) return true
        pendingMove = scope.launch {
            // The header is outside the lazy column. A card row can be offscreen in either axis.
            if (targetRow != "source" && column.layoutInfo.visibleItemsInfo.none { it.key == targetRow }) {
                column.scrollToItem(targetIndex - 1)
                withFrameNanos { }
            }
            if (focusedKey != source || screenFocus.leaving || screenFocus.restoring) return@launch
            val row = rows[targetRow]
            val targetIndexInRow = row?.let { targetIndex(targetRow, it) } ?: -1
            if (row != null && targetIndexInRow >= 0 &&
                row.state.layoutInfo.visibleItemsInfo.none { it.index == targetIndexInRow }
            ) {
                row.state.scrollToItem(targetIndexInRow)
            }
            withFrameNanos { }
            if (focusedKey == source && !screenFocus.leaving && !screenFocus.restoring) {
                focusInRow(targetRow)
            }
        }
        return true
    }

    private fun targetIndex(section: String, row: Row): Int {
        val lastCard = lastCardByRow[section] ?: return 0
        return row.keys.indexOf(lastCard).coerceAtLeast(0)
    }

    private fun focusInRow(targetRow: String): Boolean {
        val key = if (targetRow == "source") {
            targetRow
        } else {
            val row = rows[targetRow] ?: return false
            val index = targetIndex(targetRow, row)
            if (row.state.layoutInfo.visibleItemsInfo.none { it.index == index }) return false
            row.keys.getOrNull(index) ?: return false
        }
        val card = cards[key]?.takeIf { it.coordinates?.isAttached == true } ?: return false
        return card.requester.requestFocus()
    }
}

@Composable
internal fun rememberDashboardFocus(keys: List<String>): ScreenFocusState =
    rememberScreenFocus("module/live", keys)

@Composable
internal fun Modifier.dashboardFocus(key: String): Modifier {
    val modifier = screenFocus(key)
    val navigation = LocalDashboardVerticalFocus.current ?: return modifier
    val card = remember(key) { DashboardVerticalFocus.Card() }
    DisposableEffect(navigation, key, card) {
        navigation.cards[key] = card
        onDispose { navigation.cards.remove(key, card) }
    }
    return modifier
        .focusRequester(card.requester)
        .onFocusChanged { if (it.isFocused) navigation.focused(key) }
        .onGloballyPositioned { card.coordinates = it }
}

internal fun dashboardChannelKey(section: String, channel: Channel) =
    "$section/${channel.name}/${channel.lineList.firstOrNull()?.url.orEmpty()}"

internal fun dashboardFavoriteKey(favorite: ChannelFavorite) = buildString {
    append("favorite/")
    append(favorite.iptvSourceName.length).append(':').append(favorite.iptvSourceName)
    append(favorite.groupName.length).append(':').append(favorite.groupName)
    append(favorite.channel.name)
}

@Composable
internal fun rememberDashboardRowState(keys: List<String>): LazyListState {
    val focus = LocalDashboardFocus.current ?: return rememberLazyListState()
    // Keep the selected card's visible slot even when playback reorders the recent-history list.
    val state = rememberSaveable(saver = listSaver(
        save = { state: LazyListState -> listOf(state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset,
            keys.indexOf(focus.currentKey).takeIf { it >= 0 }?.minus(state.firstVisibleItemIndex) ?: -1) },
        restore = { saved ->
            val targetIndex = keys.indexOf(focus.target)
            val index = if (targetIndex >= 0 && saved[2] >= 0) targetIndex - saved[2] else saved[0]
            LazyListState(index.coerceIn(0, (keys.size - 1).coerceAtLeast(0)), saved[1])
        },
    )) { LazyListState() }
    val navigation = LocalDashboardVerticalFocus.current
    if (navigation != null && keys.isNotEmpty()) {
        val section = keys.first().substringBefore('/')
        DisposableEffect(navigation, section, state, keys) {
            val row = DashboardVerticalFocus.Row(state, keys)
            navigation.rows[section] = row
            onDispose { navigation.rows.remove(section, row) }
        }
    }
    LaunchedEffect(state, keys, focus.target, focus.restoring, focus.leaving) {
        if (!focus.restoring || focus.leaving) return@LaunchedEffect
        val targetIndex = keys.indexOf(focus.target)
        if (targetIndex < 0) return@LaunchedEffect
        val layout = snapshotFlow { state.layoutInfo }.first {
            it.totalItemsCount == keys.size && it.visibleItemsInfo.isNotEmpty()
        }
        // Watching a channel can move it to the front of history while this page is
        // leaving. The saved offset can then hide the only card allowed to take focus.
        // Compose the target before screenFocus attempts to restore it.
        if (layout.visibleItemsInfo.none { it.index == targetIndex }) {
            state.scrollToItem(targetIndex)
        }
    }
    return state
}
