package top.yogiczy.mytv.tv.player

import android.util.Log
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import top.yogiczy.mytv.core.data.entities.channel.*
import top.yogiczy.mytv.core.data.entities.epg.EpgProgramme
import top.yogiczy.mytv.core.data.entities.epg.Epg
import top.yogiczy.mytv.core.data.entities.epg.EpgList
import top.yogiczy.mytv.core.data.entities.epg.EpgProgrammeList
import top.yogiczy.mytv.core.data.media.PlaybackRange
import top.yogiczy.mytv.core.data.utils.ChannelPlayback
import top.yogiczy.mytv.core.data.utils.SP
import top.yogiczy.mytv.tv.ui.screen.settings.SettingsViewModel
import top.yogiczy.mytv.tv.ui.screensold.main.components.MainContentState
import top.yogiczy.mytv.tv.ui.screensold.videoplayer.VideoPlayerState
import top.yogiczy.mytv.tv.ui.screensold.videoplayer.player.Media3VideoPlayer
import top.yogiczy.mytv.tv.ui.screensold.videoplayer.player.VideoPlayer
import top.yogiczy.mytv.tv.ui.utils.Configs
import java.util.Collections
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Optional bounded network test, using test-package preferences and no saved user channels. */
@RunWith(AndroidJUnit4::class)
@OptIn(UnstableApi::class)
class PlaybackReloadSourceTest {
    @Test fun realFiniteWindowsContinueWithoutManualSeekingAndUpdateProgramme() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val source = InstrumentationRegistry.getArguments().getString("reloadLiveUrl")
        assumeTrue("Pass reloadLiveUrl", !source.isNullOrBlank())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val start = (System.currentTimeMillis() - 30_000) / 1_000 * 1_000
        val first = EpgProgramme(start, start + 10_000, "第一档")
        val next = EpgProgramme(start + 10_000, start + 3_600_000, "后续节目")
        val line = ChannelLine(requireNotNull(source))
        val channel = Channel(name = "连续时移测试", groupName = "临时测试", index = 0,
            lineList = ChannelLineList(listOf(line)))
        val ready = CountDownLatch(1)
        val error = AtomicReference<VideoPlayer.PlaybackException>()
        val position = AtomicLong(start)
        val firstEnd = AtomicLong(Long.MIN_VALUE)
        val requestStarts = Collections.synchronizedSet(mutableSetOf<String>())
        val prefs = SP.getInstance(instrumentation.context)
        lateinit var player: VideoPlayerState
        lateinit var state: MainContentState
        var initialized = false
        fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
        try {
            main {
                prefs.edit().clear().commit()
                SP.init(instrumentation.context)
                val engine = Media3VideoPlayer(instrumentation.targetContext, scope)
                player = VideoPlayerState(engine)
                initialized = true
                player.initialize()
                val field = Media3VideoPlayer::class.java.getDeclaredField("videoPlayer").apply { isAccessible = true }
                (field.get(engine) as ExoPlayer).addAnalyticsListener(object : AnalyticsListener {
                    override fun onLoadStarted(eventTime: AnalyticsListener.EventTime, loadEventInfo: LoadEventInfo,
                        mediaLoadData: MediaLoadData) {
                        if (mediaLoadData.dataType == C.DATA_TYPE_MANIFEST)
                            Uri.parse(loadEventInfo.uri.toString()).getQueryParameter("playseek")
                                ?.substringBefore('-')?.let { requestStarts.add(it) }
                    }
                })
                engine.setVolume(0f)
                engine.onReady { ready.countDown() }
                engine.onCurrentPositionChanged { position.set(it) }
                engine.onPlaybackWindowEnded { firstEnd.compareAndSet(Long.MIN_VALUE, it) }
                engine.onError { if (it != null) { error.set(it); ready.countDown() } }
                val groups = ChannelGroupList(listOf(ChannelGroup(channel.groupName, ChannelList(listOf(channel)))))
                val guide = EpgList(listOf(Epg(listOf(channel.name), programmeList = EpgProgrammeList(listOf(first, next)))))
                state = MainContentState(scope, player, { groups }, settingsViewModel = SettingsViewModel(), epgListProvider = { guide })
                state.changeCurrentChannel(channel, 0, first)
            }
            assertTrue("Initial near-live recording timed out", ready.await(30, TimeUnit.SECONDS))
            assertNull(error.get())
            repeat(14) { step ->
                Thread.sleep(5_000)
                assertNull("Continuous recording failed at ${(step + 1) * 5}s", error.get())
                Log.i("PlaybackContinuationTest", "seconds=${(step + 1) * 5}, offset=${position.get() - start}, windows=${requestStarts.size}")
            }
            assertTrue("The source never completed its finite window", firstEnd.get() != Long.MIN_VALUE)
            assertTrue("No automatic request for the next window", requestStarts.size >= 2)
            assertTrue("Playback did not progress beyond the first window", position.get() >= firstEnd.get() + 5_000)
            main {
                assertEquals(next, state.currentPlaybackEpgProgramme)
                assertEquals(line, state.currentChannelLine)
                assertTrue(player.playRequested)
            }
        } finally {
            main {
                if (initialized) player.release()
                scope.cancel()
                prefs.edit().clear().commit()
                SP.init(instrumentation.targetContext)
            }
        }
    }

    @Test fun realSourceReloadsForwardAndBackwardUsingNewPlayseek() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val source = InstrumentationRegistry.getArguments().getString("reloadLiveUrl")
        assumeTrue("Pass reloadLiveUrl", !source.isNullOrBlank())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val start = (System.currentTimeMillis() - 7_200_000) / 60_000 * 60_000
        val programme = EpgProgramme(start, start + 3_600_000, "重载定位测试")
        val line = ChannelLine(requireNotNull(source))
        val channel = Channel(name = "重载定位测试", groupName = "临时测试", index = 0,
            lineList = ChannelLineList(listOf(line)))
        val ready = AtomicReference(CountDownLatch(1))
        val position = AtomicLong()
        val error = AtomicReference<VideoPlayer.PlaybackException>()
        val requests = Collections.synchronizedList(mutableListOf<String>())
        val prefs = SP.getInstance(instrumentation.context)
        lateinit var engine: Media3VideoPlayer
        lateinit var state: MainContentState
        lateinit var player: VideoPlayerState
        lateinit var settings: SettingsViewModel
        var initialized = false
        fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
        try {
            main {
                prefs.edit().clear().commit()
                SP.init(instrumentation.context)
                Configs.videoPlayerPlaybackReload = true
                engine = Media3VideoPlayer(instrumentation.targetContext, scope)
                player = VideoPlayerState(engine)
                initialized = true
                player.initialize()
                val field = Media3VideoPlayer::class.java.getDeclaredField("videoPlayer").apply { isAccessible = true }
                (field.get(engine) as ExoPlayer).addAnalyticsListener(object : AnalyticsListener {
                    override fun onLoadStarted(eventTime: AnalyticsListener.EventTime, loadEventInfo: LoadEventInfo,
                        mediaLoadData: MediaLoadData) {
                        if (mediaLoadData.dataType == C.DATA_TYPE_MANIFEST) requests.add(loadEventInfo.uri.toString())
                    }
                })
                engine.setVolume(0f)
                engine.onReady { ready.get().countDown() }
                engine.onCurrentPositionChanged { position.set(it) }
                engine.onError { if (it != null) { error.set(it); ready.get().countDown() } }
                val groups = ChannelGroupList(listOf(ChannelGroup(channel.groupName, ChannelList(listOf(channel)))))
                settings = SettingsViewModel()
                state = MainContentState(scope, player, { groups }, settingsViewModel = settings)
                state.changeCurrentChannel(channel, 0, programme)
            }
            assertTrue("Initial replay timed out", ready.get().await(30, TimeUnit.SECONDS))
            assertNull("Initial replay failed", error.get())
            Thread.sleep(1_500)
            for (offset in listOf(600_000L, 300_000L, 60_000L)) {
                val target = start + offset
                val issuedAt = System.currentTimeMillis()
                val expectedUrl = ChannelPlayback.reloadRequest(line, PlaybackRange(start, programme.endAt), target, issuedAt)!!.line.url
                val expectedStart = Uri.parse(expectedUrl).getQueryParameter("playseek")!!.substringBefore('-')
                ready.set(CountDownLatch(1))
                main {
                    if (offset == 60_000L) {
                        settings.videoPlayerPlaybackReload = false
                        assertFalse("Target should be outside the reloaded manifest window", engine.canSeekTo(target))
                    }
                    state.seekTo(target)
                }
                assertTrue("Reload timed out at $offset", ready.get().await(30, TimeUnit.SECONDS))
                assertNull("Reload failed at $offset", error.get())
                Thread.sleep(2_000)
                val actual = synchronized(requests) {
                    requests.firstOrNull { Uri.parse(it).getQueryParameter("playseek")?.substringBefore('-') == expectedStart }
                }
                assertNotNull("No new manifest request for the requested position", actual)
                val actualEnd = Uri.parse(actual!!).getQueryParameter("playseek")!!.substringAfter('-')
                val requestEnd = SimpleDateFormat("yyyyMMddHHmmss", Locale.ROOT).parse(actualEnd)!!.time
                assertTrue("Request must end at invocation time instead of EPG end", requestEnd in issuedAt - 1_000..System.currentTimeMillis())
                assertTrue("Playback clock is not at the requested time: ${position.get() - target}",
                    position.get() in target - 5_000..target + 15_000)
                main {
                    assertEquals(programme, state.currentPlaybackEpgProgramme)
                    assertEquals(line, state.currentChannelLine)
                }
                Log.i("PlaybackReloadTest", "requestedOffset=$offset, actualOffset=${position.get() - start}, manifestConfirmed=true")
            }
            val previous = position.get()
            Thread.sleep(10_000)
            assertNull(error.get())
            assertTrue("Playback stopped after URL reload", position.get() - previous >= 7_000)
        } finally {
            main {
                if (initialized) player.release()
                scope.cancel()
                prefs.edit().clear().commit()
                SP.init(instrumentation.targetContext)
            }
        }
    }
}
