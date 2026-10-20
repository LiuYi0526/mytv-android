package top.yogiczy.mytv.tv.ui.utils

import android.view.KeyEvent
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import top.yogiczy.mytv.core.data.entities.control.ControlKey
import top.yogiczy.mytv.core.data.entities.control.ControlKeyActions
import top.yogiczy.mytv.core.data.entities.control.ControlKeyPressTracker
import kotlin.math.abs

fun Modifier.handleLiveControls(
    onControl: (ControlKey, Int?) -> Unit,
    onQuickOp: () -> Unit,
): Modifier = composed {
    val currentControl by rememberUpdatedState(onControl)
    val currentQuickOp by rememberUpdatedState(onQuickOp)
    val tracker = remember { ControlKeyPressTracker() }

    onFocusChanged { if (!it.hasFocus) tracker.clear() }
        .onPreviewKeyEvent { event ->
            val native = event.nativeKeyEvent
            val code = native.keyCode
            val digit = when (code) {
                in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> code - KeyEvent.KEYCODE_0
                in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> code - KeyEvent.KEYCODE_NUMPAD_0
                else -> null
            }
            val key = when (code) {
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_SYSTEM_NAVIGATION_UP -> ControlKey.ON_UP
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_SYSTEM_NAVIGATION_DOWN -> ControlKey.ON_DOWN
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_SYSTEM_NAVIGATION_LEFT -> ControlKey.ON_LEFT
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_SYSTEM_NAVIGATION_RIGHT -> ControlKey.ON_RIGHT
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> ControlKey.ON_SELECT
                KeyEvent.KEYCODE_W -> ControlKey.ON_LONG_UP
                KeyEvent.KEYCODE_S -> ControlKey.ON_LONG_DOWN
                KeyEvent.KEYCODE_A -> ControlKey.ON_LONG_LEFT
                KeyEvent.KEYCODE_D -> ControlKey.ON_LONG_RIGHT
                KeyEvent.KEYCODE_L -> ControlKey.ON_LONG_SELECT
                else -> if (digit != null) ControlKey.ON_NUMBER else null
            }
            val quickOp = code in setOf(KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS, KeyEvent.KEYCODE_HELP, KeyEvent.KEYCODE_H)
            if (key == null && !quickOp) return@onPreviewKeyEvent false
            when (native.action) {
                KeyEvent.ACTION_DOWN -> {
                    if (tracker.down(code, native.repeatCount) && key != null && key != ControlKey.ON_NUMBER && !key.name.startsWith("ON_LONG_")) {
                        currentControl(ControlKey.valueOf(key.name.replace("ON_", "ON_LONG_")), null)
                    }
                }
                KeyEvent.ACTION_UP -> {
                    if (tracker.up(code, native.isCanceled)) {
                        if (quickOp) currentQuickOp() else currentControl(key!!, digit)
                    }
                }
                else -> return@onPreviewKeyEvent false
            }
            true
        }
        .pointerInput(Unit) {
            detectTapGestures(
                onTap = { currentControl(ControlKeyActions.touchKey(it.x / size.width, it.y / size.height, false), null) },
                onLongPress = { currentControl(ControlKeyActions.touchKey(it.x / size.width, it.y / size.height, true), null) },
                onDoubleTap = { currentQuickOp() },
            )
        }
        .pointerInput(Unit) {
            var dx = 0f
            var dy = 0f
            detectDragGestures(
                onDragStart = { dx = 0f; dy = 0f },
                onDragEnd = {
                    if (maxOf(abs(dx), abs(dy)) >= 24.dp.toPx()) {
                        val key = if (abs(dy) >= abs(dx)) {
                            if (dy > 0) ControlKey.ON_UP else ControlKey.ON_DOWN
                        } else {
                            if (dx > 0) ControlKey.ON_LEFT else ControlKey.ON_RIGHT
                        }
                        currentControl(key, null)
                    }
                },
                onDragCancel = { dx = 0f; dy = 0f },
            ) { change, amount ->
                change.consume()
                dx += amount.x
                dy += amount.y
            }
        }
}
