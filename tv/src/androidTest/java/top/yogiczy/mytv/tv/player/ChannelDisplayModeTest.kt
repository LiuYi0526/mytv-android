package top.yogiczy.mytv.tv.player

import android.view.SurfaceView
import android.view.TextureView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import top.yogiczy.mytv.core.data.entities.channel.*
import top.yogiczy.mytv.core.data.entities.epg.EpgProgramme
import top.yogiczy.mytv.core.data.entities.iptvsource.IptvSource
import top.yogiczy.mytv.core.data.utils.SP
import top.yogiczy.mytv.tv.ui.screen.settings.SettingsViewModel
import top.yogiczy.mytv.tv.ui.screensold.main.components.MainContentState
import top.yogiczy.mytv.tv.ui.screensold.videoplayer.VideoPlayerDisplayMode
import top.yogiczy.mytv.tv.ui.screensold.videoplayer.VideoPlayerState
import top.yogiczy.mytv.tv.ui.screensold.videoplayer.player.VideoPlayer
import top.yogiczy.mytv.tv.ui.utils.Configs

@RunWith(AndroidJUnit4::class)
class ChannelDisplayModeTest {
    private val channel = Channel(name = "A", groupName = "移动", index = 0,
        lineList = ChannelLineList(listOf(ChannelLine("https://example.test/PLTV/1"),
            ChannelLine("https://example.test/PLTV/2"))))
    private val other = channel.copy(name = "B", index = 1)

    // Use the TEST package's preferences; never overwrite the user's saved sources/history.
    private fun isolated(block: (CoroutineScope) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val prefs = SP.getInstance(instrumentation.context)
            prefs.edit().clear().commit()
            SP.init(instrumentation.context)
            try {
                Configs.iptvSourceCurrent = IptvSource(name = "测试源", url = "https://example.test/list")
                block(scope)
            } finally {
                scope.cancel()
                prefs.edit().clear().commit()
                SP.init(instrumentation.targetContext)
            }
        }
    }

    private fun state(scope: CoroutineScope, settings: SettingsViewModel, channels: List<Channel>): Pair<MainContentState, VideoPlayerState> {
        val player = VideoPlayerState(IdlePlayer(scope), { settings.videoPlayerDisplayMode })
        val groups = ChannelGroupList(channels.groupBy { it.groupName }.map { (name, items) ->
            ChannelGroup(name, ChannelList(items))
        })
        return MainContentState(scope, player, { groups }, settingsViewModel = settings) to player
    }

    @Test fun switchingChannelsLinesAndReplayRestoresTheSavedMode() = isolated { scope ->
        val settings = SettingsViewModel()
        val (state, player) = state(scope, settings, listOf(channel, other))
        state.changeDisplayMode(VideoPlayerDisplayMode.FOUR_THREE)
        state.changeCurrentChannel(other)
        assertEquals(VideoPlayerDisplayMode.ORIGINAL, player.displayMode)
        state.changeCurrentChannel(channel, 1)
        assertEquals(VideoPlayerDisplayMode.FOUR_THREE, player.displayMode)
        state.changeCurrentChannel(channel, 0, EpgProgramme(title = "回看", startAt = 1_700_000_000_000, endAt = 1_700_000_600_000))
        assertEquals(VideoPlayerDisplayMode.FOUR_THREE, player.displayMode)
        state.changeCurrentChannel(channel, 0)
        assertEquals(VideoPlayerDisplayMode.FOUR_THREE, player.displayMode)
    }

    @Test fun restartRestoresMemoryAndFollowGlobalClearsTheOverride() = isolated { scope ->
        val settings = SettingsViewModel()
        val (initial, _) = state(scope, settings, listOf(channel))
        initial.changeDisplayMode(VideoPlayerDisplayMode.FILL)
        val restored = SettingsViewModel()
        val (state, player) = state(scope, restored, listOf(channel))
        assertEquals(VideoPlayerDisplayMode.FILL, player.displayMode)
        restored.videoPlayerDisplayMode = VideoPlayerDisplayMode.WIDE
        state.followGlobalDisplayMode()
        assertEquals(VideoPlayerDisplayMode.WIDE, player.displayMode)
        assertTrue(Configs.videoPlayerChannelDisplayModes.value.isEmpty())
        val backup = Configs.toPartial()
        assertNotNull(backup.videoPlayerChannelDisplayModes)
        assertNull(backup.desensitized().videoPlayerChannelDisplayModes)
    }

    @Test fun reusedStreamInAnotherGroupDoesNotInheritThePreviousMode() = isolated { scope ->
        val duplicate = channel.copy(groupName = "央视", index = 1)
        val (state, player) = state(scope, SettingsViewModel(), listOf(channel, duplicate))
        state.changeDisplayMode(VideoPlayerDisplayMode.FOUR_THREE)
        state.changeCurrentChannel(duplicate, 0)
        assertEquals(VideoPlayerDisplayMode.ORIGINAL, player.displayMode)
        state.changeDisplayMode(VideoPlayerDisplayMode.SIXTEEN_NINE)
        state.changeCurrentChannel(channel, 0)
        assertEquals(VideoPlayerDisplayMode.FOUR_THREE, player.displayMode)
    }

    private class IdlePlayer(scope: CoroutineScope) : VideoPlayer(scope) {
        override fun prepare(line: ChannelLine) {}
        override fun play() {}
        override fun pause() {}
        override fun seekTo(position: Long) {}
        override fun setVolume(volume: Float) {}
        override fun getVolume() = 1f
        override fun selectVideoTrack(track: Metadata.Video?) {}
        override fun selectAudioTrack(track: Metadata.Audio?) {}
        override fun selectSubtitleTrack(track: Metadata.Subtitle?) {}
        override fun setVideoSurfaceView(surfaceView: SurfaceView) {}
        override fun setVideoTextureView(textureView: TextureView) {}
    }
}
