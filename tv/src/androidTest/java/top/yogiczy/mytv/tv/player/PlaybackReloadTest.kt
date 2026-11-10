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
import top.yogiczy.mytv.core.data.media.PlaybackRange
import top.yogiczy.mytv.core.data.utils.SP
import top.yogiczy.mytv.tv.ui.screen.settings.SettingsViewModel
import top.yogiczy.mytv.tv.ui.screensold.main.components.MainContentState
import top.yogiczy.mytv.tv.ui.screensold.videoplayer.VideoPlayerDisplayMode
import top.yogiczy.mytv.tv.ui.screensold.videoplayer.VideoPlayerState
import top.yogiczy.mytv.tv.ui.screensold.videoplayer.player.VideoPlayer
import top.yogiczy.mytv.tv.ui.utils.Configs
import java.text.SimpleDateFormat
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class PlaybackReloadTest {
    private val start = 1_793_271_600_000L
    private val programme = EpgProgramme(start, start + 3_600_000, "测试回看")
    private val line = ChannelLine("https://example.test/PLTV/1.m3u8?token=a%2Bb==",
        httpUserAgent = "custom-UA", manifestType = "hls", licenseType = "clearkey", licenseKey = "key")
    private val channel = Channel(name = "测试频道", groupName = "测试", index = 0,
        lineList = ChannelLineList(listOf(line)))

    private fun isolated(block: (MainContentState, VideoPlayerState, RecordingPlayer, SettingsViewModel) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val prefs = SP.getInstance(instrumentation.context)
            prefs.edit().clear().commit()
            SP.init(instrumentation.context)
            try {
                val settings = SettingsViewModel()
                val engine = RecordingPlayer(scope)
                val player = VideoPlayerState(engine)
                val groups = ChannelGroupList(listOf(ChannelGroup("测试", ChannelList(listOf(channel)))))
                val state = MainContentState(scope, player, { groups }, settingsViewModel = settings)
                player.isPlaying = true
                block(state, player, engine, settings)
            } finally {
                scope.cancel()
                prefs.edit().clear().commit()
                SP.init(instrumentation.targetContext)
            }
        }
    }

    @Test fun automaticModeKeepsNativeSeekInsideTheLoadedWindow() = isolated { state, _, engine, _ ->
        state.changeCurrentChannel(channel, 0, programme)
        val prepares = engine.prepares
        state.seekTo(start + 600_000)
        assertEquals(start + 600_000, engine.seek)
        assertEquals(prepares, engine.prepares)
    }

    @Test fun initialReplayRequestsUntilNowWhileKeepingTheSelectedProgrammeEnd() = isolated { state, _, engine, _ ->
        val before = System.currentTimeMillis()
        state.changeCurrentChannel(channel, 0, programme)
        val encodedEnd = engine.line.url.substringAfter("playseek=").substringAfter('-')
        val requestEnd = SimpleDateFormat("yyyyMMddHHmmss", Locale.ROOT).parse(encodedEnd)!!.time
        assertTrue(requestEnd in before - 1_000..System.currentTimeMillis())
        assertEquals(programme.endAt, engine.range!!.endAt)
    }

    @Test fun outsideWindowReloadsFromOriginalLineAndPreservesProgramme() = isolated { state, player, engine, _ ->
        state.changeCurrentChannel(channel, 0, programme)
        engine.seekable = false
        state.seekTo(start + 600_000)
        assertNull(engine.seek)
        assertEquals(start, engine.range!!.startAt)
        assertEquals(programme.endAt, engine.range!!.endAt)
        assertEquals(start + 600_000, engine.range!!.sourceStartAt)
        assertEquals(start + 600_000, player.currentPosition)
        assertEquals(programme, state.currentPlaybackEpgProgramme)
        assertEquals(line, state.currentChannelLine)
        assertEquals(line.copy(url = engine.line.url), engine.line)
        assertTrue(engine.line.url.contains("/TVOD/"))
        assertTrue(engine.line.url.contains("playseek="))
    }

    @Test fun preferredReloadKeepsPauseDisplayModeAndCanMoveBackAgain() = isolated { state, player, engine, settings ->
        settings.videoPlayerPlaybackReload = true
        state.changeCurrentChannel(channel, 0, programme)
        player.displayMode = VideoPlayerDisplayMode.FOUR_THREE
        player.isPlaying = false
        player.pause()
        engine.pauses = 0
        state.seekTo(start + 1_200_000)
        assertEquals(1, engine.pauses)
        assertEquals(VideoPlayerDisplayMode.FOUR_THREE, player.displayMode)
        state.seekTo(start + 300_000)
        assertEquals(start + 300_000, engine.range!!.sourceStartAt)
        assertEquals(1, Regex("playseek=").findAll(engine.line.url).count())
        assertEquals(start, engine.range!!.startAt)
        assertNull(engine.seek)
    }

    @Test fun leavingLiveWindowStartsReplayUsingCurrentProgramme() = isolated { state, _, engine, _ ->
        engine.seekable = false
        state.seekTo(start + 600_000, programme)
        assertEquals(programme, state.currentPlaybackEpgProgramme)
        assertEquals(start + 600_000, engine.range!!.sourceStartAt)
    }

    @Test fun programmeBoundaryCanBeReloadedForContinuousPlayback() = isolated { state, _, engine, settings ->
        settings.videoPlayerPlaybackReload = true
        state.changeCurrentChannel(channel, 0, programme)
        val prepares = engine.prepares
        state.seekTo(programme.endAt)
        assertNull(engine.seek)
        assertEquals(prepares + 1, engine.prepares)
        assertTrue(engine.range!!.continuous)
        assertEquals(programme.endAt, engine.range!!.sourceStartAt)
    }

    @Test fun reloadPreferencePersistsAndRoundTripsThroughPartialConfig() = isolated { _, _, _, settings ->
        assertFalse(settings.videoPlayerPlaybackReload)
        settings.videoPlayerPlaybackReload = true
        assertTrue(SettingsViewModel().videoPlayerPlaybackReload)
        val partial = Configs.toPartial()
        Configs.videoPlayerPlaybackReload = false
        Configs.fromPartial(partial)
        assertTrue(Configs.videoPlayerPlaybackReload)
    }

    @Test fun epgClockOnRelativeLiveReloadsTemplateWithoutNativeSeek() = isolated { state, player, engine, _ ->
        val explicitLine = line.copy(url = "https://example.test/live", catchup = "append",
            catchupSource = "?startTime={b:10}&stopTime={e:10}", pltv2tvod = false)
        state.changeCurrentChannel(channel.copy(lineList = ChannelLineList(listOf(explicitLine))))
        engine.absolute = false
        player.pause()
        player.displayMode = VideoPlayerDisplayMode.FOUR_THREE
        state.seekTo(start + 120_000, programme, absoluteTarget = true)
        assertNull(engine.seek)
        assertEquals(start + 120_000, engine.range!!.sourceStartAt)
        assertEquals(programme, state.currentPlaybackEpgProgramme)
        assertTrue(engine.line.url.contains("startTime=${(start + 120_000) / 1_000}"))
        assertFalse(engine.line.url.contains("playseek="))
        assertEquals(explicitLine.copy(url = engine.line.url), engine.line)
        assertFalse(player.playRequested)
        assertEquals(VideoPlayerDisplayMode.FOUR_THREE, player.displayMode)
    }

    @Test fun unsupportedEpgClockNeverFallsBackToRelativeNativeSeek() = isolated { state, _, engine, _ ->
        state.changeCurrentChannel(channel.copy(lineList = ChannelLineList(listOf(line.copy(catchup = "none")))))
        engine.absolute = false
        val prepares = engine.prepares
        state.seekTo(start + 120_000, programme, absoluteTarget = true)
        assertNull(engine.seek)
        assertEquals(prepares, engine.prepares)
        assertNull(state.currentPlaybackEpgProgramme)
    }

    @Test fun relativeMediaSeekStillUsesRelativeMilliseconds() = isolated { state, _, engine, _ ->
        engine.absolute = false
        val prepares = engine.prepares
        state.seekTo(120_000)
        assertEquals(120_000L, engine.seek)
        assertEquals(prepares, engine.prepares)
    }

    private class RecordingPlayer(scope: CoroutineScope) : VideoPlayer(scope) {
        var line = ChannelLine()
        var range: PlaybackRange? = null
        var seek: Long? = null
        var seekable = true
        var prepares = 0
        var pauses = 0
        var absolute = true
        override val positionIsAbsolute get() = absolute
        override fun canSeekTo(position: Long) = seekable
        override fun prepare(line: ChannelLine) { this.line = line; prepares++ }
        override fun prepare(line: ChannelLine, playbackRange: PlaybackRange?) {
            prepare(line); range = playbackRange
        }
        override fun play() {}
        override fun pause() { pauses++ }
        override fun seekTo(position: Long) { seek = position }
        override fun setVolume(volume: Float) {}
        override fun getVolume() = 1f
        override fun selectVideoTrack(track: Metadata.Video?) {}
        override fun selectAudioTrack(track: Metadata.Audio?) {}
        override fun selectSubtitleTrack(track: Metadata.Subtitle?) {}
        override fun setVideoSurfaceView(surfaceView: SurfaceView) {}
        override fun setVideoTextureView(textureView: TextureView) {}
    }
}
