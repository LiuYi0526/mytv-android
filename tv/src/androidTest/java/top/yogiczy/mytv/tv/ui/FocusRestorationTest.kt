package top.yogiczy.mytv.tv.ui

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import top.yogiczy.mytv.tv.ui.utils.Configs
import top.yogiczy.mytv.tv.ui.utils.saveFocusRestorer

/** Regression for the changed timing of Compose's deprecated focusRestorer lambda. */
class FocusRestorationTest {
    @get:Rule val compose = createComposeRule()
    private var originalFocusOptimize = true
    private val outside = FocusRequester()
    private val group = FocusRequester()
    private val first = FocusRequester()
    private val fallback = FocusRequester()
    private var revision by mutableIntStateOf(0)
    private var showFirst by mutableStateOf(true)

    @Before fun enableFocusRestoration() {
        originalFocusOptimize = Configs.uiFocusOptimize
        Configs.uiFocusOptimize = true
    }

    @After fun restoreFocusSetting() {
        Configs.uiFocusOptimize = originalFocusOptimize
    }

    private fun showContent() {
        compose.setContent {
            MaterialTheme {
                Column {
                    Text("Revision $revision")
                    Button(onClick = {}, modifier = Modifier.focusRequester(outside).testTag("outside")) {
                        Text("Outside")
                    }
                    Row(Modifier.focusRequester(group).saveFocusRestorer { fallback }.focusGroup()) {
                        if (showFirst) {
                            Button(onClick = {}, modifier = Modifier.focusRequester(first).testTag("first")) {
                                Text("First")
                            }
                        }
                        Button(onClick = {}, modifier = Modifier.focusRequester(fallback).testTag("fallback")) {
                            Text("Fallback")
                        }
                    }
                }
            }
        }
        compose.runOnIdle { outside.requestFocus() }
    }

    @Test fun recompositionDoesNotMoveFocusIntoTheGroup() {
        showContent()
        compose.runOnIdle { revision++ }
        compose.onNodeWithTag("outside").assertIsFocused()
        compose.runOnIdle { group.requestFocus() }
        compose.onNodeWithTag("fallback").assertIsFocused()
    }

    @Test fun reentryRestoresPreviousChildAndFallsBackAfterThatChildIsRemoved() {
        showContent()
        // Enter through the group's fallback before choosing a different child.
        compose.runOnIdle { group.requestFocus() }
        compose.onNodeWithTag("fallback").assertIsFocused()
        compose.runOnIdle { first.requestFocus() }
        compose.onNodeWithTag("first").assertIsFocused()
        compose.runOnIdle { outside.requestFocus() }
        compose.runOnIdle { group.requestFocus() }
        compose.onNodeWithTag("first").assertIsFocused()
        compose.runOnIdle { outside.requestFocus(); showFirst = false }
        compose.waitForIdle()
        compose.runOnIdle { group.requestFocus() }
        compose.onNodeWithTag("fallback").assertIsFocused()
    }
}
