package top.yogiczy.mytv.tv.ui.screensold.classicchannel.components

import android.view.KeyEvent
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onPreviewKeyEvent

/** Panel transitions move focus immediately, so handle them before DPAD focus search. */
internal fun Modifier.classicPanelDirections(
    onLeft: (() -> Unit)? = null,
    onRight: (() -> Unit)? = null,
): Modifier = onPreviewKeyEvent { event ->
    val action = when (event.nativeKeyEvent.keyCode) {
        KeyEvent.KEYCODE_DPAD_LEFT -> onLeft
        KeyEvent.KEYCODE_DPAD_RIGHT -> onRight
        else -> null
    }
    if (action == null) {
        false
    } else {
        if (event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN && event.nativeKeyEvent.repeatCount == 0) {
            action()
        }
        // Consume the matching up/repeat events even if the focused panel has changed.
        true
    }
}
