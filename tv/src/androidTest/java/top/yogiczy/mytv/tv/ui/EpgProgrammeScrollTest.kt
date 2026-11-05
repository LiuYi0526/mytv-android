package top.yogiczy.mytv.tv.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import top.yogiczy.mytv.core.data.entities.epg.EpgProgramme
import top.yogiczy.mytv.core.data.entities.epg.EpgProgrammeList
import top.yogiczy.mytv.tv.ui.screensold.epg.components.EpgProgrammeItemList
import top.yogiczy.mytv.tv.ui.theme.MyTvTheme
import kotlin.math.abs

@OptIn(ExperimentalTestApi::class)
class EpgProgrammeScrollTest {
    @get:Rule val compose = createComposeRule()
    private val base = System.currentTimeMillis() - 50 * 3_600_000L
    private val programmes = EpgProgrammeList(List(100) { index ->
        EpgProgramme(base + index * 3_600_000L, base + (index + 1) * 3_600_000L,
            "Programme $index", "Programme synopsis")
    })

    private fun viewport() = compose.onNode(
        SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange),
        useUnmergedTree = true,
    )

    private fun focusedBounds(index: Int): androidx.compose.ui.geometry.Rect {
        val text = compose.onAllNodesWithText(programmes[index].title, useUnmergedTree = true)
            .fetchSemanticsNodes().single()
        return compose.onAllNodes(isFocused(), useUnmergedTree = true).fetchSemanticsNodes()
            .single { it.boundsInRoot.contains(text.boundsInRoot.center) }.boundsInRoot
    }

    private fun showAndVerifyOpening(index: Int, fontScale: Float = 1f, centered: Boolean = false) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                MyTvTheme {
                    Box(Modifier.width(460.dp).height(420.dp)) {
                        EpgProgrammeItemList(
                            epgProgrammeListProvider = { programmes },
                            currentPlaybackProvider = { programmes[index] },
                        )
                    }
                }
            }
        }
        val positions = mutableListOf<Float>()
        repeat(30) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            runCatching { focusedBounds(index) }.getOrNull()?.let { positions += it.top }
        }
        assertTrue("Initial programme never received focus", positions.isNotEmpty())
        assertTrue("Programme moves after initial focus: $positions", positions.max() - positions.min() <= 1f)
        val row = focusedBounds(index)
        val bounds = viewport().fetchSemanticsNode().boundsInRoot
        assertTrue("Boundary programme must stay fully visible", row.top >= bounds.top && row.bottom <= bounds.bottom)
        if (centered) assertCentered(index)
    }

    private fun assertCentered(index: Int) {
        val row = focusedBounds(index)
        val bounds = viewport().fetchSemanticsNode().boundsInRoot
        assertTrue("Programme $index must stay centered: $row in $bounds", abs(row.center.y - bounds.center.y) <= 1f)
    }

    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
    }

    @Test fun openingAndMovingInBothDirectionsKeepTheProgrammeCentered() {
        showAndVerifyOpening(50, centered = true)
        repeat(6) { step -> press(Key.DirectionDown); assertCentered(51 + step) }
        repeat(6) { step -> press(Key.DirectionUp); assertCentered(55 - step) }
    }

    @Test fun largeTextUsesMeasuredRowHeightForCentering() {
        showAndVerifyOpening(50, fontScale = 1.8f, centered = true)
        press(Key.DirectionDown)
        assertCentered(51)
    }

    @Test fun firstProgrammeStaysVisibleWithoutExtraOpeningAnimation() = showAndVerifyOpening(0)

    @Test fun lastProgrammeStaysVisibleWithoutExtraOpeningAnimation() = showAndVerifyOpening(99)

    @Test fun touchScrollingDoesNotSnapBackToTheFocusedProgramme() {
        showAndVerifyOpening(50, centered = true)
        fun scrollValue() = viewport().fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        val before = scrollValue()
        viewport().performTouchInput { swipeUp(durationMillis = 300) }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        assertTrue("Touch must be able to scroll away from the initial position", scrollValue() > before)
    }
}
