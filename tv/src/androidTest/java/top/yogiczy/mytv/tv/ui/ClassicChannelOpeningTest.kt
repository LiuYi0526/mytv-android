package top.yogiczy.mytv.tv.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import top.yogiczy.mytv.core.data.entities.channel.Channel
import top.yogiczy.mytv.core.data.entities.channel.ChannelGroup
import top.yogiczy.mytv.core.data.entities.channel.ChannelList
import top.yogiczy.mytv.tv.ui.screensold.classicchannel.components.ClassicChannelItemList
import kotlin.math.abs

@OptIn(ExperimentalTestApi::class)
class ClassicChannelOpeningTest {
    @get:Rule val compose = createComposeRule()

    @Test fun openingOnCurrentChannelDoesNotAnimateAnExtraVerticalScroll() {
        verifyOpening(currentIndex = 50, expectCentered = true)
    }

    @Test fun openingOnFirstChannelStaysAtTheStart() {
        verifyOpening(currentIndex = 0)
    }

    @Test fun openingOnLastChannelStaysAtTheEnd() {
        verifyOpening(currentIndex = 99)
    }

    @Test fun openingWithLargeTextUsesMeasuredRowHeight() {
        verifyOpening(currentIndex = 50, fontScale = 1.8f, expectCentered = true)
    }

    private fun verifyOpening(
        currentIndex: Int,
        fontScale: Float = 1f,
        expectCentered: Boolean = false,
    ) {
        val channels = ChannelList(List(100) { Channel(name = "Opening channel $it", index = it) })
        val group = ChannelGroup("Opening test", channels)
        val current = channels[currentIndex]
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                MaterialTheme {
                    Box(Modifier.width(320.dp).height(420.dp).testTag("channel viewport")) {
                        ClassicChannelItemList(
                            channelGroupProvider = { group },
                            channelListProvider = { channels },
                            initialChannelProvider = { current },
                        )
                    }
                }
            }
        }
        val positions = mutableListOf<Float>()
        repeat(30) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            val text = compose.onAllNodesWithText(current.name, useUnmergedTree = true)
                .fetchSemanticsNodes().singleOrNull()
            if (text != null && compose.onAllNodes(isFocused(), useUnmergedTree = true)
                    .fetchSemanticsNodes().any { node -> node.boundsInRoot.contains(text.boundsInRoot.center) }) {
                positions += text.boundsInRoot.top
            }
        }
        assertTrue("Current channel never received focus", positions.isNotEmpty())
        assertTrue("Channel moves after initial focus: $positions",
            positions.max() - positions.min() <= 1f)
        if (expectCentered) {
            assertChannelCentered(current.name)
            compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
            compose.mainClock.advanceTimeBy(1_000)
            compose.waitForIdle()
            assertChannelCentered(channels[currentIndex + 1].name)
        }
    }

    private fun assertChannelCentered(name: String) {
        val text = compose.onAllNodesWithText(name, useUnmergedTree = true)
            .fetchSemanticsNodes().single()
        val focused = compose.onAllNodes(isFocused(), useUnmergedTree = true)
            .fetchSemanticsNodes().single { it.boundsInRoot.contains(text.boundsInRoot.center) }
        val viewport = compose.onNodeWithTag("channel viewport").fetchSemanticsNode().boundsInRoot
        assertTrue("Focused channel should stay centered", abs(focused.boundsInRoot.center.y - viewport.center.y) <= 1f)
    }
}
