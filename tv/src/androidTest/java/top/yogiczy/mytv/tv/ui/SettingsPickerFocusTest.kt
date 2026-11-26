package top.yogiczy.mytv.tv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.Density
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import top.yogiczy.mytv.core.data.entities.epgsource.EpgSource
import top.yogiczy.mytv.tv.ui.material.LocalPopupManager
import top.yogiczy.mytv.tv.ui.material.LocalPopupState
import top.yogiczy.mytv.tv.ui.material.PopupHandleableApplication
import top.yogiczy.mytv.tv.ui.material.PopupManager
import top.yogiczy.mytv.tv.ui.screen.settings.components.SettingsSeekStepItem
import top.yogiczy.mytv.tv.ui.screen.settings.subcategories.SourceEpgField
import top.yogiczy.mytv.tv.ui.theme.MyTvTheme

@OptIn(ExperimentalTestApi::class)
class SettingsPickerFocusTest {
    @get:Rule val compose = createComposeRule()

    @Test fun seekStepPickerLeavesRoomAtTheFirstAndLastRows() {
        showPicker("快退按钮步长") { SettingsSeekStepItem("快退按钮步长", 10) {} }
        assertFocusHasRoom("10秒")
        repeat(2) { press(Key.DirectionDown) }
        assertFocusHasRoom("5分钟")
        repeat(2) { press(Key.DirectionUp) }
        assertFocusHasRoom("10秒")
    }

    @Test fun programmeGuidePickerKeepsFocusInsideTheViewportAfterScrolling() {
        val sources = (1..8).map { EpgSource("节目单选项 $it", "https://example.test/$it.xml") }
        showPicker("节目单") {
            SourceEpgField(emptyList(), sources, sources.first(), compact = false) {}
        }
        assertFocusHasRoom("使用默认节目单")
        repeat(sources.size) { press(Key.DirectionDown) }
        assertFocusHasRoom(sources.last().name)
        repeat(sources.size) { press(Key.DirectionUp) }
        assertFocusHasRoom("使用默认节目单")
    }

    private fun showPicker(title: String, content: @Composable () -> Unit) {
        compose.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(1f),
                LocalPopupManager provides remember { PopupManager() },
                LocalPopupState provides remember { mutableStateListOf() },
            ) {
                MyTvTheme { PopupHandleableApplication(content) }
            }
        }
        compose.onNodeWithText(title).performSemanticsAction(SemanticsActions.RequestFocus)
        press(Key.Enter)
    }

    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
    }

    private fun assertFocusHasRoom(name: String) {
        val card = compose.onNode(hasText(name) and isFocused())
            .assertIsFocused().fetchSemanticsNode().boundsInRoot
        val viewport = compose.onNode(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange) and
                hasAnyDescendant(hasText(name)),
            useUnmergedTree = true,
        ).fetchSemanticsNode().boundsInRoot
        // Focus scales the card slightly beyond its layout bounds.
        assertTrue(
            "Focused row $name touches the picker edge: card=$card viewport=$viewport",
            card.top >= viewport.top + 4f && card.bottom <= viewport.bottom - 4f,
        )
    }
}
