package top.yogiczy.mytv.tv.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import top.yogiczy.mytv.core.data.media.PlaybackSeekSteps
import top.yogiczy.mytv.core.data.utils.Globals
import top.yogiczy.mytv.core.data.utils.SP
import top.yogiczy.mytv.tv.ui.material.LocalPopupManager
import top.yogiczy.mytv.tv.ui.material.LocalPopupState
import top.yogiczy.mytv.tv.ui.material.PopupHandleableApplication
import top.yogiczy.mytv.tv.ui.material.PopupManager
import top.yogiczy.mytv.tv.ui.screen.settings.SettingsViewModel
import top.yogiczy.mytv.tv.ui.screen.settings.categories.SettingsVideoPlayerScreen
import top.yogiczy.mytv.tv.ui.theme.MyTvTheme
import top.yogiczy.mytv.tv.ui.utils.Configs

@OptIn(ExperimentalTestApi::class)
class PlaybackSeekStepSettingsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun fourSettingsSaveIndependentlyAndRoundTripThroughConfigExport() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        SP.init(instrumentation.context)
        val saved = Configs.videoPlayerSeekSteps
        try {
            Configs.videoPlayerSeekSteps = PlaybackSeekSteps.DEFAULT
            val settings = SettingsViewModel()
            compose.setContent {
                CompositionLocalProvider(
                    LocalDensity provides Density(1f),
                    LocalPopupManager provides remember { PopupManager() },
                    LocalPopupState provides remember { mutableStateListOf() },
                ) {
                    MyTvTheme {
                        PopupHandleableApplication { SettingsVideoPlayerScreen(settingsViewModel = settings) }
                    }
                }
            }
            setStep("进度条快退步长", "30秒")
            setStep("进度条快进步长", "2分钟")
            setStep("快退按钮步长", "15秒")
            setStep("快进按钮步长", "20秒")

            compose.runOnIdle {
                val expected = PlaybackSeekSteps(30, 120, 15, 20)
                assertEquals(expected, SettingsViewModel().videoPlayerSeekSteps)
                val exported = Globals.json.encodeToString(Configs.toPartial())
                Configs.videoPlayerSeekSteps = PlaybackSeekSteps.DEFAULT
                Configs.fromPartial(Globals.json.decodeFromString<Configs.Partial>(exported))
                assertEquals(expected, Configs.videoPlayerSeekSteps)
                Configs.fromPartial(Globals.json.decodeFromString<Configs.Partial>("{}"))
                assertEquals(expected, Configs.videoPlayerSeekSteps)
            }
        } finally {
            Configs.videoPlayerSeekSteps = saved
            SP.init(instrumentation.targetContext)
        }
    }

    private fun setStep(title: String, choice: String) {
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(title))
        compose.onNodeWithText(title).performTouchInput { click() }
        compose.onAllNodesWithText(title).assertCountEquals(2)
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.Text, listOf(AnnotatedString(choice))))
            .assertIsDisplayed().performTouchInput { click() }
        compose.onAllNodesWithText(title).assertCountEquals(1)
    }
}
