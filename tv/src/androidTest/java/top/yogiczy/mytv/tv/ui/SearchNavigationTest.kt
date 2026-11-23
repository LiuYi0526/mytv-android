package top.yogiczy.mytv.tv.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.tv.material3.Text
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import top.yogiczy.mytv.core.data.entities.channel.Channel
import top.yogiczy.mytv.core.data.entities.channel.ChannelGroup
import top.yogiczy.mytv.core.data.entities.channel.ChannelGroupList
import top.yogiczy.mytv.core.data.entities.channel.ChannelList
import top.yogiczy.mytv.tv.ui.screen.search.SearchScreen
import top.yogiczy.mytv.tv.ui.theme.MyTvTheme

@OptIn(ExperimentalTestApi::class)
class SearchNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val groups = ChannelGroupList(listOf(
        ChannelGroup("搜索分组", ChannelList(listOf(Channel(name = "Alpha"), Channel(name = "Atlas")))),
    ))

    private fun awaitFocus(text: String) {
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().any { node ->
                generateSequence(node) { it.parent }
                    .firstOrNull { it.config.contains(SemanticsProperties.Focused) }
                    ?.config?.getOrNull(SemanticsProperties.Focused) == true
            }
        }
    }

    @Test fun searchStartsOnTheFirstLetterAndBackRemainsTouchable() {
        var backCount = 0
        compose.setContent {
            MyTvTheme {
                SearchScreen(channelGroupListProvider = { groups }, onBackPressed = { backCount++ })
            }
        }
        awaitFocus("A")
        compose.onRoot().performKeyInput { pressKey(Key.Enter) }
        compose.onNodeWithText("Atlas").assertExists()
        compose.onNodeWithText("返回").performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, backCount) }
    }

    @Test fun returningFromPlaybackRefocusesTheSelectedSearchResult() {
        compose.setContent {
            MyTvTheme {
                val navigation = rememberNavController()
                NavHost(navigation, startDestination = "search") {
                    composable("search") {
                        SearchScreen(
                            channelGroupListProvider = { groups },
                            onChannelSelected = { navigation.navigate("playback") },
                        )
                    }
                    composable("playback") { Text("播放页") }
                }
            }
        }
        awaitFocus("A")
        compose.onRoot().performKeyInput { pressKey(Key.Enter) }
        compose.onNode(
            SemanticsMatcher.keyIsDefined(SemanticsActions.RequestFocus) and hasAnyDescendant(hasText("Atlas")),
            useUnmergedTree = true,
        ).performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        awaitFocus("Atlas")
        compose.onRoot().performKeyInput { pressKey(Key.Enter) }
        compose.onNodeWithText("播放页").assertIsDisplayed()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        awaitFocus("Atlas")
    }
}
