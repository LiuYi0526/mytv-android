package top.yogiczy.mytv.tv.ui.utils

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

internal class ScreenFocusState(private val saved: MutableState<String>, initialTarget: String) {
    var target by mutableStateOf(initialTarget)
        private set
    val currentKey get() = saved.value
    var restoring by mutableStateOf(initialTarget.isNotEmpty())
        private set
    var leaving by mutableStateOf(false)
        private set

    fun selected(key: String) {
        saved.value = key
        target = key
        leaving = true
        restoring = true
    }

    fun leave() {
        if (!leaving) target = saved.value
        leaving = true
        restoring = target.isNotEmpty()
    }

    fun enter() {
        leaving = false
    }

    fun focused(key: String) {
        if (!leaving && (!restoring || key == target)) {
            saved.value = key
            restoring = false
        }
    }
}

internal val LocalScreenFocus = staticCompositionLocalOf<ScreenFocusState?> { null }

@Composable
internal fun FocusReturnScope(
    defaultKey: String = "",
    keys: List<String>? = null,
    content: @Composable (ScreenFocusState) -> Unit,
) {
    val state = rememberScreenFocus(defaultKey, keys)
    CompositionLocalProvider(LocalScreenFocus provides state) { content(state) }
}

@Composable
internal fun rememberScreenFocus(defaultKey: String = "", keys: List<String>? = null): ScreenFocusState {
    val saved = rememberSaveable { mutableStateOf(defaultKey) }
    val state = remember {
        val target = saved.value.takeIf { keys == null || it in keys }
            ?: keys?.firstOrNull { it.substringBefore('/') == saved.value.substringBefore('/') }
            ?: defaultKey
        ScreenFocusState(saved, target)
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, state) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> state.leave()
                Lifecycle.Event.ON_START, Lifecycle.Event.ON_RESUME -> state.enter()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    return state
}

@Composable
internal fun Modifier.screenFocus(key: String): Modifier {
    val state = LocalScreenFocus.current ?: return this
    val requester = remember { FocusRequester() }
    var placed by remember { mutableStateOf(false) }
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(placed, windowFocused, state.restoring, state.leaving, state.target, key) {
        if (placed && windowFocused && state.restoring && !state.leaving && state.target == key) {
            // A retained page can already have focus, so no new focus event is guaranteed.
            if (requester.requestFocus()) state.focused(key)
        }
    }
    return this
        .focusRequester(requester)
        // During entry, Navigation may request default focus. Only the saved card may receive it.
        .focusProperties { canFocus = !state.restoring || state.target == key }
        .onFocusChanged { if (it.isFocused) state.focused(key) }
        .onGloballyPositioned { placed = true }
}
