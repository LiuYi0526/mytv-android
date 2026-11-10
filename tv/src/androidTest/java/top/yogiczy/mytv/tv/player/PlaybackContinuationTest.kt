package top.yogiczy.mytv.tv.player

import android.view.SurfaceView
import android.view.TextureView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import top.yogiczy.mytv.core.data.entities.channel.*
import top.yogiczy.mytv.core.data.entities.epg.*
import top.yogiczy.mytv.core.data.media.PlaybackRange
import top.yogiczy.mytv.core.data.utils.SP
import top.yogiczy.mytv.tv.ui.screen.settings.SettingsViewModel
import top.yogiczy.mytv.tv.ui.screensold.main.components.MainContentState
import top.yogiczy.mytv.tv.ui.screensold.videoplayer.VideoPlayerState
import top.yogiczy.mytv.tv.ui.screensold.videoplayer.player.VideoPlayer
import java.util.concurrent.atomic.AtomicLong

@RunWith(AndroidJUnit4::class)
class PlaybackContinuationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private val start = 1_793_271_600_000L
    private val first = EpgProgramme(start, start + 30_000, "第一档节目")
    private val second = EpgProgramme(start + 30_000, start + 90_000, "第二档节目")
    private val channel = Channel(name = "测试频道", groupName = "测试", index = 0,
        lineList = ChannelLineList(listOf(ChannelLine("https://example.test/PLTV/1.m3u8", httpUserAgent = "custom-UA"))))

    private class Fixture(val scope: CoroutineScope, val engine: RecordingPlayer, val player: VideoPlayerState,
        val state: MainContentState, val clock: AtomicLong)

    private fun isolated(withGuide: Boolean = true, selectProgramme: Boolean = true, block: (Fixture) -> Unit) {
        lateinit var fixture: Fixture
        val prefs = SP.getInstance(instrumentation.context)
        main {
            prefs.edit().clear().commit()
            SP.init(instrumentation.context)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val engine = RecordingPlayer(scope)
            val player = VideoPlayerState(engine).also { it.initialize() }
            val groups = ChannelGroupList(listOf(ChannelGroup("测试", ChannelList(listOf(channel)))))
            val guide = if (withGuide) EpgList(listOf(Epg(listOf(channel.name),
                programmeList = EpgProgrammeList(listOf(first, second))))) else EpgList()
            val clock = AtomicLong(start + 120_000)
            val state = MainContentState(scope, player, { groups }, settingsViewModel = SettingsViewModel(),
                epgListProvider = { guide }, nowProvider = clock::get)
            if (selectProgramme) state.changeCurrentChannel(channel, 0, first)
            fixture = Fixture(scope, engine, player, state, clock)
        }
        try { block(fixture) }
        finally {
            main {
                fixture.player.release()
                fixture.scope.cancel()
                prefs.edit().clear().commit()
                SP.init(instrumentation.targetContext)
            }
        }
    }

    private fun awaitPrepare(f: Fixture, count: Int) {
        val deadline = System.currentTimeMillis() + 3_000
        var ready = false
        while (!ready && System.currentTimeMillis() < deadline) {
            main { ready = f.engine.prepares >= count }
            if (!ready) Thread.sleep(20)
        }
        assertTrue("Continuation did not prepare a new window", ready)
    }

    @Test fun programmeChangesInsideTheExistingWindowWithoutReloading() = isolated { f ->
        main {
            val count = f.engine.prepares
            f.engine.position(start + 45_000)
            assertEquals(second, f.state.currentPlaybackEpgProgramme)
            assertEquals(count, f.engine.prepares)
            assertTrue(f.engine.range!!.continuous)
        }
    }

    @Test fun finiteWindowsContinueFromActualEndAndCanCrossProgrammes() = isolated { f ->
        val initial = f.engine.prepares
        main { f.engine.end(start + 25_000) }
        awaitPrepare(f, initial + 1)
        main {
            assertEquals(start + 25_000, f.engine.range!!.sourceStartAt)
            assertEquals(first, f.state.currentPlaybackEpgProgramme)
            f.engine.end(start + 45_000)
        }
        awaitPrepare(f, initial + 2)
        main {
            assertEquals(second, f.state.currentPlaybackEpgProgramme)
            assertEquals(start + 45_000, f.engine.range!!.sourceStartAt)
            assertEquals(channel.lineList[0], f.state.currentChannelLine)
            assertEquals("custom-UA", f.engine.line.httpUserAgent)
            assertTrue(f.player.playRequested)
        }
    }

    @Test fun pauseAtWindowEndWaitsForExplicitPlayAndDoesNotRestartTheOldWindow() = isolated { f ->
        val count = f.engine.prepares
        main { f.player.pause(); f.engine.end(start + 20_000) }
        Thread.sleep(150)
        main {
            assertEquals(count, f.engine.prepares)
            f.player.play()
        }
        awaitPrepare(f, count + 1)
        main {
            assertEquals(start + 20_000, f.engine.range!!.sourceStartAt)
            assertEquals(0, f.engine.playCalls)
        }
    }

    @Test fun nearLiveWaitIsCancelledByChannelChange() = isolated { f ->
        main {
            f.engine.end(f.clock.get() - 1_000)
            f.state.changeCurrentChannel(channel, 0)
            f.clock.addAndGet(20_000)
        }
        val count = f.engine.prepares
        Thread.sleep(1_200)
        main { assertEquals(count, f.engine.prepares); assertNull(f.engine.range) }
    }

    @Test fun pauseAndResumeDuringNearLiveWaitKeepsThePendingTime() = isolated { f ->
        val count = f.engine.prepares
        val end = f.clock.get() - 1_000
        main { f.engine.end(end); f.player.pause(); f.clock.addAndGet(20_000) }
        Thread.sleep(1_200)
        main { assertEquals(count, f.engine.prepares); f.player.play() }
        awaitPrepare(f, count + 1)
        main { assertEquals(end, f.engine.range!!.sourceStartAt) }
    }

    @Test fun repeatedNonAdvancingWindowsPauseInsteadOfReloadingForever() = isolated { f ->
        val initial = f.engine.prepares
        repeat(3) { index ->
            main { f.engine.end(start + 20_000) }
            awaitPrepare(f, initial + index + 1)
        }
        main { f.engine.end(start + 20_000); assertFalse(f.player.playRequested) }
        Thread.sleep(100)
        main { assertEquals(initial + 3, f.engine.prepares) }
    }

    @Test fun timeshiftWithoutGuideStillContinuesOnTheSameChannel() = isolated(withGuide = false, selectProgramme = false) { f ->
        main { f.state.seekTo(start + 30_000) }
        val count = f.engine.prepares
        main {
            assertEquals("时移播放", f.state.currentPlaybackEpgProgramme!!.title)
            f.engine.end(start + 60_000)
        }
        awaitPrepare(f, count + 1)
        main { assertEquals(start + 60_000, f.engine.range!!.sourceStartAt) }
    }

    private class RecordingPlayer(scope: CoroutineScope) : VideoPlayer(scope) {
        var line = ChannelLine()
        var range: PlaybackRange? = null
        var prepares = 0
        var playCalls = 0
        fun position(at: Long) = triggerCurrentPosition(at)
        fun end(at: Long) = triggerPlaybackWindowEnded(at)
        override val positionIsAbsolute = true
        override fun prepare(line: ChannelLine) { this.line = line; prepares++ }
        override fun prepare(line: ChannelLine, playbackRange: PlaybackRange?) { prepare(line); range = playbackRange }
        override fun play() { playCalls++ }
        override fun pause() {}
        override fun seekTo(position: Long) { triggerCurrentPosition(position) }
        override fun setVolume(volume: Float) {}
        override fun getVolume() = 1f
        override fun selectVideoTrack(track: Metadata.Video?) {}
        override fun selectAudioTrack(track: Metadata.Audio?) {}
        override fun selectSubtitleTrack(track: Metadata.Subtitle?) {}
        override fun setVideoSurfaceView(surfaceView: SurfaceView) {}
        override fun setVideoTextureView(textureView: TextureView) {}
    }
}
