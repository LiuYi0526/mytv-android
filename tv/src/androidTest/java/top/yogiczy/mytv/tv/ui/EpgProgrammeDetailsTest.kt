package top.yogiczy.mytv.tv.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.TextLayoutResult
import androidx.tv.material3.Text
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import top.yogiczy.mytv.core.data.entities.channel.Channel
import top.yogiczy.mytv.core.data.entities.epg.EpgProgramme
import top.yogiczy.mytv.core.data.entities.epg.EpgProgrammeList
import top.yogiczy.mytv.tv.ui.material.PopupContent
import top.yogiczy.mytv.tv.ui.material.PopupHandleableApplication
import top.yogiczy.mytv.tv.ui.material.SimplePopup
import top.yogiczy.mytv.tv.ui.screen.live.channels.components.LiveChannelsChannelInfo
import top.yogiczy.mytv.tv.ui.screensold.epg.components.EpgProgrammeItemList
import top.yogiczy.mytv.tv.ui.theme.MyTvTheme

/** Details use the real list and popup focus stack; no user settings/source changes. */
@OptIn(ExperimentalTestApi::class)
class EpgProgrammeDetailsTest {
    @get:Rule val compose = createComposeRule()
    private val now = System.currentTimeMillis()
    private val description = (1..50).joinToString("\n") { "第 $it 段节目简介：这是供完整阅读和滚动查看的节目内容。" }
    private val live = EpgProgramme(now - 600_000, now + 600_000, "Current programme", description)
    private val past = EpgProgramme(now - 1_200_000, now - 600_000, "Past programme", description)
    private val next = EpgProgramme(now + 600_000, now + 1_200_000, "Next programme", description)
    private var replayCount = 0
    private var reserveCount = 0
    private var liveCount = 0
    private var currentPlayback by mutableStateOf<EpgProgramme?>(null)

    private fun showGuide(
        programmes: List<EpgProgramme> = listOf(past, live, next),
        playback: EpgProgramme? = null,
        supportPlayback: Boolean = true,
    ) {
        currentPlayback = playback
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                MyTvTheme {
                    PopupHandleableApplication {
                        PopupContent(visibleProvider = { true }) {
                            Box(Modifier.width(600.dp).height(400.dp)) {
                                EpgProgrammeItemList(
                                    epgProgrammeListProvider = { EpgProgrammeList(programmes) },
                                    supportPlaybackProvider = { supportPlayback },
                                    currentPlaybackProvider = { currentPlayback },
                                    onPlayback = { replayCount++ },
                                    onLive = { liveCount++; currentPlayback = null },
                                    onReserve = { reserveCount++ },
                                )
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    private fun label(programme: EpgProgramme) = programme.title

    @Test fun remoteConfirmOnLiveProgrammeLeavesReplayWithoutOpeningDetails() {
        showGuide(playback = past)
        press(Key.DirectionDown)
        press(Key.Enter)
        assertEquals(1, liveCount)
        assertEquals(null, currentPlayback)
        assertEquals(0, replayCount)
        assertEquals(0, reserveCount)
        compose.onNodeWithText("节目详情").assertDoesNotExist()
    }

    @Test fun touchCanSelectLiveWithoutCatchupSupportOrSynopsis() {
        showGuide(listOf(live.copy(description = null)), supportPlayback = false)
        compose.onNodeWithText(live.title).performTouchInput { click() }
        assertEquals(1, liveCount)
        assertEquals(0, replayCount)
        assertEquals(0, reserveCount)
        compose.onNodeWithText("节目详情").assertDoesNotExist()
    }

    @Test fun longPressOnLiveOnlyShowsDetailsAndKeepsReplay() {
        showGuide(playback = past)
        compose.onNodeWithText(live.title).performTouchInput { longClick() }
        compose.onNodeWithText("节目详情").assertIsDisplayed()
        assertEquals(past, currentPlayback)
        assertEquals(0, liveCount)
        assertEquals(0, replayCount)
        assertEquals(0, reserveCount)
    }

    @Test fun touchLongPressDoesNotReplayOrReserveAndCanClose() {
        showGuide()
        for (programme in listOf(past, next)) {
            compose.onNodeWithText(programme.title).performTouchInput { longClick() }
            compose.onNodeWithText("节目详情").assertIsDisplayed()
            compose.onAllNodesWithText(programme.title).assertCountEquals(2)
            compose.onNodeWithContentDescription("节目简介").assertExists()
            assertEquals(0, replayCount)
            assertEquals(0, reserveCount)
            compose.onNodeWithText("关闭详情").performTouchInput { click() }
            compose.onNodeWithText("节目详情").assertDoesNotExist()
        }
    }

    @Test fun remoteDetailsScrollsAndBackRestoresTheProgrammeRow() {
        showGuide()
        // L is the existing keyboard binding of the remote long-select action.
        press(Key.L)
        compose.onNodeWithText("节目详情").assertIsDisplayed()
        press(Key.DirectionDown)
        compose.waitUntil(3_000) {
            compose.onNodeWithContentDescription("节目简介").fetchSemanticsNode()
                .config[SemanticsProperties.VerticalScrollAxisRange].value() > 0f
        }
        press(Key.Back)
        compose.onNodeWithText("节目详情").assertDoesNotExist()
        compose.waitUntil(3_000) {
            val node = compose.onNodeWithText(label(live), useUnmergedTree = true).fetchSemanticsNode()
            generateSequence(node) { it.parent }
                .any { it.config.getOrNull(SemanticsProperties.Focused) == true }
        }
        assertEquals(0, replayCount)
        assertEquals(0, reserveCount)
    }

    @Test fun programmeRowKeepsReplayAndReservationShortcuts() {
        showGuide()
        compose.onNodeWithText(label(past)).performTouchInput { click() }
        compose.onNodeWithText(label(next)).performTouchInput { click() }
        assertEquals(1, replayCount)
        assertEquals(1, reserveCount)
        compose.onNodeWithText("节目详情").assertDoesNotExist()
    }

    @Test fun synopsisStaysOnOneLineAndFocusDoesNotChangeRowHeight() {
        val longTitle = "Long programme title ".repeat(20)
        showGuide(listOf(past.copy(description = null), live.copy(title = longTitle), next))
        fun rowHeight(title: String): Float {
            val node = compose.onNodeWithText(title, useUnmergedTree = true).fetchSemanticsNode()
            return generateSequence(node) { it.parent }
                .first { it.config.contains(SemanticsProperties.Focused) }.boundsInRoot.height
        }
        val heightBefore = rowHeight(longTitle)
        assertEquals(heightBefore, rowHeight(past.title), 0.1f)
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onAllNodesWithText(description, useUnmergedTree = true)[0]
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(1, layouts.single().lineCount)
        assertTrue(layouts.single().hasVisualOverflow)
        press(Key.DirectionDown)
        assertEquals(heightBefore, rowHeight(longTitle), 0.1f)
        assertEquals(heightBefore, rowHeight(next.title), 0.1f)
    }

    @Test fun titleOnlyProgrammesUseTwoStaticLinesWithTheSameRowHeight() {
        val longTitle = "A long programme title without a synopsis ".repeat(20)
        val blankDescriptionTitle = "Another programme title with a blank synopsis ".repeat(20)
        showGuide(listOf(past.copy(title = longTitle, description = null), live,
            next.copy(title = blankDescriptionTitle, description = " \n ")))
        fun layout(title: String): TextLayoutResult {
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(title, useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            return layouts.single()
        }
        fun rowHeight(title: String): Float {
            val node = compose.onNodeWithText(title, useUnmergedTree = true).fetchSemanticsNode()
            return generateSequence(node) { it.parent }
                .first { it.config.contains(SemanticsProperties.Focused) }.boundsInRoot.height
        }
        for (title in listOf(longTitle, blankDescriptionTitle)) {
            assertEquals(2, layout(title).lineCount)
            assertTrue(layout(title).hasVisualOverflow)
            assertEquals(rowHeight(live.title), rowHeight(title), .1f)
        }
        press(Key.DirectionUp)
        // A focused title-only row must still wrap and truncate, rather than marquee.
        assertEquals(2, layout(longTitle).lineCount)
        assertTrue(layout(longTitle).hasVisualOverflow)
        assertEquals(rowHeight(live.title), rowHeight(longTitle), .1f)
    }

    @Test fun titleOnlyProgrammeOpensCompleteScrollableDetailsByTouchAndRemote() {
        val longTitle = "Full programme title ".repeat(80)
        showGuide(listOf(live.copy(title = longTitle, description = null)))
        compose.onNodeWithText("长按确认 / 触摸长按查看节目详情").assertIsDisplayed()
        compose.onNodeWithText(longTitle).performTouchInput { longClick() }
        compose.onNodeWithText("节目详情").assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNode(hasText(longTitle) and hasAnyAncestor(hasContentDescription("节目信息")),
            useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue(layouts.single().lineCount > 2)
        assertTrue(!layouts.single().hasVisualOverflow)
        compose.onNodeWithContentDescription("节目简介").assertDoesNotExist()
        press(Key.DirectionDown)
        compose.waitUntil(3_000) {
            compose.onNodeWithContentDescription("节目信息").fetchSemanticsNode()
                .config[SemanticsProperties.VerticalScrollAxisRange].value() > 0f
        }
        press(Key.Back)
        compose.onNodeWithText("节目详情").assertDoesNotExist()
        press(Key.L)
        compose.onNodeWithText("节目详情").assertIsDisplayed()
        assertEquals(0, liveCount)
        assertEquals(0, replayCount)
        assertEquals(0, reserveCount)
    }

    @Test fun missingGuideOnlyShowsChannelInformation() {
        compose.setContent {
            MyTvTheme {
                LiveChannelsChannelInfo(
                    channelProvider = { Channel(name = "No EPG channel") },
                    showChannelLogo = false,
                    showProgrammeDescription = true,
                )
            }
        }
        compose.onNodeWithText("No EPG channel").assertIsDisplayed()
        compose.onNodeWithText("精彩节目").assertDoesNotExist()
        compose.onNodeWithText("00:00 — 23:59").assertDoesNotExist()
        assertTrue(compose.onAllNodesWithContentDescription("节目简介").fetchSemanticsNodes().isEmpty())
    }

    @Test fun popupRecompositionUpdatesContentWithoutDuplicatesAndDisposalCleansItUp() {
        var revision by mutableIntStateOf(0)
        var visible by mutableStateOf(true)
        var mounted by mutableStateOf(true)
        compose.setContent {
            MyTvTheme {
                PopupHandleableApplication {
                    if (mounted) {
                        SimplePopup(
                            modifier = Modifier.semantics { stateDescription = "revision:$revision" },
                            visibleProvider = { visible },
                            onDismissRequest = { visible = false },
                        ) { Text("Popup revision $revision") }
                    }
                }
            }
        }
        compose.onAllNodesWithText("Popup revision 0").assertCountEquals(1)
        compose.runOnIdle { revision++ }
        compose.onAllNodesWithText("Popup revision 1").assertCountEquals(1)
        compose.onNodeWithText("Popup revision 0").assertDoesNotExist()
        compose.runOnIdle { visible = false }
        compose.onNodeWithText("Popup revision 1").assertDoesNotExist()
        compose.runOnIdle { visible = true }
        compose.onAllNodesWithText("Popup revision 1").assertCountEquals(1)
        compose.runOnIdle { mounted = false }
        compose.onNodeWithText("Popup revision 1").assertDoesNotExist()
    }
}
