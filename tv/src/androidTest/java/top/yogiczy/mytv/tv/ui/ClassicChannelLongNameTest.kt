package top.yogiczy.mytv.tv.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
import kotlin.math.abs

@OptIn(ExperimentalTestApi::class)
class ClassicChannelLongNameTest {
    @get:Rule val compose = createComposeRule()
    private val channels = ChannelList(List(20) {
        Channel(name = "[$it 摔跤世锦赛] 10月31日 19:30 世界摔跤锦标赛半决赛 男子自由式57、74、79、92公斤级", index = it)
    })
    private val current = channels[10]
    private val short = Channel(name = "CCTV-1", index = 20)
    private val groups = ChannelGroupList(listOf(
        ChannelGroup("热门比赛", channels), ChannelGroup("短名称", ChannelList(listOf(short))),
    ))

    private fun showScreen(withGuide: Boolean = false) {
        val now = System.currentTimeMillis()
        val epgs = if (withGuide) EpgList(channels.map {
            Epg(listOf(it.name), programmeList = EpgProgrammeList(listOf(
                EpgProgramme(now - 60_000, now + 3_600_000, "正在直播的比赛"),
            )))
        }) else EpgList()
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                MyTvTheme {
                    Box(Modifier.width(960.dp).height(540.dp)) {
                        PopupContent(visibleProvider = { true }) {
                            ClassicChannelScreen(
                                channelGroupListProvider = { groups },
                                currentChannelProvider = { current },
                                epgListProvider = { epgs },
                            )
                        }
                    }
                }
            }
        }
        awaitFocus(current.name)
    }

    private fun awaitFocus(name: String) = compose.waitUntil(5_000) {
        compose.onAllNodesWithText(name, useUnmergedTree = true).fetchSemanticsNodes().any { node ->
            generateSequence(node) { it.parent }
                .firstOrNull { it.config.contains(SemanticsProperties.Focused) }
                ?.config?.getOrNull(SemanticsProperties.Focused) == true
        }
    }

    private fun textLayout(name: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(name).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        return results.single()
    }

    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    @Test fun longNamesWithoutCurrentProgrammeWrapAndRemainCentered() {
        showScreen()
        assertEquals(2, textLayout(current.name).lineCount)
        assertTrue(!textLayout(current.name).hasVisualOverflow)
        assertEquals(2, textLayout(channels[11].name).lineCount)
        val before = compose.onAllNodes(isFocused(), useUnmergedTree = true).fetchSemanticsNodes()
            .minBy { it.boundsInRoot.width * it.boundsInRoot.height }.boundsInRoot.center.y
        press(Key.DirectionDown)
        awaitFocus(channels[11].name)
        assertEquals(2, textLayout(channels[11].name).lineCount)
        val after = compose.onAllNodes(isFocused(), useUnmergedTree = true).fetchSemanticsNodes()
            .minBy { it.boundsInRoot.width * it.boundsInRoot.height }.boundsInRoot.center.y
        assertTrue("Wrapped rows must stay centered", abs(after - before) < 2f)
    }

    @Test fun longNameGroupWidensWithinLimitAndShortGroupReturnsToCompactWidth() {
        showScreen()
        val wide = compose.onNodeWithText("热门比赛").fetchSemanticsNode().boundsInRoot.width
        assertTrue("Long names need a wider bounded panel: $wide", wide > 500f && wide <= 608f)
        press(Key.DirectionLeft)
        awaitFocus("热门比赛")
        press(Key.DirectionDown)
        awaitFocus("短名称")
        press(Key.DirectionRight)
        awaitFocus(short.name)
        val compact = compose.onNodeWithText("短名称").fetchSemanticsNode().boundsInRoot.width
        assertTrue("Short group should not inherit the wide panel: $compact", compact <= 380f && compact < wide)
    }

    @Test fun currentProgrammeKeepsTheNameToOneLine() {
        showScreen(withGuide = true)
        compose.waitUntil(5_000) { textLayout(current.name).lineCount == 1 }
        assertEquals(1, textLayout(channels[11].name).lineCount)
        assertTrue(compose.onAllNodesWithText("正在直播的比赛").fetchSemanticsNodes().isNotEmpty())
    }
}
