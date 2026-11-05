package top.yogiczy.mytv.tv.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import top.yogiczy.mytv.core.data.entities.channel.Channel
import top.yogiczy.mytv.core.data.entities.channel.ChannelGroup
import top.yogiczy.mytv.core.data.entities.channel.ChannelGroupList
import top.yogiczy.mytv.core.data.entities.channel.ChannelList
import top.yogiczy.mytv.core.data.entities.epg.Epg
import top.yogiczy.mytv.core.data.entities.epg.EpgList
import top.yogiczy.mytv.core.data.entities.epg.EpgProgramme
import top.yogiczy.mytv.core.data.entities.epg.EpgProgrammeList
import top.yogiczy.mytv.tv.ui.material.PopupContent
import top.yogiczy.mytv.tv.ui.screensold.classicchannel.ClassicChannelScreen
import top.yogiczy.mytv.tv.ui.theme.MyTvTheme
import java.text.SimpleDateFormat
import java.util.Locale

@OptIn(ExperimentalTestApi::class)
class ClassicPanelNavigationTest {
    @get:Rule val compose = createComposeRule()
    private val first = ChannelList(List(3) { Channel(name = "Alpha ${it + 1}", index = it) })
    private val second = ChannelList(List(2) { Channel(name = "Beta ${it + 1}", index = it + 3) })
    private val current = first[1]
    private val groups = ChannelGroupList(listOf(ChannelGroup("分组一", first), ChannelGroup("分组二", second)))
    private val start = System.currentTimeMillis() - 60_000
    private val epgs = EpgList((first + second).map { channel ->
        Epg(listOf(channel.name), programmeList = EpgProgrammeList(listOf(
            EpgProgramme(start, start + 3_600_000, "Live ${channel.name}")
        )))
    })

    private fun showScreen(
        favorites: ChannelList = ChannelList(listOf(current)),
        favoriteMode: Boolean = false,
    ) {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                MyTvTheme {
                    Box(Modifier.width(960.dp).height(540.dp)) {
                        PopupContent(visibleProvider = { true }) {
                            ClassicChannelScreen(
                                channelGroupListProvider = { groups },
                                currentChannelProvider = { current },
                                favoriteChannelListProvider = { favorites },
                                channelFavoriteEnabledProvider = { true },
                                channelFavoriteListVisibleProvider = { favoriteMode },
                                epgListProvider = { epgs },
                                showEpgProgrammeProgressProvider = { true },
                            )
                        }
                    }
                }
            }
        }
        focus(if (favoriteMode) favorites.firstOrNull()?.name ?: "暂无频道" else current.name)
    }

    private fun focus(text: String) {
        try {
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().any { node ->
                    generateSequence(node) { it.parent }
                        .firstOrNull { it.config.contains(SemanticsProperties.Focused) }
                        ?.config?.getOrNull(SemanticsProperties.Focused) == true
                }
            }
        } catch (failure: Throwable) {
            val focused = compose.onAllNodes(isFocused(), useUnmergedTree = true)
                .fetchSemanticsNodes().joinToString { "${it.boundsInRoot}: ${it.config}" }
            throw AssertionError("Expected focus on '$text'; focused nodes: $focused", failure)
        }
    }

    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    private fun programme(channel: Channel) =
        "${SimpleDateFormat("HH:mm", Locale.getDefault()).format(start)}    Live ${channel.name}"

    @Test fun openingDuplicateStreamRestoresTheSelectedSourceGroup() {
        val sameStream = Channel(name = "中视购物")
        val duplicateGroups = ChannelGroupList(listOf(
            ChannelGroup("中国央视", ChannelList(listOf(sameStream.copy()))),
            ChannelGroup("江西移动PLTV", ChannelList(listOf(sameStream.copy()))),
        )).withMetadata()
        val selected = duplicateGroups[1].channelList.single()
        var picked: Channel? = null
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                MyTvTheme {
                    Box(Modifier.width(960.dp).height(540.dp)) {
                        PopupContent(visibleProvider = { true }) {
                            ClassicChannelScreen(
                                channelGroupListProvider = { duplicateGroups },
                                currentChannelProvider = { selected },
                                onChannelSelected = { picked = it },
                            )
                        }
                    }
                }
            }
        }
        focus(selected.name)
        compose.onNodeWithText("江西移动PLTV").assertIsDisplayed()
        compose.onNodeWithText("中国央视").assertDoesNotExist()
        press(Key.Enter)
        assertEquals("江西移动PLTV", picked?.groupName)
        assertEquals(selected.index, picked?.index)
    }

    @Test fun defaultPanelIsCompactAndEpgReturnsToTheLatestFocusedChannel() {
        showScreen()
        compose.onNodeWithText("频道分组").assertDoesNotExist()
        compose.onNodeWithText("分组一").assertIsDisplayed()
        compose.onNodeWithContentDescription("已收藏").assertIsDisplayed()
        assertEquals(1, compose.onAllNodesWithText(current.name).fetchSemanticsNodes().size)
        press(Key.DirectionDown)
        focus(first[2].name)
        press(Key.DirectionRight)
        focus(programme(first[2]))
        press(Key.DirectionLeft)
        focus(first[2].name)
        compose.onNodeWithText("频道分组").assertDoesNotExist()
    }

    @Test fun leftOpensGroupsAndRightConfirmsTheNewGroup() {
        showScreen()
        press(Key.DirectionLeft)
        compose.onNodeWithText("频道分组").assertIsDisplayed()
        focus("分组一")
        press(Key.DirectionDown)
        focus("分组二")
        press(Key.DirectionRight)
        focus(second.first().name)
        compose.onNodeWithText("频道分组").assertDoesNotExist()
        compose.onNodeWithText("分组二").assertIsDisplayed()
    }

    @Test fun touchCanOpenTheHeaderAndSelectAGroupWithOneTap() {
        showScreen()
        compose.onNodeWithText("分组一").performTouchInput { click() }
        compose.onNodeWithText("频道分组").assertIsDisplayed()
        compose.onNodeWithText("分组二").performTouchInput { click() }
        focus(second.first().name)
        compose.onNodeWithText("频道分组").assertDoesNotExist()
    }

    @Test fun emptyFavoritesRemainKeyboardNavigable() {
        showScreen(favorites = ChannelList(), favoriteMode = true)
        press(Key.DirectionLeft)
        focus("我的收藏")
        press(Key.DirectionDown)
        focus("分组一")
        press(Key.DirectionRight)
        focus(first.first().name)
    }

    @Test fun horizontalSwipesOpenAndCloseGroupsRepeatedly() {
        showScreen()
        repeat(2) {
            compose.onNodeWithText(current.name).performTouchInput {
                swipe(Offset(width * .1f, centerY), Offset(width * .9f, centerY), 200)
            }
            compose.onNodeWithText("频道分组").assertIsDisplayed()
            focus("分组一")
            compose.onNodeWithText("频道分组").performTouchInput {
                swipe(Offset(width * .9f, centerY), Offset(width * .1f, centerY), 200)
            }
            compose.onNodeWithText("频道分组").assertDoesNotExist()
            focus(current.name)
        }
    }

    @Test fun verticalDragAndCancelledSwipeDoNotOpenGroups() {
        showScreen()
        compose.onNodeWithText(current.name).performTouchInput {
            swipe(Offset(centerX, height * .9f), Offset(centerX, height * .1f), 200)
        }
        compose.onNodeWithText("频道分组").assertDoesNotExist()
        compose.onNodeWithText(current.name).performTouchInput {
            down(Offset(width * .1f, centerY))
            moveTo(Offset(width * .9f, centerY), 100)
            cancel()
        }
        compose.onNodeWithText("频道分组").assertDoesNotExist()
        // Cancelled movement must not leak into the next tap.
        compose.onNodeWithText("分组一").performTouchInput { click() }
        compose.onNodeWithText("频道分组").assertIsDisplayed()
    }

    @Test fun favoriteModeCanStartOnAChannelOtherThanThePlayingChannel() {
        showScreen(favorites = ChannelList(listOf(second.first())), favoriteMode = true)
        press(Key.DirectionRight)
        focus(programme(second.first()))
    }
}
