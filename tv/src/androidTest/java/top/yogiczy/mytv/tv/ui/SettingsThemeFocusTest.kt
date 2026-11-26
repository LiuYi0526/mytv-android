package top.yogiczy.mytv.tv.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import top.yogiczy.mytv.tv.ui.screen.settings.categories.SettingsThemeScreen
import top.yogiczy.mytv.tv.ui.theme.MyTvTheme

@OptIn(ExperimentalTestApi::class)
class SettingsThemeFocusTest {
    @get:Rule val compose = createComposeRule()

    @Test fun movingFocusAcrossTheThemeRowKeepsBothCardEdgesVisible() {
        val names = listOf("地海蔚蓝", "樱语红粉", "触手生春", "夏绿成荫", "龙行绚烂")
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                MyTvTheme {
                    Box(Modifier.width(440.dp)) { SettingsThemeScreen() }
                }
            }
        }
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasText(names.first())).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(names.first()).performSemanticsAction(SemanticsActions.RequestFocus)
        assertFocusHasRoom(names.first())

        names.drop(1).forEach { name ->
            compose.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
            assertFocusHasRoom(name)
        }
        names.asReversed().drop(1).forEach { name ->
            compose.onRoot().performKeyInput { pressKey(Key.DirectionLeft) }
            assertFocusHasRoom(name)
        }
    }

    private fun assertFocusHasRoom(name: String) {
        val card = compose.onNodeWithText(name).assertIsFocused().fetchSemanticsNode().boundsInRoot
        val row = compose.onNode(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange) and
                hasAnyDescendant(hasText(name)),
            useUnmergedTree = true,
        ).fetchSemanticsNode().boundsInRoot

        // The focus border and scale need some space inside the scrolling viewport.
        val gutter = 6f
        assertTrue(
            "Focused theme $name touches the row edge: card=$card row=$row",
            card.left >= row.left + gutter && card.right <= row.right - gutter,
        )
    }
}
