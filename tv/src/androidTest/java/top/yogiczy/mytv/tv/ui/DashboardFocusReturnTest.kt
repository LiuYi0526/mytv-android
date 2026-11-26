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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import top.yogiczy.mytv.core.data.entities.channel.Channel
import top.yogiczy.mytv.core.data.entities.channel.ChannelFavorite
import top.yogiczy.mytv.core.data.entities.channel.ChannelFavoriteList
import top.yogiczy.mytv.tv.ui.screen.dashboard.DashboardScreen
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
    private var dashboardRecent by mutableStateOf<List<Channel>>(emptyList())
    private lateinit var focus: ScreenFocusState
    private lateinit var row: LazyListState
    private lateinit var scope: CoroutineScope

    private fun showDashboard(favorites: List<String>, recent: List<String> = emptyList()) {
        val favoriteList = ChannelFavoriteList(favorites.map { ChannelFavorite(Channel(it), "测试源", "测试") })
        dashboardRecent = recent.map { Channel(it) }
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                MyTvTheme {
                    Box(Modifier.width(720.dp).height(300.dp)) {
                        DashboardScreen(
                            channelFavoriteListProvider = { favoriteList },
                            recentChannelsProvider = { dashboardRecent },
                        )
                    }
                }
            }
        }
        assertFocused("直播")
    }

    private fun press(key: Key) = compose.onRoot().performKeyInput { pressKey(key) }

    private fun assertFocused(title: String): SemanticsNodeInteraction {
        val card = isFocused() and (hasText(title) or hasAnyDescendant(hasText(title)))
        compose.waitUntil(5_000) {
            compose.onAllNodes(card, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        return compose.onNode(card, useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun verticalRoundTripRestoresTheExactCardAfterScrollingThroughRows() {
        showDashboard(favorites = listOf("收藏甲"), recent = listOf("最近观看甲"))
        press(Key.DirectionRight)
        assertFocused("全部频道")
        press(Key.DirectionDown)
        assertFocused("收藏甲")
        press(Key.DirectionDown)
        assertFocused("最近观看甲")
        press(Key.DirectionUp)
        assertFocused("收藏甲")
        // Pressing against the edge does not count as a horizontal focus change.
        press(Key.DirectionLeft)
        assertFocused("收藏甲")
        press(Key.DirectionUp)
        assertFocused("全部频道")
    }

    @Test fun unvisitedRowsStartAtTheFirstCardAndRememberTheirOwnFocus() {
        showDashboard(favorites = listOf("收藏甲", "收藏乙", "收藏丙"))
        press(Key.DirectionRight)
        assertFocused("全部频道")
        press(Key.DirectionDown)
        assertFocused("收藏甲")
        press(Key.DirectionRight)
        assertFocused("收藏乙")
        press(Key.DirectionUp)
        assertFocused("全部频道")
        press(Key.DirectionDown)
        assertFocused("收藏乙")
    }

    @Test fun favoritesKeepTheFocusedCardInTheSecondSlotWhileScrolling() {
        val names = List(9) { "收藏${it + 1}" }
        showDashboard(favorites = names)
        assertSecondSlotWhileScrolling(names)
    }

    @Test fun recentChannelsKeepTheFocusedCardInTheSecondSlotWhileScrolling() {
        val names = List(9) { "最近频道${it + 1}" }
        showDashboard(favorites = emptyList(), recent = names)
        assertSecondSlotWhileScrolling(names)
    }

    @Test fun movingBetweenScrolledRowsRestoresEachCardAndItsHorizontalPosition() {
        val favorites = List(9) { "收藏${it + 1}" }
        val recent = List(9) { "最近频道${it + 1}" }
        showDashboard(favorites, recent)
        press(Key.DirectionDown)
        assertFocused(favorites.first())
        press(Key.DirectionDown)
        assertFocused(recent.first())
        for (index in 1..3) {
            press(Key.DirectionRight)
            assertFocused(recent[index])
        }
        val recentTarget = recent[3]
        val recentX = assertFocused(recentTarget).fetchSemanticsNode().boundsInRoot.left
        press(Key.DirectionUp)
        assertFocused(favorites.first())
        for (index in 1..favorites.lastIndex) {
            press(Key.DirectionRight)
            assertFocused(favorites[index])
        }
        val favoriteX = assertFocused(favorites.last()).fetchSemanticsNode().boundsInRoot.left

        press(Key.DirectionDown)
        assertEquals(recentX, assertFocused(recentTarget).fetchSemanticsNode().boundsInRoot.left, 2f)
        press(Key.DirectionUp)
        assertEquals(favoriteX, assertFocused(favorites.last()).fetchSemanticsNode().boundsInRoot.left, 2f)
    }

    @Test fun movingBetweenRowsFindsTheRememberedChannelAfterReordering() {
        val recent = List(9) { "最近频道${it + 1}" }
        showDashboard(favorites = emptyList(), recent = recent)
        press(Key.DirectionDown)
        assertFocused(recent.first())
        for (index in 1..6) {
            press(Key.DirectionRight)
            assertFocused(recent[index])
        }
        press(Key.DirectionUp)
        assertFocused("直播")
        compose.runOnIdle {
            val remembered = dashboardRecent[6]
            dashboardRecent = listOf(dashboardRecent.first(), remembered) +
                dashboardRecent.drop(1).filterNot { it == remembered }
        }
        press(Key.DirectionDown)
        assertFocused(recent[6])
    }

    @Test fun aRemovedRememberedCardFallsBackToTheFirstCardInTheRow() {
        val recent = List(9) { "最近频道${it + 1}" }
        showDashboard(favorites = emptyList(), recent = recent)
        press(Key.DirectionDown)
        assertFocused(recent.first())
        for (index in 1..6) {
            press(Key.DirectionRight)
            assertFocused(recent[index])
        }
        press(Key.DirectionUp)
        assertFocused("直播")
        compose.runOnIdle {
            dashboardRecent = dashboardRecent.filterNot { it.name == recent[6] }
        }
        press(Key.DirectionDown)
        assertFocused(recent.first())
    }

    private fun assertSecondSlotWhileScrolling(names: List<String>) {
        press(Key.DirectionDown)
        val firstX = assertFocused(names[0]).fetchSemanticsNode().boundsInRoot.left
        press(Key.DirectionRight)
        val secondX = assertFocused(names[1]).fetchSemanticsNode().boundsInRoot.left
        assertTrue(secondX > firstX)

        for (index in 2..5) {
            press(Key.DirectionRight)
            assertEquals(secondX, assertFocused(names[index]).fetchSemanticsNode().boundsInRoot.left, 2f)
        }
        for (index in 6..names.lastIndex) {
            press(Key.DirectionRight)
            assertFocused(names[index])
        }
        assertTrue(assertFocused(names.last()).fetchSemanticsNode().boundsInRoot.left > secondX)

        for (index in names.lastIndex - 1 downTo 0) {
            press(Key.DirectionLeft)
            val left = assertFocused(names[index]).fetchSemanticsNode().boundsInRoot.left
            if (index in 1..5) assertEquals(secondX, left, 2f)
            if (index == 0) assertEquals(firstX, left, 2f)
        }
    }

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
