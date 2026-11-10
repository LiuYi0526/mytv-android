package top.yogiczy.mytv.tv.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import top.yogiczy.mytv.tv.ui.utils.clickableNoIndication

class InputCallbackUpdateTest {
    @get:Rule val compose = createComposeRule()

    @Test fun repeatedTouchesUseTheLatestActionAfterRecomposition() {
        val version = mutableStateOf(1)
        val actions = mutableListOf<Int>()
        compose.setContent {
            val current = version.value
            Box(Modifier.size(100.dp).testTag("target").clickableNoIndication(onClick = { actions += current }))
        }
        compose.onNodeWithTag("target").performTouchInput { click() }
        compose.runOnIdle { version.value = 2 }
        compose.onNodeWithTag("target").performTouchInput { click() }
        compose.runOnIdle { assertEquals(listOf(1, 2), actions) }
    }

    @Test fun longAndDoubleTouchesAlsoUseUpdatedCallbacks() {
        val version = mutableStateOf(1)
        val actions = mutableListOf<String>()
        compose.setContent {
            val current = version.value
            Box(Modifier.size(100.dp).testTag("target").clickableNoIndication(
                onLongClick = { actions += "long$current" },
                onDoubleClick = { actions += "double$current" },
            ))
        }
        compose.onNodeWithTag("target").performTouchInput { longClick() }
        compose.runOnIdle { version.value = 2 }
        compose.onNodeWithTag("target").performTouchInput { longClick() }
        compose.onNodeWithTag("target").performTouchInput { doubleClick() }
        compose.runOnIdle { assertEquals(listOf("long1", "long2", "double2"), actions) }
    }

    @Test fun addingAndRemovingHandlersUpdatesGestureRecognition() {
        val enabled = mutableStateOf(false)
        var count = 0
        compose.setContent {
            Box(Modifier.size(100.dp).testTag("target").clickableNoIndication(
                onClick = if (enabled.value) ({ count++ }) else null,
            ))
        }
        compose.onNodeWithTag("target").performTouchInput { click() }
        compose.runOnIdle { enabled.value = true }
        compose.onNodeWithTag("target").performTouchInput { click() }
        compose.runOnIdle { enabled.value = false }
        compose.onNodeWithTag("target").performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, count) }
    }
}
