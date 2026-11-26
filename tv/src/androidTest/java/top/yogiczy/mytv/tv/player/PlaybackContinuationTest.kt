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

    private fun isolated(
        withGuide: Boolean = true,
        selectProgramme: Boolean = true,
        programmes: List<EpgProgramme> = listOf(first, second),
        initialProgramme: EpgProgramme = first,
        clockAt: Long = start + 120_000,
        block: (Fixture) -> Unit,
    ) {
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
                programmeList = EpgProgrammeList(programmes)))) else EpgList()
            val clock = AtomicLong(clockAt)
            val state = MainContentState(scope, player, { groups }, settingsViewModel = SettingsViewModel(),
                epgListProvider = { guide }, nowProvider = clock::get)
            if (selectProgramme) state.changeCurrentChannel(channel, 0, initialProgramme)
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

    @Test fun rewindingFromProgrammeStartReloadsAtTheEndOfThePreviousProgramme() =
        isolated(initialProgramme = second) { f ->
            main {
                f.engine.loadedDurationMs = 90_000
                val count = f.engine.prepares
                f.player.pause()
                val target = second.startAt - 10_000
                f.state.seekTo(target)
                assertEquals(count + 1, f.engine.prepares)
                assertNull(f.engine.lastSeek)
                assertEquals(first, f.state.currentPlaybackEpgProgramme)
                assertEquals(target, f.engine.range!!.sourceStartAt)
                assertFalse(f.player.playRequested)
            }
        }

    @Test fun forwardAcrossProgrammesReusesTheWindowWhenItContainsTheTarget() = isolated { f ->
        main {
            f.engine.loadedDurationMs = 60_000
            val count = f.engine.prepares
            val target = first.endAt + 5_000
            f.state.seekTo(target)
            assertEquals(count, f.engine.prepares)
            assertEquals(target, f.engine.lastSeek)
            assertEquals(second, f.state.currentPlaybackEpgProgramme)
        }
    }

    @Test fun forwardAcrossProgrammesReloadsAtTheExactTargetOutsideTheWindow() = isolated { f ->
        main {
            f.engine.loadedDurationMs = first.endAt - first.startAt
            val count = f.engine.prepares
            val target = first.endAt + 5_000
            f.state.seekTo(target)
            assertEquals(count + 1, f.engine.prepares)
            assertNull(f.engine.lastSeek)
            assertEquals(second, f.state.currentPlaybackEpgProgramme)
            assertEquals(target, f.engine.range!!.sourceStartAt)
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
            assertEquals("无信息", f.state.currentPlaybackEpgProgramme!!.title)
            f.engine.end(start + 60_000)
        }
        awaitPrepare(f, count + 1)
        main { assertEquals(start + 60_000, f.engine.range!!.sourceStartAt) }
    }

    @Test fun restartOfSameProgrammeActuallySeeksInsteadOfReusingCurrentPosition() = isolated { f ->
        main {
            f.engine.position(first.startAt + 10_000)
            val count = f.engine.prepares
            f.state.playProgrammeFromStart(first)
            assertTrue(f.engine.lastSeek == first.startAt || f.engine.prepares > count)
            assertEquals(first, f.state.currentPlaybackEpgProgramme)
        }
    }

    @Test fun selectedNeighbourStartsAtItsOwnTimeWithOriginalLineMetadata() = isolated { f ->
        main {
            f.state.playProgrammeFromStart(second)
            assertEquals(second, f.state.currentPlaybackEpgProgramme)
            assertEquals(second.startAt, f.engine.range!!.sourceStartAt)
            assertEquals("custom-UA", f.engine.line.httpUserAgent)
        }
    }

    @Test fun futureProgrammeDoesNotPrepareOrSeek() = isolated { f ->
        main {
            val count = f.engine.prepares
            f.state.playProgrammeFromStart(EpgProgramme(f.clock.get() + 60_000, f.clock.get() + 120_000, "未开播"))
            assertEquals(count, f.engine.prepares)
            assertNull(f.engine.lastSeek)
            assertEquals(first, f.state.currentPlaybackEpgProgramme)
        }
    }

    @Test fun previousProgrammeCanReloadAcrossMidnightWithActualDatesInUrl() {
        val clockFormat = java.text.SimpleDateFormat("yyyyMMddHHmmss", java.util.Locale.ROOT)
        val midnight = clockFormat.parse("20261111000000")!!.time
        val yesterday = EpgProgramme(midnight - 3_600_000, midnight, "同名节目")
        val today = EpgProgramme(midnight, midnight + 3_600_000, "同名节目")
        isolated(programmes = listOf(yesterday, today), initialProgramme = today,
            clockAt = midnight + 1_800_000) { f ->
            main {
                val target = top.yogiczy.mytv.core.data.media.PlaybackProgrammeNavigation
                    .adjacent(listOf(yesterday, today), f.state.currentPlaybackEpgProgramme, -1)!!
                f.state.playProgrammeFromStart(target)
                assertEquals(yesterday, f.state.currentPlaybackEpgProgramme)
                assertEquals(yesterday.startAt, f.engine.range!!.sourceStartAt)
                assertTrue(f.engine.line.url.contains("playseek=20261110230000-20261111003000"))
            }
        }
    }

    private class RecordingPlayer(scope: CoroutineScope) : VideoPlayer(scope) {
        var line = ChannelLine()
        var range: PlaybackRange? = null
        var prepares = 0
        var playCalls = 0
        var lastSeek: Long? = null
        var loadedDurationMs: Long? = null
        fun position(at: Long) = triggerCurrentPosition(at)
        fun end(at: Long) = triggerPlaybackWindowEnded(at)
        override val positionIsAbsolute = true
        override fun canSeekTo(position: Long): Boolean {
            val duration = loadedDurationMs ?: return true
            return range?.windowPosition(position, null, duration) != null
        }
        override fun prepare(line: ChannelLine) { this.line = line; prepares++ }
        override fun prepare(line: ChannelLine, playbackRange: PlaybackRange?) { prepare(line); range = playbackRange }
        override fun play() { playCalls++ }
        override fun pause() {}
        override fun seekTo(position: Long) { lastSeek = position; triggerCurrentPosition(position) }
        override fun setVolume(volume: Float) {}
        override fun getVolume() = 1f
        override fun selectVideoTrack(track: Metadata.Video?) {}
        override fun selectAudioTrack(track: Metadata.Audio?) {}
        override fun selectSubtitleTrack(track: Metadata.Subtitle?) {}
        override fun setVideoSurfaceView(surfaceView: SurfaceView) {}
        override fun setVideoTextureView(textureView: TextureView) {}
    }
}
