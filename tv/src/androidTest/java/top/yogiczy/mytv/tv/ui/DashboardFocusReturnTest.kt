package top.yogiczy.mytv.tv.ui

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import top.yogiczy.mytv.tv.ui.screen.dashboard.LocalDashboardFocus
import top.yogiczy.mytv.tv.ui.screen.dashboard.dashboardFocus
import top.yogiczy.mytv.tv.ui.screen.dashboard.rememberDashboardRowState
import top.yogiczy.mytv.tv.ui.theme.MyTvTheme
import top.yogiczy.mytv.tv.ui.utils.ScreenFocusState

@OptIn(ExperimentalTestApi::class)
class DashboardFocusReturnTest {
    @get:Rule val compose = createComposeRule()
    private val selected = "history/channel8"
    private var keys by mutableStateOf(List(20) { "history/channel$it" })
    private lateinit var focus: ScreenFocusState
    private lateinit var row: LazyListState
    private lateinit var scope: CoroutineScope

    private fun showScrolledHistory() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                MyTvTheme {
                    val saved = remember { mutableStateOf(selected) }
                    focus = remember { ScreenFocusState(saved, "") }
                    scope = rememberCoroutineScope()
                    CompositionLocalProvider(LocalDashboardFocus provides focus) {
                        row = rememberDashboardRowState(keys)
                        LazyRow(Modifier.width(400.dp).height(80.dp), state = row) {
                            // Match the production history row's index-based lazy items.
                            items(keys) { key ->
                                Box(Modifier.width(100.dp).height(80.dp)
                                    .dashboardFocus(key).focusable().testTag(key)) { Text(key) }
                            }
                        }
                    }
                }
            }
        }
        compose.runOnIdle { scope.launch { row.scrollToItem(6, 15) } }
        compose.waitForIdle()
        compose.onNodeWithTag(selected).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNodeWithTag(selected).assertIsFocused()
    }

    @Test fun returningAfterHistoryReordersBringsTheSavedCardBackAndUnlocksNavigation() {
        showScrolledHistory()
        compose.runOnIdle {
            focus.selected(selected)
            keys = listOf(selected) + keys.filterNot { it == selected }
        }
        compose.waitForIdle()
        compose.onNodeWithTag(selected).assertDoesNotExist()
        compose.runOnIdle { focus.enter() }
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasTestTag(selected) and isFocused()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(selected).assertIsDisplayed()
        compose.runOnIdle { assertFalse(focus.restoring) }
        compose.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        compose.onNodeWithTag("history/channel0").assertIsFocused()
    }

    @Test fun returningToAnAlreadyVisibleCardKeepsTheExistingScrollPosition() {
        showScrolledHistory()
        var index = 0
        var offset = 0
        compose.runOnIdle {
            index = row.firstVisibleItemIndex
            offset = row.firstVisibleItemScrollOffset
            focus.selected(selected)
        }
        compose.waitForIdle()
        compose.runOnIdle { focus.enter() }
        compose.waitForIdle()
        compose.onNodeWithTag(selected).assertIsFocused()
        compose.runOnIdle {
            assertFalse(focus.restoring)
            assertEquals(index, row.firstVisibleItemIndex)
            assertEquals(offset, row.firstVisibleItemScrollOffset)
        }
    }
}
