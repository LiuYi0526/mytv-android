package top.yogiczy.mytv.tv.ui.screensold.components

import android.view.KeyEvent
import androidx.compose.foundation.focusable
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import top.yogiczy.mytv.tv.ui.utils.saveRequestFocus

@Composable
internal fun Modifier.awaitPanelDirection(
    enabled: Boolean,
    requestItemFocus: () -> Boolean,
): Modifier {
    if (!enabled) return this

    val anchor = remember { FocusRequester() }
    var anchorFocused by remember { mutableStateOf(false) }
    var waiting by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        // Wait for the popup's own focus target to attach before taking focus.
        withFrameNanos { }
        anchor.saveRequestFocus()
    }

    return onPreviewKeyEvent { event ->
        val key = event.nativeKeyEvent
        if (!anchorFocused || key.action != KeyEvent.ACTION_DOWN) {
            false
        } else {
            when (key.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (requestItemFocus()) waiting = false
                    true
                }
                else -> false
            }
        }
    }
        .focusRequester(anchor)
        .onFocusChanged { anchorFocused = it.isFocused }
        .focusable(enabled = waiting)
}

internal fun LazyListState.focusVisibleItem(
    preferredIndex: Int,
    requesters: List<FocusRequester>,
): Boolean {
    val items = layoutInfo.visibleItemsInfo
    // The user may have scrolled by touch while waiting to use the remote.
    val item = items.firstOrNull { it.index == preferredIndex } ?: items.getOrNull(items.size / 2)
    return item?.let { requesters.getOrNull(it.index)?.saveRequestFocus()?.getOrDefault(false) } == true
}
