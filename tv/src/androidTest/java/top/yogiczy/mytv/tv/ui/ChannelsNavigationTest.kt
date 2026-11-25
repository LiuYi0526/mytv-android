package top.yogiczy.mytv.tv.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.platform.app.InstrumentationRegistry
import androidx.tv.material3.Text
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import top.yogiczy.mytv.core.data.entities.channel.Channel
import top.yogiczy.mytv.core.data.entities.channel.ChannelFavorite
import top.yogiczy.mytv.core.data.entities.channel.ChannelFavoriteList
import top.yogiczy.mytv.core.data.entities.channel.ChannelGroup
import top.yogiczy.mytv.core.data.entities.channel.ChannelGroupList
import top.yogiczy.mytv.core.data.entities.channel.ChannelList
import top.yogiczy.mytv.core.data.entities.epg.Epg
import top.yogiczy.mytv.core.data.entities.epg.EpgDisplaySchedule
import top.yogiczy.mytv.core.data.entities.epg.EpgList
import top.yogiczy.mytv.core.data.entities.epg.EpgProgramme
import top.yogiczy.mytv.core.data.entities.epg.EpgProgrammeList
import top.yogiczy.mytv.core.data.utils.SP
import top.yogiczy.mytv.tv.ui.screen.channels.ChannelsScreen
import top.yogiczy.mytv.tv.ui.screen.components.AppScreen
import top.yogiczy.mytv.tv.ui.screen.dashboard.components.DashboardFavoriteList
import top.yogiczy.mytv.tv.ui.screen.dashboard.components.DashboardHistoryList
import top.yogiczy.mytv.tv.ui.screen.favorites.FavoritesScreen
import top.yogiczy.mytv.tv.ui.theme.MyTvTheme
import top.yogiczy.mytv.tv.ui.utils.Configs

@OptIn(ExperimentalTestApi::class)
class ChannelsNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val firstGroup = ChannelGroup("分组甲", ChannelList(listOf(
        Channel(name = "甲台一"), Channel(name = "甲台二"),
    )))
    private val secondGroup = ChannelGroup("分组乙", ChannelList(listOf(
        Channel(name = "乙台一"), Channel(name = "乙台二"),
    )))
    private val groups = ChannelGroupList(listOf(firstGroup, secondGroup))
    private val favorites = ChannelFavoriteList(listOf(
        ChannelFavorite(firstGroup.channelList.first(), "订阅甲", firstGroup.name),
        ChannelFavorite(secondGroup.channelList.first(), "订阅乙", secondGroup.name),
    ))

    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    private fun awaitFocus(vararg channelOrGroupNames: String) {
        try {
            compose.waitUntil(5_000) {
                channelOrGroupNames.any { name ->
                    compose.onAllNodesWithText(name, useUnmergedTree = true).fetchSemanticsNodes().any { node ->
                        generateSequence(node) { it.parent }
                            .firstOrNull { it.config.contains(SemanticsProperties.Focused) }
                            ?.config?.getOrNull(SemanticsProperties.Focused) == true
                    }
                }
            }
        } catch (failure: Throwable) {
            val focused = compose.onAllNodes(isFocused(), useUnmergedTree = true).fetchSemanticsNodes()
            throw AssertionError("Expected focus in ${channelOrGroupNames.toList()}, focused nodes: $focused", failure)
        }
    }

    @Test fun opensOnTheFirstCardAndDirectionSelectsGroups() {
        compose.setContent {
            MyTvTheme { ChannelsScreen(channelGroupListProvider = { groups }) }
        }
        awaitFocus("甲台一")
        press(Key.DirectionUp)
        awaitFocus("分组甲")
        press(Key.DirectionRight)
        awaitFocus("分组乙")
        compose.onNodeWithText("乙台一").assertIsDisplayed()
        compose.onNodeWithText("甲台一").assertDoesNotExist()
        press(Key.DirectionDown)
        awaitFocus("乙台一", "乙台二")
        press(Key.DirectionUp)
        awaitFocus("分组乙")
        press(Key.DirectionUp)
        awaitFocus("返回")
        press(Key.DirectionDown)
        awaitFocus("分组乙")
        compose.onNodeWithText("乙台一").assertIsDisplayed()
    }

    @Test fun allChannelsDisplayLongNamesAndCurrentProgrammes() = verifyLongChannelNames { channels, epg ->
        val groups = ChannelGroupList(listOf(ChannelGroup("Global", channels)))
        ChannelsScreen(
            channelGroupListProvider = { groups },
            epgListProvider = { epg },
            allowTwoLineName = true,
        )
    }

    @Test fun favoritesDisplayLongNamesAndCurrentProgrammes() = verifyLongChannelNames { channels, epg ->
        val favorites = ChannelFavoriteList(channels.map { ChannelFavorite(it, "订阅甲", "Global") })
        FavoritesScreen(channelFavoriteListProvider = { favorites }, epgListProvider = { epg })
    }

    @Test fun dashboardFavoritesDisplayLongNamesAndCurrentProgrammes() = verifyDashboardLongChannelNames { channels, epg ->
        val favorites = ChannelFavoriteList(channels.map { ChannelFavorite(it, "订阅甲", "Global") })
        DashboardFavoriteList(channelFavoriteListProvider = { favorites }, epgListProvider = { epg })
    }

    @Test fun dashboardHistoryDisplaysLongNamesAndCurrentProgrammes() = verifyDashboardLongChannelNames { channels, epg ->
        DashboardHistoryList(
            channels = channels,
            epgListProvider = { epg },
            onChannelSelected = {},
            onChannelFavoriteToggle = {},
        )
    }

    private fun verifyDashboardLongChannelNames(row: @Composable (ChannelList, EpgList) -> Unit) =
        verifyLongChannelNames { channels, epg ->
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                AppScreen { row(channels, epg) }
            }
        }

    private fun verifyLongChannelNames(screen: @Composable (ChannelList, EpgList) -> Unit) {
        val afghanistan = Channel(name = "Afghanistan International")
        val extraLong = Channel(name = "International Sports Championship and Live Event Coverage Channel")
        val withProgramme = Channel(name = "International Sports and News")
        val short = Channel(name = "CCTV-1")
        val channels = ChannelList(listOf(afghanistan, extraLong, withProgramme, short))
        val now = System.currentTimeMillis()
        val epg = EpgList(listOf(
            Epg(listOf(withProgramme.name), programmeList = EpgProgrammeList(listOf(
                EpgProgramme(now - 60_000, now + 3_600_000, "国际新闻"),
            ))),
        ))
        compose.setContent {
            MyTvTheme { screen(channels, epg) }
        }

        fun focusChannel(channel: Channel) {
            compose.onNode(
                SemanticsMatcher.keyIsDefined(SemanticsActions.RequestFocus) and hasAnyDescendant(hasText(channel.name)),
                useUnmergedTree = true,
            ).performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            awaitFocus(channel.name)
        }

        focusChannel(afghanistan)
        compose.onNodeWithText("国际新闻").assertIsDisplayed()
        compose.onNodeWithText(EpgDisplaySchedule.UNKNOWN_TITLE).assertIsDisplayed()
        val focusedPlaceholder = compose.onNode(
            hasText(EpgDisplaySchedule.UNKNOWN_TITLE) and hasAnyAncestor(isFocused()),
            useUnmergedTree = true,
        )
        focusedPlaceholder.assertDoesNotExist()

        fun layout(channel: Channel): TextLayoutResult {
            val results = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(channel.name, useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
            return results.single()
        }

        assertEquals(2, layout(afghanistan).lineCount)
        assertFalse(layout(afghanistan).hasVisualOverflow)
        assertEquals(2, layout(extraLong).lineCount)
        assertTrue(layout(extraLong).isLineEllipsized(1))
        assertEquals(1, layout(withProgramme).lineCount)
        assertTrue(layout(withProgramme).isLineEllipsized(0))

        press(Key.DirectionRight)
        awaitFocus(extraLong.name)
        assertEquals(1, layout(extraLong).lineCount)
        assertFalse(layout(extraLong).hasVisualOverflow)
        focusedPlaceholder.assertIsDisplayed()
        val nameBounds = compose.onNodeWithText(extraLong.name, useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        assertTrue(focusedPlaceholder.fetchSemanticsNode().boundsInRoot.top >= nameBounds.bottom)
        assertEquals(2, layout(afghanistan).lineCount)

        focusChannel(withProgramme)
        assertEquals(2, layout(extraLong).lineCount)
        assertEquals(1, layout(withProgramme).lineCount)
        assertFalse(layout(withProgramme).hasVisualOverflow)
        compose.onNodeWithText("国际新闻").assertIsDisplayed()
    }

    @Test fun upFromTheFirstCardUsesTheSelectedGroupInsteadOfThePreviousFocus() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        try {
            SP.init(instrumentation.context)
            Configs.uiFocusOptimize = true
            compose.setContent {
                MyTvTheme { ChannelsScreen(channelGroupListProvider = { groups }) }
            }
            awaitFocus("甲台一")
            press(Key.DirectionUp)
            awaitFocus("分组甲")
            press(Key.DirectionDown)
            awaitFocus("甲台一", "甲台二")

            // Selecting by touch changes the grid while the last D-pad focus was in group A.
            compose.onNodeWithText("分组乙").performTouchInput { click() }
            compose.onNode(
                SemanticsMatcher.keyIsDefined(SemanticsActions.RequestFocus) and hasAnyDescendant(hasText("乙台一")),
                useUnmergedTree = true,
            ).performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            awaitFocus("乙台一")
            press(Key.DirectionUp)
            awaitFocus("分组乙")
            compose.onNodeWithText("乙台一").assertIsDisplayed()
        } finally {
            SP.init(instrumentation.targetContext)
        }
    }

    @Test fun upFromTheGridReturnsToTheSelectedGroupEvenWhenItIsOffscreen() {
        val manyGroups = ChannelGroupList(
            listOf(firstGroup) + List(30) { ChannelGroup("分组 ${it + 2}", secondGroup.channelList) },
        )
        compose.setContent {
            MyTvTheme { ChannelsScreen(channelGroupListProvider = { manyGroups }) }
        }
        awaitFocus("甲台一")
        compose.onNode(hasScrollToIndexAction() and hasAnyDescendant(hasText("分组甲")))
            .performScrollToIndex(manyGroups.lastIndex)
        compose.onNodeWithText("分组甲").assertDoesNotExist()
        awaitFocus("甲台一")
        press(Key.DirectionUp)
        awaitFocus("分组甲")
    }

    @Test fun touchSelectsTheGroupAndTheBackButtonStillWorks() {
        var backCount = 0
        compose.setContent {
            MyTvTheme {
                ChannelsScreen(channelGroupListProvider = { groups }, onBackPressed = { backCount++ })
            }
        }
        awaitFocus("甲台一")
        compose.onNodeWithText("分组乙").performTouchInput { click() }
        compose.onNodeWithText("乙台一").assertIsDisplayed()
        compose.onNodeWithText("返回").performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, backCount) }
    }

    @Test fun emptyGridStartsOnTheSelectedGroup() {
        val withEmptyGroup = ChannelGroupList(listOf(ChannelGroup("空分组"), secondGroup))
        compose.setContent {
            MyTvTheme { ChannelsScreen(channelGroupListProvider = { withEmptyGroup }) }
        }
        awaitFocus("空分组")
        press(Key.DirectionRight)
        awaitFocus("分组乙")
        compose.onNodeWithText("乙台一").assertIsDisplayed()
    }

    @Test fun favoritesStartOnTheFirstCardAndSwitchSourceOnFocus() {
        compose.setContent {
            MyTvTheme { FavoritesScreen(channelFavoriteListProvider = { favorites }) }
        }
        awaitFocus("甲台一")
        press(Key.DirectionUp)
        awaitFocus("全部")
        press(Key.DirectionRight)
        awaitFocus("订阅甲")
        compose.onNodeWithText("乙台一").assertDoesNotExist()
        press(Key.DirectionRight)
        awaitFocus("订阅乙")
        compose.onNodeWithText("甲台一").assertDoesNotExist()
        press(Key.DirectionDown)
        awaitFocus("乙台一")
        press(Key.DirectionUp)
        awaitFocus("订阅乙")
        press(Key.DirectionUp)
        awaitFocus("返回")
        press(Key.DirectionDown)
        awaitFocus("订阅乙")
        compose.onNodeWithText("乙台一").assertIsDisplayed()
        compose.onNodeWithText("返回").assertIsDisplayed()
        compose.onNodeWithText("清空").assertIsDisplayed()
    }

    @Test fun removingTheFocusedFavoriteFallsBackToAChannelOrBackWhenEmpty() {
        val remaining = mutableStateOf(favorites)
        compose.setContent {
            MyTvTheme {
                FavoritesScreen(
                    channelFavoriteListProvider = { remaining.value },
                    onChannelFavoriteToggle = { channel ->
                        remaining.value = ChannelFavoriteList(remaining.value.filter { it.channel != channel })
                    },
                )
            }
        }
        awaitFocus("甲台一")
        press(Key.DirectionUp)
        press(Key.DirectionRight)
        press(Key.DirectionRight)
        awaitFocus("订阅乙")
        press(Key.DirectionDown)
        awaitFocus("乙台一")
        compose.onNodeWithText("乙台一").performTouchInput { longClick() }
        awaitFocus("甲台一")
        compose.onNodeWithText("订阅乙").assertDoesNotExist()
        compose.onNodeWithText("甲台一").performTouchInput { longClick() }
        awaitFocus("返回")
    }

    @Test fun returningFromPlaybackRestoresTheSelectedCard() {
        compose.setContent {
            MyTvTheme {
                val navigation = rememberNavController()
                NavHost(navigation, startDestination = "channels") {
                    composable("channels") {
                        ChannelsScreen(
                            channelGroupListProvider = { groups },
                            onChannelSelected = { navigation.navigate("playback") },
                        )
                    }
                    composable("playback") { Text("播放页") }
                }
            }
        }
        awaitFocus("甲台一")
        press(Key.DirectionRight)
        awaitFocus("甲台二")
        press(Key.Enter)
        compose.onNodeWithText("播放页").assertIsDisplayed()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        awaitFocus("甲台二")
    }
}
