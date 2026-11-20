package top.yogiczy.mytv.tv.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import top.yogiczy.mytv.core.data.entities.channel.Channel
import top.yogiczy.mytv.core.data.entities.channel.ChannelLine
import top.yogiczy.mytv.core.data.entities.channel.ChannelLineList
import top.yogiczy.mytv.core.data.utils.SP
import top.yogiczy.mytv.tv.ui.screen.settings.SettingsViewModel
import top.yogiczy.mytv.tv.ui.screensold.quickop.components.QuickOpBtnList
import top.yogiczy.mytv.tv.ui.screensold.videoplayer.player.VideoPlayer
import top.yogiczy.mytv.tv.ui.theme.MyTvTheme

/** Exercise the real toolbar input and lazy focus scrolling without changing user preferences. */
@OptIn(ExperimentalTestApi::class)
class QuickOpToolbarTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private var previousSettings: SettingsViewModel? = null
    private val metadata = mutableStateOf(VideoPlayer.Metadata())
    private val tracksReady = mutableStateOf(false)
    private val actions = mutableListOf<String>()
    private val video = VideoPlayer.Metadata.Video(width = 3840, height = 2160, bitrate = 38_000_000, mimeType = "video/hevc")
    private val audio = VideoPlayer.Metadata.Audio(channels = 2, language = "en", mimeType = "audio/mp4a-latm")
    private val allTracks = VideoPlayer.Metadata(video = video, audio = audio,
        videoTracks = listOf(video), audioTracks = listOf(audio),
        subtitleTracks = listOf(VideoPlayer.Metadata.Subtitle(language = "en")))

    @Before fun isolatedSettings() {
        instrumentation.runOnMainSync {
            previousSettings = SettingsViewModel.instance
            SP.getInstance(instrumentation.context).edit().clear().commit()
            SP.init(instrumentation.context)
            SettingsViewModel.instance = SettingsViewModel()
        }
    }

    @After fun restoreSettings() {
        instrumentation.runOnMainSync {
            SettingsViewModel.instance = previousSettings
            SP.getInstance(instrumentation.context).edit().clear().commit()
            SP.init(instrumentation.targetContext)
        }
    }

    private fun show(tracks: VideoPlayer.Metadata = allTracks, ready: Boolean = true) {
        metadata.value = tracks
        tracksReady.value = ready
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                MyTvTheme {
                    Box(Modifier.width(960.dp).background(Color.Black)) {
                        QuickOpBtnList(
                            modifier = Modifier.testTag("toolbar"),
                            channelProvider = { Channel(name = "测试频道", lineList = ChannelLineList(listOf(
                                ChannelLine(name = "线路 1"),
                            ))) },
                            playerMetadataProvider = { metadata.value },
                            tracksReadyProvider = { tracksReady.value },
                            onShowEpg = { actions += "epg" },
                            onShowChannelLine = { actions += "line" },
                            onShowVideoPlayerController = { actions += "controller" },
                            onShowVideoTracks = { actions += "video" },
                            onShowAudioTracks = { actions += "audio" },
                            onShowSubtitleTracks = { actions += "subtitle" },
                            toDashboardScreen = { actions += "dashboard" },
                            onShowMoreSettings = { actions += "settings" },
                            onDismissRequest = { actions += "dismiss" },
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun press(key: Key, times: Int = 1) {
        repeat(times) {
            compose.onRoot().performKeyInput { pressKey(key) }
            compose.waitForIdle()
        }
    }

    @Test fun remoteReachesTheLastItemAndReturnsWithoutClippingTheFirst() {
        show()
        compose.onNodeWithContentDescription("节目单").assertIsFocused()
        press(Key.DirectionRight, 2)
        compose.onNodeWithContentDescription("播放控制").assertIsFocused()
        press(Key.DirectionCenter)
        compose.runOnIdle { assertEquals(listOf("controller"), actions) }
        press(Key.DirectionRight, 9)
        compose.onNodeWithContentDescription("设置").assertIsDisplayed().assertIsFocused()
        press(Key.DirectionLeft, 11)
        val first = compose.onNodeWithContentDescription("节目单").assertIsFocused().assertIsDisplayed()
        val bounds = first.fetchSemanticsNode().boundsInRoot
        val row = compose.onNodeWithTag("toolbar").fetchSemanticsNode().boundsInRoot
        assertTrue("First item lost its safe inset", bounds.left > row.left)
        press(Key.DirectionCenter)
        compose.runOnIdle { assertEquals(listOf("controller", "epg"), actions) }
    }

    @Test fun lateTracksKeepTheFocusedActionAndVideoParametersFitTwoLines() {
        show(VideoPlayer.Metadata(), ready = false)
        press(Key.DirectionRight, 7)
        compose.onNodeWithContentDescription("主界面").assertIsFocused()
        compose.runOnIdle { metadata.value = allTracks }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("主界面").assertIsFocused()
        val label = "3840 × 2160\n38.00Mbps"
        compose.onNodeWithTag("toolbar").performScrollToNode(hasText(label))
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(label, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(2, layouts.single().lineCount)
        assertFalse("Resolution or bitrate was truncated", layouts.single().hasVisualOverflow)
        val width = compose.onNode(hasContentDescription("视频轨道", substring = true)).fetchSemanticsNode().boundsInRoot.width
        compose.onNodeWithTag("toolbar").performScrollToNode(hasContentDescription("音轨", substring = true))
        assertEquals(width, compose.onNode(hasContentDescription("音轨", substring = true)).fetchSemanticsNode().boundsInRoot.width, 0.1f)
    }

    @Test fun touchSwipeAndTapReachTrackMenusAndTriggerOnlyOnce() {
        show()
        val row = compose.onNodeWithTag("toolbar")
        row.performTouchInput { swipeLeft() }
        compose.waitForIdle()
        row.performScrollToNode(hasContentDescription("字幕，关闭"))
        compose.onNodeWithContentDescription("字幕，关闭").performTouchInput { click() }
        row.performScrollToNode(hasContentDescription("音轨", substring = true))
        compose.onNode(hasContentDescription("音轨", substring = true)).performTouchInput { click() }
        row.performTouchInput { swipeRight() }
        compose.waitForIdle()
        row.performScrollToNode(hasContentDescription("节目单"))
        compose.onNodeWithContentDescription("节目单").performTouchInput { click() }
        compose.runOnIdle { assertEquals(listOf("subtitle", "audio", "epg"), actions) }
    }

    @Test fun absentTracksRemainVisibleButCannotReceiveTouchOrRemoteFocus() {
        show(VideoPlayer.Metadata(), ready = true)
        compose.onNodeWithTag("toolbar").performScrollToNode(hasContentDescription("视频轨道，无视轨"))
        compose.onNodeWithContentDescription("视频轨道，无视轨").assertIsNotEnabled().performTouchInput { click() }
        compose.onNodeWithContentDescription("音轨，无音轨").assertIsNotEnabled()
        compose.onNodeWithContentDescription("字幕，无字幕").assertIsNotEnabled()
        compose.runOnIdle { assertTrue(actions.isEmpty()) }
        press(Key.DirectionRight, 7)
        compose.onNodeWithContentDescription("主界面").assertIsFocused()
        compose.onNodeWithContentDescription("清除缓存").assertDoesNotExist()
        compose.onNodeWithContentDescription("返回").assertDoesNotExist()
    }

    @Test fun aFocusedTrackThatDisappearsReturnsFocusToTheFirstAction() {
        show()
        press(Key.DirectionRight, 7)
        compose.onNodeWithContentDescription("字幕，关闭").assertIsFocused()
        compose.runOnIdle { metadata.value = VideoPlayer.Metadata(); tracksReady.value = false }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("节目单").assertIsFocused()
    }
}
