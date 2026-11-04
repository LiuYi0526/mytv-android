package top.yogiczy.mytv.tv.ui

import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
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
import top.yogiczy.mytv.tv.ui.material.PopupHandleableApplication
import top.yogiczy.mytv.tv.ui.screensold.classicchannel.ClassicChannelScreen
import top.yogiczy.mytv.tv.ui.theme.MyTvTheme
import java.util.Calendar

/** Uses the real channel -> EPG transition, including its focus exit handler. */
@OptIn(ExperimentalTestApi::class)
class ClassicEpgFocusTest {
    @get:Rule val compose = createComposeRule()

    private val current = Channel(name = "Focus channel", epgName = "Focus channel", index = 0)
    private val channels = ChannelList(listOf(current))
    private val group = ChannelGroup("Focus group", channels)
    private val now = System.currentTimeMillis()
    private val live = EpgProgramme(now - 60_000, now + 1_800_000, "Current live programme")
    private val next = EpgProgramme(live.endAt, live.endAt + 1_800_000, "Next programme")
    private val yesterday = Calendar.getInstance().apply {
        add(Calendar.DAY_OF_YEAR, -1)
        set(Calendar.HOUR_OF_DAY, 12)
        set(Calendar.MINUTE, 0)
    }.timeInMillis
    private val replay = EpgProgramme(yesterday, yesterday + 1_800_000, "Replayed programme")
    private var revision by mutableIntStateOf(0)
    private var visible by mutableStateOf(true)
    private var guideProgrammes by mutableStateOf<List<EpgProgramme>?>(null)
    private var playbackCount = 0
    private var reserveCount = 0

    private fun showScreen(
        programmes: List<EpgProgramme>?, playback: EpgProgramme? = null, withPopupHost: Boolean = false,
        viewportWidth: Dp = 960.dp,
    ) {
        guideProgrammes = programmes
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                MyTvTheme {
                    val panelContent: @Composable () -> Unit = {
                        Box(Modifier.width(viewportWidth).height(540.dp)) {
                            PopupContent(visibleProvider = { visible }, onDismissRequest = { visible = false }) {
                                ClassicChannelScreen(
                                    channelGroupListProvider = { ChannelGroupList(listOf(group)) },
                                    currentChannelProvider = { current },
                                    // Metadata changes recompose the EPG without changing its rows.
                                    epgListProvider = {
                                        EpgList(guideProgrammes?.let {
                                            listOf(Epg(listOf(current.epgName), logo = "revision:$revision",
                                                programmeList = EpgProgrammeList(it)))
                                        }.orEmpty())
                                    },
                                    currentPlaybackEpgProgrammeProvider = { playback },
                                    supportPlaybackProvider = { true },
                                    onEpgProgrammePlayback = { _, _ -> playbackCount++ },
                                    onEpgProgrammeReserve = { _, _ -> reserveCount++ },
                                    onClose = { visible = false },
                                )
                            }
                        }
                    }
                    if (withPopupHost) PopupHandleableApplication { panelContent() }
                    else panelContent()
                }
            }
        }
        focusChannels()
    }

    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    private fun focusChannels() {
        // The default panel opens directly on channels; the group name is a header.
        awaitFocused(current.name)
    }

    private fun label(programme: EpgProgramme) = programme.title

    private fun isTextFocused(text: String): Boolean {
        // The channel's name is also displayed in the non-focusable info panel.
        val nodes = compose.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes()
        return nodes.any { node ->
            val focusTarget = generateSequence(node) { it.parent }
                .firstOrNull { it.config.contains(SemanticsProperties.Focused) }
            node.boundsInRoot.width > 0 && node.boundsInRoot.height > 0 &&
                focusTarget?.config?.getOrNull(SemanticsProperties.Focused) == true
        }
    }

    private fun awaitFocused(text: String) {
        try {
            compose.waitUntil(5_000) { isTextFocused(text) }
        } catch (failure: Throwable) {
            val focused = compose.onAllNodes(isFocused(), useUnmergedTree = true)
                .fetchSemanticsNodes().joinToString { "${it.boundsInRoot}: ${it.config}" }
            throw AssertionError("Expected focus on '$text'; focused nodes: $focused", failure)
        }
    }

    @Test fun enteringFromChannelsFocusesLiveProgramme() {
        val earlier = List(12) { index ->
            val start = now - (13 - index) * 1_800_000L
            EpgProgramme(start, start + 1_800_000, "Earlier programme $index")
        }
        showScreen(earlier + live + next)
        press(Key.DirectionRight)
        awaitFocused(label(live))

        press(Key.DirectionDown)
        awaitFocused(label(next))
        compose.runOnIdle { revision++ }
        compose.waitForIdle()
        assertTrue("Recomposition must not steal focus back to the live row", isTextFocused(label(next)))

        compose.runOnIdle { visible = false }
        compose.waitForIdle()
        compose.runOnIdle { visible = true }
        focusChannels()
        press(Key.DirectionRight)
        awaitFocused(label(live))
    }

    @Test fun enteringDuringReplaySelectsTheReplayedProgrammeAndItsDay() {
        showScreen(listOf(replay, live, next), playback = replay)
        press(Key.DirectionRight)
        awaitFocused(label(replay))
    }

    @Test fun unknownReplayFallsBackToTheLiveProgramme() {
        showScreen(listOf(live, next), playback = replay)
        press(Key.DirectionRight)
        awaitFocused(label(live))
    }

    @Test fun absentTodayAndLiveProgrammeFallBackToFirstAvailableProgramme() {
        showScreen(listOf(replay))
        press(Key.DirectionRight)
        awaitFocused(label(replay))
    }

    @Test fun unmatchedGuideShowsAnInertEmptyStateAndCanReturnToChannels() {
        showScreen(null)
        press(Key.DirectionRight)
        awaitFocused("暂无节目单")
        press(Key.Enter)
        assertEquals(0, playbackCount)
        assertEquals(0, reserveCount)
        compose.onNodeWithText("00:00    精彩节目").assertDoesNotExist()
        press(Key.DirectionLeft)
        focusChannels()
        press(Key.DirectionRight)
        awaitFocused("暂无节目单")
        compose.onNodeWithText("暂无节目单").performTouchInput {
            swipe(Offset(width * .1f, centerY), Offset(width * .9f, centerY), 150)
        }
        focusChannels()
        compose.onNodeWithText("暂无节目单").assertDoesNotExist()
    }

    @Test fun emptyGuideUpdatesToLiveProgrammeAndBackWithoutFakeRows() {
        showScreen(emptyList())
        press(Key.DirectionRight)
        awaitFocused("暂无节目单")
        compose.runOnIdle { guideProgrammes = listOf(live, next) }
        awaitFocused(label(live))
        compose.onNodeWithText("暂无节目单").assertDoesNotExist()
        compose.runOnIdle { guideProgrammes = emptyList() }
        awaitFocused("暂无节目单")
        compose.onNodeWithText(label(live)).assertDoesNotExist()
        press(Key.DirectionLeft)
        focusChannels()
    }

    @Test fun refreshRemovingTheSelectedDateFallsBackToAvailableLiveProgramme() {
        showScreen(listOf(replay))
        press(Key.DirectionRight)
        awaitFocused(label(replay))
        compose.runOnIdle { guideProgrammes = listOf(live, next) }
        awaitFocused(label(live))
        compose.onNodeWithText("00:00    精彩节目").assertDoesNotExist()
    }

    @Test fun dateColumnRemainsFullyVisibleWhenProgrammeListTakesFocus() {
        showScreen(listOf(replay, live, next))
        press(Key.DirectionRight)
        awaitFocused(label(live))
        assertDateFitsOnOneLine()
    }

    @Test fun narrowViewportDoesNotSqueezeTheDateColumnAndCanReturnToChannels() {
        showScreen(listOf(replay, live, next), viewportWidth = 720.dp)
        press(Key.DirectionRight)
        awaitFocused(label(live))
        assertDateFitsOnOneLine()
        press(Key.DirectionLeft)
        focusChannels()
        press(Key.DirectionRight)
        awaitFocused(label(live))
        assertDateFitsOnOneLine()
    }

    private fun assertDateFitsOnOneLine() {
        val date = java.text.SimpleDateFormat("MM-dd", java.util.Locale.getDefault()).format(live.startAt)
        val node = compose.onNodeWithText(date, useUnmergedTree = true)
        node.assertIsDisplayed()
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        node.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals("Date must not wrap into multiple lines", 1, layouts.single().lineCount)
        assertTrue("Date must fit without overflow", !layouts.single().hasVisualOverflow)
        assertTrue("Date column must not be clipped after widening the guide",
            node.fetchSemanticsNode().boundsInRoot.width >= layouts.single().size.width - 1f)
    }

    @Test fun openingAndClosingDetailsKeepsTheGuidePositionAndWidth() {
        try {
            showScreen(listOf(live.copy(description = "Programme synopsis for details."), next), withPopupHost = true)
            press(Key.DirectionRight)
            awaitFocused(label(live))
            fun rowBounds() = compose.onAllNodesWithText(label(live), useUnmergedTree = true)
                .fetchSemanticsNodes().first { node ->
                    generateSequence(node) { it.parent }
                        .any { it.config.contains(SemanticsActions.OnLongClick) }
                }.boundsInRoot
            val before = rowBounds()
            fun assertStable() {
                val after = rowBounds()
                assertEquals("Guide must not move sideways", before.left, after.left, 1f)
                assertEquals("Guide must not change width", before.width, after.width, 1f)
                assertEquals("Selected programme must not move vertically", before.top, after.top, 1f)
            }

            compose.onNode(hasText(label(live)) and SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick))
                .performTouchInput { longClick() }
            compose.onNodeWithText("节目详情").assertIsDisplayed()
            assertStable()
            compose.onNodeWithText("关闭详情").performTouchInput { click() }
            awaitFocused(label(live))
            assertStable()

            press(Key.L) // Existing long-select key binding.
            compose.onNodeWithText("节目详情").assertIsDisplayed()
            assertStable()
            press(Key.Back)
            awaitFocused(label(live))
            assertStable()
            assertEquals(0, playbackCount)
            assertEquals(0, reserveCount)
            press(Key.DirectionLeft)
            focusChannels()
        } catch (failure: Throwable) {
            // Some devices remove the test task before the runner can report
            // the assertion. Keep the original failure visible in logcat.
            Log.e("EpgPanelRegression", "Guide/details integration failed", failure)
            throw failure
        }
    }
}
