package top.yogiczy.mytv.tv.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import top.yogiczy.mytv.core.data.utils.Globals
import top.yogiczy.mytv.core.data.utils.SP
import top.yogiczy.mytv.tv.ui.screen.dashboard.components.DashboardTime
import top.yogiczy.mytv.tv.ui.screen.settings.subcategories.SettingsUiTimeShowModeScreen
import top.yogiczy.mytv.tv.ui.screensold.datetime.DatetimeScreen
import top.yogiczy.mytv.tv.ui.screensold.datetime.components.DateTimeDetail
import top.yogiczy.mytv.tv.ui.theme.MyTvTheme
import top.yogiczy.mytv.tv.ui.utils.Configs

@OptIn(ExperimentalTestApi::class)
class WallClockSettingsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun secondsDefaultOnAndOlderSettingsKeepTheStoredValue() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val testPreferences = SP.getInstance(instrumentation.context)
        val key = "UI_TIME_SHOW_SECONDS"
        val previous = testPreferences.all[key] as? Boolean
        testPreferences.edit().remove(key).commit()
        try {
            SP.init(instrumentation.context)
            assertTrue(Configs.uiTimeShowSeconds)
            Configs.fromPartial(Globals.json.decodeFromString("""{"uiTimeShowSeconds":false}"""))
            assertFalse(Configs.uiTimeShowSeconds)
            assertEquals(false, Configs.toPartial().uiTimeShowSeconds)

            Configs.fromPartial(Globals.json.decodeFromString("{}"))
            assertFalse(Configs.uiTimeShowSeconds)
        } finally {
            testPreferences.edit().apply {
                if (previous == null) remove(key) else putBoolean(key, previous)
            }.commit()
            SP.init(instrumentation.targetContext)
        }
    }

    @Test fun secondsSwitchCanBeChangedByTouchAndRemote() {
        var showSeconds by mutableStateOf(true)
        var modeChanges = 0
        compose.setContent {
            MyTvTheme {
                SettingsUiTimeShowModeScreen(
                    timeShowModeProvider = { Configs.UiTimeShowMode.ALWAYS },
                    onTimeShowModeChanged = { modeChanges++ },
                    showSeconds = showSeconds,
                    onShowSecondsChanged = { showSeconds = it },
                )
            }
        }
        val option = compose.onNodeWithText("显示秒")
        compose.onNodeWithText("12:34:56").assertIsDisplayed()
        option.performTouchInput { click() }
        compose.onNodeWithText("12:34").assertIsDisplayed()
        option.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.onRoot().performKeyInput { pressKey(Key.Enter) }
        compose.onNodeWithText("12:34:56").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, modeChanges) }
    }

    @Test fun allWallClocksApplyTheToggleAndPlayerCanStillHideTheClock() {
        var showSeconds by mutableStateOf(true)
        var mode by mutableStateOf(Configs.UiTimeShowMode.ALWAYS)
        compose.setContent {
            MyTvTheme {
                Column {
                    DatetimeScreen(
                        modifier = Modifier.height(80.dp).testTag("player clock"),
                        showModeProvider = { mode },
                        showSeconds = showSeconds,
                    )
                    DateTimeDetail(modifier = Modifier.testTag("clock details"), showSeconds = showSeconds)
                    DashboardTime(modifier = Modifier.testTag("dashboard clock"), showSeconds = showSeconds)
                }
            }
        }
        val clocks = listOf("player clock", "clock details", "dashboard clock")
        val withSeconds = """(?:.* )?\p{Nd}{2}:\p{Nd}{2}:\p{Nd}{2}""".toRegex()
        val withoutSeconds = """(?:.* )?\p{Nd}{2}:\p{Nd}{2}""".toRegex()
        clocks.forEach { compose.onNode(clockText(it, withSeconds), useUnmergedTree = true).assertIsDisplayed() }

        compose.runOnIdle { showSeconds = false }
        clocks.forEach { compose.onNode(clockText(it, withoutSeconds), useUnmergedTree = true).assertIsDisplayed() }

        compose.runOnIdle { mode = Configs.UiTimeShowMode.HIDDEN }
        compose.onNode(clockText("player clock", withoutSeconds), useUnmergedTree = true).assertDoesNotExist()
    }

    private fun clockText(tag: String, pattern: Regex): SemanticsMatcher {
        val location = hasTestTag(tag) or hasAnyAncestor(hasTestTag(tag))
        return location and SemanticsMatcher("shows time matching $pattern") { node ->
            node.config.getOrNull(SemanticsProperties.Text)?.any { pattern.matches(it.text) } == true
        }
    }
}
