package top.yogiczy.mytv.tv.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import top.yogiczy.mytv.tv.ui.material.SnackbarData
import top.yogiczy.mytv.tv.ui.material.SnackbarUIState

class SnackbarRefreshStateTest {
    private fun withState(block: suspend (SnackbarUIState) -> Unit) = runBlocking(Dispatchers.Main) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try { block(SnackbarUIState(scope)) } finally { scope.cancel() }
    }

    @Test fun refreshStaysPastNormalDurationThenCompletionDisappears() = withState { state ->
        state.show("正在刷新订阅源", SnackbarData(id = "refresh", duration = Long.MAX_VALUE, leadingLoading = true))
        delay(SnackbarData.DEFAULT_DURATION + 100)
        assertTrue(state.visible)
        assertTrue(state.currentData.leadingLoading)
        state.show("订阅源刷新完成", SnackbarData(id = "refresh", duration = 80))
        assertTrue(state.visible)
        assertEquals("订阅源刷新完成", state.currentData.text)
        assertFalse(state.currentData.leadingLoading)
        delay(150)
        assertFalse(state.visible)
    }

    @Test fun cancellationDuringEnterAnimationDoesNotLeaveALoadingMessage() = withState { state ->
        state.show("之前的提示", SnackbarData(id = "before", duration = Long.MAX_VALUE))
        state.show("正在刷新订阅源", SnackbarData(id = "refresh", duration = Long.MAX_VALUE))
        state.dismiss("refresh")
        delay(400)
        assertFalse(state.visible)
    }

    @Test fun cancellingAnOldRefreshDoesNotDismissTheNewMessage() = withState { state ->
        state.show("旧刷新", SnackbarData(id = "old", duration = Long.MAX_VALUE))
        state.show("新刷新", SnackbarData(id = "new", duration = Long.MAX_VALUE))
        state.dismiss("old")
        delay(400)
        assertTrue(state.visible)
        assertEquals("新刷新", state.currentData.text)
        state.dismiss("new")
        assertFalse(state.visible)
    }

    @Test fun completionSupersedesALoadingMessageStillWaitingToAppear() = withState { state ->
        state.show("之前的提示", SnackbarData(id = "before", duration = Long.MAX_VALUE))
        state.show("正在刷新订阅源", SnackbarData(id = "refresh", duration = Long.MAX_VALUE))
        state.show("订阅源刷新失败，继续使用缓存", SnackbarData(id = "refresh", duration = 1_000))
        delay(400)
        assertTrue(state.visible)
        assertEquals("订阅源刷新失败，继续使用缓存", state.currentData.text)
        assertFalse(state.currentData.leadingLoading)
    }

    @Test fun oldHideTimerCannotDismissALongerLivedReplacement() = withState { state ->
        state.show("短提示", SnackbarData(id = "refresh", duration = 50))
        delay(10)
        state.show("正在刷新订阅源", SnackbarData(id = "refresh", duration = Long.MAX_VALUE))
        delay(120)
        assertTrue(state.visible)
        assertEquals("正在刷新订阅源", state.currentData.text)
    }
}
