package top.yogiczy.mytv.tv.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.Density
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import top.yogiczy.mytv.core.data.entities.control.ControlAction
import top.yogiczy.mytv.core.data.entities.control.ControlKey
import top.yogiczy.mytv.tv.ui.material.LocalPopupManager
import top.yogiczy.mytv.tv.ui.material.LocalPopupState
import top.yogiczy.mytv.tv.ui.material.PopupHandleableApplication
import top.yogiczy.mytv.tv.ui.material.PopupManager
import top.yogiczy.mytv.tv.ui.screen.settings.subcategories.SettingsControlKeyActionsContent
import top.yogiczy.mytv.tv.ui.theme.MyTvTheme

@OptIn(ExperimentalTestApi::class)
class ControlKeyActionsFocusTest {
    @get:Rule val compose = createComposeRule()
    private var overrides by mutableStateOf<Map<String, String>>(emptyMap())

    private fun showScreen() {
        compose.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(1f),
                LocalPopupManager provides remember { PopupManager() },
                LocalPopupState provides remember { mutableStateListOf() },
            ) {
                MyTvTheme {
                    PopupHandleableApplication {
                        SettingsControlKeyActionsContent(
                            overrides = overrides,
                            flipChannels = false,
                            onOverridesChange = { overrides = it },
                        )
                    }
                }
            }
        }
    }

    private fun focus(text: String) {
        compose.onNodeWithText(text).performScrollTo()
            .performSemanticsAction(SemanticsActions.RequestFocus)
    }

    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    @Test fun savingLeftMappingRestoresRowAndAllowsVerticalNavigation() {
        showScreen()
        focus(ControlKey.ON_LEFT.label)
        press(Key.DirectionCenter)
        focus(ControlAction.VIDEO_PLAYER_CONTROLLER.label)
        press(Key.DirectionCenter)

        compose.runOnIdle {
            assertEquals(ControlAction.VIDEO_PLAYER_CONTROLLER.name, overrides[ControlKey.ON_LEFT.name])
        }
        compose.onNodeWithText(ControlKey.ON_LEFT.label).assertIsFocused()
        press(Key.DirectionDown)
        compose.onNodeWithText(ControlKey.ON_RIGHT.label).assertIsFocused()
        press(Key.DirectionUp)
        compose.onNodeWithText(ControlKey.ON_LEFT.label).assertIsFocused()
        press(Key.DirectionUp)
        compose.onNodeWithText(ControlKey.ON_DOWN.label).assertIsFocused()
    }

    @Test fun cancellingAndReopeningPickerKeepsNavigationAvailable() {
        showScreen()
        focus(ControlKey.ON_LEFT.label)
        repeat(2) {
            press(Key.DirectionCenter)
            press(Key.Back)
            compose.onNodeWithText(ControlKey.ON_LEFT.label).assertIsFocused()
            press(Key.DirectionDown)
            compose.onNodeWithText(ControlKey.ON_RIGHT.label).assertIsFocused()
            press(Key.DirectionUp)
        }
        compose.runOnIdle { assertEquals(emptyMap<String, String>(), overrides) }
    }
}
