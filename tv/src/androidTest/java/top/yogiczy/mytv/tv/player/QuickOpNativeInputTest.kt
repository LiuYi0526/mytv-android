package top.yogiczy.mytv.tv.player

import android.graphics.Bitmap
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test
import top.yogiczy.mytv.core.data.utils.SP
import top.yogiczy.mytv.core.data.entities.channel.Channel
import top.yogiczy.mytv.core.data.entities.channel.ChannelLine
import top.yogiczy.mytv.core.data.entities.channel.ChannelLineList
import top.yogiczy.mytv.tv.ui.material.PopupContent
import top.yogiczy.mytv.tv.ui.material.PopupHandleableApplication
import top.yogiczy.mytv.tv.ui.material.popupable
import top.yogiczy.mytv.tv.ui.screen.settings.SettingsViewModel
import top.yogiczy.mytv.tv.ui.screensold.audiotracks.AudioTracksScreen
import top.yogiczy.mytv.tv.ui.screensold.channelline.ChannelLineScreen
import top.yogiczy.mytv.tv.ui.screensold.quickop.QuickOpNavigationState
import top.yogiczy.mytv.tv.ui.screensold.quickop.components.QuickOpBtnList
import top.yogiczy.mytv.tv.ui.screensold.videoplayer.VideoPlayerState
import top.yogiczy.mytv.tv.ui.screensold.videoplayer.player.Media3VideoPlayer
import top.yogiczy.mytv.tv.ui.screensold.videoplayer.player.VideoPlayer
import top.yogiczy.mytv.tv.ui.theme.MyTvTheme
import java.io.File

/** Dispatch real window input without Espresso/UiAutomation's incompatible API-37 service paths. */
class QuickOpNativeInputTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private var activity: ComponentActivity? = null
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun await(message: String, condition: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + 8_000
        while (SystemClock.uptimeMillis() < until) {
            if (condition()) return
            SystemClock.sleep(80)
        }
        assertTrue(message, condition())
    }
    private fun composeRoot(view: View): Pair<View, SemanticsOwner>? {
        // Read this app's public semantics owner; no framework hidden APIs or production hooks.
        view.javaClass.methods.firstOrNull { it.name.startsWith("getSemanticsOwner") && it.parameterCount == 0 }
            ?.invoke(view)?.let { if (it is SemanticsOwner) return view to it }
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            composeRoot(view.getChildAt(index))?.let { return it }
        }
        return null
    }
    private fun node(description: String): SemanticsNode? {
        fun find(root: SemanticsNode): SemanticsNode? {
            if (root.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(description) == true) return root
            for (child in root.children) find(child)?.let { return it }
            return null
        }
        var result: SemanticsNode? = null
        main { composeRoot(activity!!.window.decorView)?.second?.let { result = find(it.unmergedRootSemanticsNode) } }
        return result
    }
    private fun hasText(text: String): Boolean {
        fun find(root: SemanticsNode): Boolean =
            root.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true || root.children.any(::find)
        var found = false
        main { composeRoot(activity!!.window.decorView)?.second?.let { found = find(it.unmergedRootSemanticsNode) } }
        return found
    }
    private val SemanticsNode.isEnabled get() = !config.contains(SemanticsProperties.Disabled)
    private val SemanticsNode.isFocused get() = config.getOrNull(SemanticsProperties.Focused) == true
    private fun key(code: Int, times: Int = 1) {
        repeat(times) {
            val time = SystemClock.uptimeMillis()
            main {
                activity!!.dispatchKeyEvent(KeyEvent(time, time, KeyEvent.ACTION_DOWN, code, 0))
                activity!!.dispatchKeyEvent(KeyEvent(time, time + 30, KeyEvent.ACTION_UP, code, 0))
            }
            SystemClock.sleep(180)
        }
    }
    private fun tap(description: String) {
        val rect = node(description)!!.boundsInRoot
        val offset = IntArray(2)
        main { composeRoot(activity!!.window.decorView)!!.first.getLocationInWindow(offset) }
        val now = SystemClock.uptimeMillis()
        listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEach { action ->
            val event = MotionEvent.obtain(now, SystemClock.uptimeMillis(), action,
                rect.center.x + offset[0], rect.center.y + offset[1], 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            try { main { activity!!.dispatchTouchEvent(event) } } finally { event.recycle() }
        }
    }
    private fun capture(name: String) {
        var bitmap: Bitmap? = null
        val done = java.util.concurrent.CountDownLatch(1)
        var success = false
        main {
            val root = activity!!.window.decorView
            bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            PixelCopy.request(activity!!.window, bitmap!!, { result -> success = result == PixelCopy.SUCCESS; done.countDown() }, Handler(Looper.getMainLooper()))
        }
        if (done.await(3, java.util.concurrent.TimeUnit.SECONDS) && success) {
            File(instrumentation.targetContext.cacheDir, name).outputStream().use { bitmap!!.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        bitmap?.recycle()
    }

    @Test fun childReturnIsConsumedOnceAndDirectShortcutsHaveNoToolbarParent() {
        val navigation = QuickOpNavigationState()
        navigation.openToolbar()
        val originalListState = navigation.listState
        navigation.openChild("line")
        assertFalse(navigation.returnFromChild("audio"))
        assertTrue(navigation.returnFromChild("line"))
        assertSame(originalListState, navigation.listState)
        assertEquals("line", navigation.focusKey)
        assertFalse(navigation.returnFromChild("line"))
        navigation.openChild("audio")
        navigation.leaveToolbar()
        assertFalse(navigation.returnFromChild("audio"))
        navigation.openToolbar()
        assertEquals("epg", navigation.focusKey)
        assertNotSame(originalListState, navigation.listState)
    }

    @Test fun selectingLineAndClosingAudioRestoreTheirToolbarButtons() {
        var previous: SettingsViewModel? = null
        val navigation = QuickOpNavigationState()
        val toolbar = mutableStateOf(true)
        val child = mutableStateOf<String?>(null)
        val lineIndex = mutableStateOf(0)
        val channel = Channel(name = "Return test", lineList = ChannelLineList(listOf(
            ChannelLine(url = "udp://239.0.0.1:1234", name = "线路 A"),
            ChannelLine(url = "udp://239.0.0.2:1234", name = "线路 B"),
        )))
        val audio = VideoPlayer.Metadata.Audio(channels = 2, isSelected = true)
        fun viewport(): Pair<Int, Int> {
            var result = 0 to 0
            main { result = navigation.listState.firstVisibleItemIndex to navigation.listState.firstVisibleItemScrollOffset }
            return result
        }
        fun settled() = await("Toolbar scroll did not settle") {
            var scrolling = true
            main { scrolling = navigation.listState.isScrollInProgress }
            !scrolling
        }
        fun open(key: String) {
            navigation.openChild(key)
            toolbar.value = false
            child.value = key
        }
        fun close(key: String) {
            child.value = null
            if (navigation.returnFromChild(key)) toolbar.value = true
        }
        try {
            main {
                previous = SettingsViewModel.instance
                SP.getInstance(instrumentation.context).edit().clear().commit()
                SP.init(instrumentation.context)
                SettingsViewModel.instance = SettingsViewModel()
                instrumentation.targetContext.startActivity(Intent(instrumentation.targetContext, ComponentActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            await("Test activity did not resume") {
                main {
                    activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                        .filterIsInstance<ComponentActivity>().firstOrNull { it.javaClass == ComponentActivity::class.java }
                }
                activity != null
            }
            main {
                activity!!.setContent {
                    CompositionLocalProvider(LocalDensity provides Density(1f)) {
                        MyTvTheme {
                            PopupHandleableApplication {
                                Box(Modifier.fillMaxSize().background(Color.Black).popupable()) {
                                    PopupContent(visibleProvider = { child.value == "line" }, onDismissRequest = { close("line") }) {
                                        ChannelLineScreen(channelProvider = { channel },
                                            currentLineProvider = { channel.lineList[lineIndex.value] },
                                            onLineSelected = { lineIndex.value = channel.lineList.indexOf(it); close("line") },
                                            onClose = { close("line") })
                                    }
                                    PopupContent(visibleProvider = { child.value == "audio" }, onDismissRequest = { close("audio") }) {
                                        AudioTracksScreen(trackListProvider = { listOf(audio) },
                                            onTrackChanged = { close("audio") }, onClose = { close("audio") })
                                    }
                                    PopupContent(visibleProvider = { toolbar.value }, onDismissRequest = {
                                        navigation.leaveToolbar(); toolbar.value = false
                                    }) {
                                        QuickOpBtnList(modifier = Modifier.width(560.dp), listState = navigation.listState,
                                            channelProvider = { channel }, channelLineIdxProvider = { lineIndex.value },
                                            playerMetadataProvider = { VideoPlayer.Metadata(audio = audio, audioTracks = listOf(audio)) },
                                            tracksReadyProvider = { true }, initialFocusKeyProvider = { navigation.focusKey },
                                            onShowChannelLine = { open("line") }, onShowAudioTracks = { open("audio") })
                                    }
                                }
                            }
                        }
                    }
                }
            }
            await("Initial focus missing") { node("节目单")?.isFocused == true }
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            await("Line button focus missing") { node("线路 A")?.isFocused == true }
            settled()
            val lineViewport = viewport()
            val lineLeft = node("线路 A")!!.boundsInRoot.left
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Line picker not opened") { hasText("多线路") }
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Selected line did not restore its updated toolbar button") {
                lineIndex.value == 1 && node("线路 B")?.isFocused == true
            }
            await("Selecting a line shifted the toolbar") {
                viewport() == lineViewport && kotlin.math.abs(node("线路 B")!!.boundsInRoot.left - lineLeft) < 1f
            }
            tap("线路 B")
            await("Line picker did not reopen") { hasText("多线路") }
            key(KeyEvent.KEYCODE_BACK)
            await("Back did not restore line button") { node("线路 B")?.isFocused == true }
            await("Touch-opened line picker changed the viewport") {
                viewport() == lineViewport && kotlin.math.abs(node("线路 B")!!.boundsInRoot.left - lineLeft) < 1f
            }
            key(KeyEvent.KEYCODE_DPAD_RIGHT, 4)
            await("Audio button not reachable") { node("音轨，${audio.shortLabel}")?.isFocused == true }
            settled()
            val audioViewport = viewport()
            val audioLeft = node("音轨，${audio.shortLabel}")!!.boundsInRoot.left
            assertTrue("Fixture never scrolled", audioViewport.first > 0 || audioViewport.second > 0)
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Audio picker not opened") { !toolbar.value && child.value == "audio" && hasText("音轨") }
            key(KeyEvent.KEYCODE_BACK)
            await("Offscreen audio button focus not restored") { node("音轨，${audio.shortLabel}")?.isFocused == true }
            await("Returning from audio reset the scrolled viewport") {
                viewport() == audioViewport && kotlin.math.abs(node("音轨，${audio.shortLabel}")!!.boundsInRoot.left - audioLeft) < 1f
            }
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            await("Display mode must follow the track buttons") { node("原始")?.isFocused == true }
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            await("Sleep timer must follow display mode") { node("定时关闭")?.isFocused == true }
            key(KeyEvent.KEYCODE_BACK)
            await("Second Back did not close toolbar") { !toolbar.value && node("节目单") == null }
            main { navigation.leaveToolbar(); child.value = "line" }
            await("Direct line picker not opened") { hasText("多线路") }
            key(KeyEvent.KEYCODE_BACK)
            await("Direct shortcut incorrectly reopened toolbar") { child.value == null && !toolbar.value && node("节目单") == null }
        } finally {
            main {
                activity?.finish()
                SettingsViewModel.instance = previous
                SP.getInstance(instrumentation.context).edit().clear().commit()
                SP.init(instrumentation.targetContext)
            }
        }
    }

    @Test fun loadingSlotsStayPutSkipFocusAndBecomeUnavailableOrUsable() {
        var previous: SettingsViewModel? = null
        val metadata = mutableStateOf(VideoPlayer.Metadata())
        val ready = mutableStateOf(false)
        val actions = java.util.concurrent.CopyOnWriteArrayList<String>()
        try {
            main {
                previous = SettingsViewModel.instance
                SP.getInstance(instrumentation.context).edit().clear().commit()
                SP.init(instrumentation.context)
                SettingsViewModel.instance = SettingsViewModel()
            }
            // Avoid MonitoringInstrumentation's idle-based startActivitySync timeout.
            main { instrumentation.targetContext.startActivity(Intent(instrumentation.targetContext, ComponentActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            await("Test activity did not resume") {
                main {
                    activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                        .filterIsInstance<ComponentActivity>().firstOrNull { it.javaClass == ComponentActivity::class.java }
                }
                activity != null
            }
            main {
                activity!!.setContent {
                    CompositionLocalProvider(LocalDensity provides Density(1f)) {
                        MyTvTheme {
                            Box(Modifier.width(960.dp).background(Color.Black)) {
                                QuickOpBtnList(playerMetadataProvider = { metadata.value }, tracksReadyProvider = { ready.value },
                                    toDashboardScreen = { actions += "dashboard" },
                                    onShowSubtitleTracks = { actions += "subtitle" },
                                    onShowVideoTracks = { actions += "video" })
                            }
                        }
                    }
                }
            }
            await("Initial toolbar focus missing") { node("节目单")?.isFocused == true }
            listOf("视频轨道，加载中", "音轨，加载中", "字幕，加载中").forEach {
                await("Loading slot missing: $it") { node(it) != null }
                assertFalse("Loading slot must be disabled", node(it)!!.isEnabled)
            }
            capture("quick-track-loading.png")
            key(KeyEvent.KEYCODE_DPAD_RIGHT, 7)
            await("Remote did not skip loading tracks") { node("主界面")?.isFocused == true }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            assertEquals(listOf("dashboard"), actions.toList())
            val video = VideoPlayer.Metadata.Video(width = 3840, height = 2160, bitrate = 38_000_000, mimeType = "video/hevc")
            val audio = VideoPlayer.Metadata.Audio(channels = 2)
            main {
                metadata.value = VideoPlayer.Metadata(video = video, audio = audio,
                    videoTracks = listOf(video), audioTracks = listOf(audio),
                    subtitleTracks = listOf(VideoPlayer.Metadata.Subtitle(language = "en")))
                ready.value = true
            }
            await("Loaded tracks moved existing focus") { node("主界面")?.isFocused == true && node("字幕，关闭")?.isEnabled == true }
            key(KeyEvent.KEYCODE_DPAD_LEFT, 3)
            await("Subtitle cannot receive focus after discovery") { node("字幕，关闭")?.isFocused == true }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            assertEquals(listOf("dashboard", "subtitle"), actions.toList())
            main { metadata.value = VideoPlayer.Metadata(); ready.value = true }
            await("Removing a focused track lost toolbar focus") { node("节目单")?.isFocused == true }
            key(KeyEvent.KEYCODE_DPAD_RIGHT, 4)
            await("Decode button focus missing") { node("硬解优先")?.isFocused == true }
            val missing = node("视频轨道，无视轨")!!
            assertFalse(missing.isEnabled)
            val rect = missing.boundsInRoot
            val offset = IntArray(2)
            main { composeRoot(activity!!.window.decorView)!!.first.getLocationInWindow(offset) }
            val now = SystemClock.uptimeMillis()
            listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEach { action ->
                val event = MotionEvent.obtain(now, SystemClock.uptimeMillis(), action,
                    rect.center.x + offset[0], rect.center.y + offset[1], 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
                try { main { activity!!.dispatchTouchEvent(event) } } finally { event.recycle() }
            }
            SystemClock.sleep(200)
            assertEquals("Disabled touch invoked an action", listOf("dashboard", "subtitle"), actions.toList())
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            await("Absent tracks were not skipped") { node("原始")?.isFocused == true }
            assertNotNull(node("音轨，无音轨"))
            assertNotNull(node("字幕，无字幕"))
            assertNull(node("清除缓存"))
            assertNull(node("返回"))
            capture("quick-track-unavailable.png")
            // Current formats without selectable lists must not be mislabeled as absent media.
            main { metadata.value = VideoPlayer.Metadata(video = video, audio = audio) }
            await("Known video format was mislabeled as absent") { node("视频轨道，${video.shortLabel}") != null }
            assertFalse(node("视频轨道，${video.shortLabel}")!!.isEnabled)
            assertNotNull(node("音轨，${audio.shortLabel}"))
            assertFalse(node("音轨，${audio.shortLabel}")!!.isEnabled)
        } finally {
            main {
                activity?.finish()
                SettingsViewModel.instance = previous
                SP.getInstance(instrumentation.context).edit().clear().commit()
                SP.init(instrumentation.targetContext)
            }
        }
    }

    @Test fun discoveryResetsOnPrepareButNotOnOrdinaryRebuffering() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var engine: Media3VideoPlayer? = null
        fun event(name: String, type: Class<*>? = null, value: Any? = null) {
            val method = if (type == null) VideoPlayer::class.java.getDeclaredMethod(name)
                else VideoPlayer::class.java.getDeclaredMethod(name, type)
            method.isAccessible = true
            if (type == null) method.invoke(engine) else method.invoke(engine, value)
        }
        try {
            main {
                SP.init(instrumentation.context)
                val player = Media3VideoPlayer(instrumentation.targetContext, scope).also { engine = it }
                val state = VideoPlayerState(player)
                state.initialize()
                assertFalse(state.tracksReady)
                event("triggerReady")
                assertTrue(state.tracksReady)
                event("triggerBuffering", Boolean::class.javaPrimitiveType, true)
                assertTrue(state.tracksReady)
                event("triggerPrepared")
                assertFalse(state.tracksReady)
                assertEquals(VideoPlayer.Metadata(), state.metadata)
            }
        } finally { main { engine?.release(); scope.cancel(); SP.init(instrumentation.targetContext) } }
    }
}
