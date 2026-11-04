package top.yogiczy.mytv.tv.ui.material

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.tv.material3.MaterialTheme
import top.yogiczy.mytv.tv.ui.utils.backHandler
import top.yogiczy.mytv.tv.ui.utils.clickableNoIndication
import top.yogiczy.mytv.tv.ui.utils.ifElse
import java.util.UUID

class PopupManager {
    private val stack = mutableListOf<StackItem>()

    fun push(focusRequester: FocusRequester, emitter: Boolean = false) {
        stack.add(StackItem(focusRequester, emitter))
    }

    fun pop() {
        if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
        val last = stack.lastOrNull()
        try {
            last?.focusRequester?.requestFocus()
            if (last?.emitter == true) stack.remove(last)
        } catch (ex: Exception) {
            ex.printStackTrace()
            try {
                stack.remove(last)
                stack.lastOrNull()?.focusRequester?.requestFocus()
            } catch (_: Exception) {
            }
        }
    }

    class StackItem(
        val focusRequester: FocusRequester,
        val emitter: Boolean = false,
    )
}

val LocalPopupManager = compositionLocalOf { PopupManager() }

fun Modifier.popupable() = composed {
    val popupManager = LocalPopupManager.current
    val focusRequester = remember { FocusRequester() }

    DisposableEffect(Unit) {
        focusRequester.requestFocus()
        popupManager.push(focusRequester)
        onDispose { popupManager.pop() }
    }

    focusRequester(focusRequester).focusable()
}

@Composable
fun PopupContent(
    modifier: Modifier = Modifier,
    visibleProvider: () -> Boolean,
    onDismissRequest: (() -> Unit)? = null,
    withBackground: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    if (!visibleProvider()) return
    BackHandler { onDismissRequest?.invoke() }

    Box(
        modifier
            .fillMaxSize()
            .popupable()
            .clickableNoIndication { onDismissRequest?.invoke() }
            .backHandler { onDismissRequest?.invoke() }
            .ifElse(
                withBackground,
                Modifier.background(MaterialTheme.colorScheme.background.copy(0.5f)),
            ),
    ) {
        content()
    }
}

@Composable
fun SimplePopup(
    modifier: Modifier = Modifier,
    visibleProvider: () -> Boolean,
    onDismissRequest: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val visible = visibleProvider()
    val key = remember { UUID.randomUUID().toString() }
    val popupState = LocalPopupState.current
    val currentVisible by rememberUpdatedState(visible)
    val currentModifier by rememberUpdatedState(modifier)
    val currentDismiss by rememberUpdatedState(onDismissRequest)
    val currentContent by rememberUpdatedState(content)

    val entry = remember(key) {
        PopupState(
            key = key,
            composableReference = {
                PopupContent(
                    modifier = currentModifier.focusProperties { onExit = { cancelFocusChange() } },
                    visibleProvider = { currentVisible },
                    onDismissRequest = { currentDismiss?.invoke() },
                    withBackground = true,
                    content = { currentContent() },
                )
            },
        )
    }
    // Register once per visible lifetime. Composition can run repeatedly while
    // the popup is open; appending here used to stack duplicate focus scopes.
    DisposableEffect(visible, popupState, key) {
        if (visible) popupState.add(entry)
        onDispose { popupState.removeAll { it.key == key } }
    }
}

data class PopupState(
    val key: String = UUID.randomUUID().toString(),
    val composableReference: @Composable () -> Unit = {},
)

val LocalPopupState = compositionLocalOf { mutableStateListOf<PopupState>() }

@Composable
fun PopupHandleableApplication(
    applicationContent: @Composable () -> Unit
) {
    val popupState = LocalPopupState.current

    Box(modifier = Modifier.fillMaxSize()) {
        applicationContent()

        popupState.map {
            it.composableReference()
        }
    }
}
