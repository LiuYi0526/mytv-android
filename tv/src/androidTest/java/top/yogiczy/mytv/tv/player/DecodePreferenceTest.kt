package top.yogiczy.mytv.tv.player

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.view.SurfaceView
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import top.yogiczy.mytv.core.data.entities.channel.ChannelLine
import top.yogiczy.mytv.tv.ui.screensold.quickop.components.DecodePreferenceButton
import top.yogiczy.mytv.tv.ui.screensold.videoplayer.VideoPlayerDisplayMode
import top.yogiczy.mytv.tv.ui.screensold.videoplayer.VideoPlayerState
import top.yogiczy.mytv.tv.ui.screensold.videoplayer.player.IjkVideoPlayer
import top.yogiczy.mytv.tv.ui.screensold.videoplayer.player.Media3VideoPlayer
import top.yogiczy.mytv.tv.ui.screensold.videoplayer.player.VideoPlayer
import top.yogiczy.mytv.tv.ui.theme.MyTvTheme
import tv.danmaku.ijk.media.player.IjkMediaPlayer
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
@OptIn(UnstableApi::class)
@kotlin.OptIn(ExperimentalTestApi::class)
class DecodePreferenceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun touchAndRemoteEachToggleThePreferenceOnce() {
        var changes = 0
        compose.setContent {
            var preferred by remember { mutableStateOf(false) }
            val focus = remember { FocusRequester() }
            MyTvTheme {
                DecodePreferenceButton(preferred, {
                    preferred = !preferred
                    changes++
                }, Modifier.focusRequester(focus))
            }
            LaunchedEffect(Unit) { focus.requestFocus() }
        }
        compose.onNodeWithText("硬解优先").performTouchInput { click() }
        compose.onNodeWithText("软解优先").assertExists()
        compose.runOnIdle { assertEquals(1, changes) }
        compose.onNodeWithText("软解优先").performKeyInput { pressKey(Key.DirectionCenter) }
        compose.onNodeWithText("硬解优先").assertExists()
        compose.runOnIdle { assertEquals(2, changes) }
    }

    private fun showSurface(): SurfaceView {
        val surface = AtomicReference<SurfaceView>()
        compose.setContent { AndroidView(modifier = Modifier.fillMaxSize(), factory = { SurfaceView(it).also(surface::set) }) }
        compose.waitUntil(5_000) { surface.get()?.holder?.surface?.isValid == true }
        return surface.get()
    }

    @Test fun media3SwitchesRealVideoDecoderAndPreservesPausedPlayback() {
        val software = MediaCodecSelector.DEFAULT.getDecoderInfos("video/avc", false, false).filter { it.softwareOnly }
        assumeTrue("Device needs an H.264 software decoder", software.isNotEmpty())
        val surface = showSurface()
        val sample = createVideo()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val ready = AtomicReference(CountDownLatch(1))
        val failure = AtomicReference<VideoPlayer.PlaybackException>()
        val decoder = AtomicReference<String>()
        lateinit var player: Media3VideoPlayer
        lateinit var state: VideoPlayerState
        var initialized = false
        fun raw(): ExoPlayer = Media3VideoPlayer::class.java.getDeclaredField("videoPlayer")
            .apply { isAccessible = true }.get(player) as ExoPlayer
        try {
            instrumentation.runOnMainSync {
                player = Media3VideoPlayer(instrumentation.targetContext, scope)
                state = VideoPlayerState(player)
                state.initialize()
                initialized = true
                player.onReady { ready.get().countDown() }
                player.onError { if (it != null) { failure.set(it); ready.get().countDown() } }
                player.onMetadata { it.video?.decoder?.let(decoder::set) }
                player.setVideoSurfaceView(surface)
                state.volume = 0.23f
                state.prepare(ChannelLine(Uri.fromFile(sample).toString()))
            }
            assertTrue(ready.get().await(10, TimeUnit.SECONDS))
            assertNull(failure.get())
            instrumentation.runOnMainSync { state.pause(); state.seekTo(4_000); state.displayMode = VideoPlayerDisplayMode.FILL }
            compose.waitUntil(5_000) {
                var settled = false
                instrumentation.runOnMainSync { settled = raw().playbackState == androidx.media3.common.Player.STATE_READY && raw().currentPosition in 3_500..4_500 }
                settled
            }
            // Exercise the real error handler and reload: an audio initialization failure
            // may prefer FFmpeg audio, but must leave H.264 on the normal video decoder path.
            ready.set(CountDownLatch(1))
            instrumentation.runOnMainSync {
                val listener = Media3VideoPlayer::class.java.getDeclaredField("playerListener")
                    .apply { isAccessible = true }.get(player) as androidx.media3.common.Player.Listener
                listener.onPlayerError(ExoPlaybackException.createForRenderer(
                    IllegalStateException("Test audio decoder initialization failure"), "test-audio", 1,
                    androidx.media3.common.Format.Builder().setSampleMimeType("audio/mp4a-latm").build(),
                    androidx.media3.common.C.FORMAT_HANDLED, false,
                    androidx.media3.common.PlaybackException.ERROR_CODE_DECODER_INIT_FAILED))
            }
            assertTrue("Audio fallback reload did not become ready", ready.get().await(10, TimeUnit.SECONDS))
            assertNull(failure.get())
            val afterAudioFallback = MediaCodecSelector.DEFAULT.getDecoderInfos("video/avc", false, false)
                .first { it.name == decoder.get() }
            if (MediaCodecSelector.DEFAULT.getDecoderInfos("video/avc", false, false).any { it.hardwareAccelerated })
                assertTrue("Audio fallback changed video decoder to ${afterAudioFallback.name}", afterAudioFallback.hardwareAccelerated)
            for (preferred in listOf(true, false)) {
                ready.set(CountDownLatch(1))
                instrumentation.runOnMainSync { state.preferSoftwareDecode = preferred }
                assertTrue("Decoder switch did not become ready", ready.get().await(10, TimeUnit.SECONDS))
                assertNull(failure.get())
                val actual = MediaCodecSelector.DEFAULT.getDecoderInfos("video/avc", false, false)
                    .first { it.name == decoder.get() }
                if (preferred) assertTrue("Software preference still chose ${actual.name}", actual.softwareOnly)
                else if (MediaCodecSelector.DEFAULT.getDecoderInfos("video/avc", false, false).any { it.hardwareAccelerated })
                    assertTrue("Hardware preference still chose ${actual.name}", actual.hardwareAccelerated)
                println("Media3 softwarePreferred=$preferred decoder=${actual.name}")
                instrumentation.runOnMainSync {
                    assertFalse(raw().playWhenReady)
                    assertEquals(0.23f, player.getVolume(), 0.001f)
                    assertTrue("Position lost: ${raw().currentPosition}", raw().currentPosition in 3_500..4_500)
                    assertEquals(VideoPlayerDisplayMode.FILL, state.displayMode)
                }
            }
        } finally {
            instrumentation.runOnMainSync { if (initialized) state.release(); scope.cancel() }
            sample.delete()
        }
    }

    @Test fun ijkSwitchesBetweenMediaCodecAndFfmpegWithoutResumingPausedVideo() {
        val surface = showSurface()
        val sample = createVideo()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val ready = AtomicReference(CountDownLatch(1))
        val failure = AtomicReference<VideoPlayer.PlaybackException>()
        lateinit var player: IjkVideoPlayer
        var initialized = false
        fun raw(): IjkMediaPlayer = (IjkVideoPlayer::class.java.getDeclaredField("player\$delegate")
            .apply { isAccessible = true }.get(player) as Lazy<*>).value as IjkMediaPlayer
        try {
            instrumentation.runOnMainSync {
                player = IjkVideoPlayer(instrumentation.targetContext, scope)
                player.initialize()
                initialized = true
                player.onReady { ready.get().countDown() }
                player.onError { if (it != null) { failure.set(it); ready.get().countDown() } }
                player.setVideoSurfaceView(surface)
                player.prepare(ChannelLine(Uri.fromFile(sample).toString()))
            }
            assertTrue(ready.get().await(10, TimeUnit.SECONDS))
            assertNull(failure.get())
            instrumentation.runOnMainSync { player.pause(); player.seekTo(4_000) }
            compose.waitUntil(5_000) {
                var settled = false
                instrumentation.runOnMainSync { settled = raw().currentPosition in 3_000..5_000 }
                settled
            }
            for (preferred in listOf(true, false)) {
                ready.set(CountDownLatch(1))
                instrumentation.runOnMainSync { player.setSoftwareDecodePreferred(preferred) }
                assertTrue("IJK decoder switch did not prepare", ready.get().await(10, TimeUnit.SECONDS))
                assertNull(failure.get())
                instrumentation.runOnMainSync {
                    val expected = if (preferred) IjkMediaPlayer.FFP_PROPV_DECODER_AVCODEC else IjkMediaPlayer.FFP_PROPV_DECODER_MEDIACODEC
                    assertEquals("IJK did not change its video decoder", expected, raw().videoDecoder)
                    assertFalse(raw().isPlaying)
                    println("IJK softwarePreferred=$preferred decoder=${raw().videoDecoder}")
                }
                compose.waitUntil(5_000) {
                    var restored = false
                    instrumentation.runOnMainSync { restored = raw().currentPosition in 3_000..5_000 }
                    restored
                }
            }
        } finally {
            instrumentation.runOnMainSync { if (initialized) player.release(); scope.cancel() }
            sample.delete()
        }
    }

    /** Generate a small seekable video locally; no expiring stream or user subscription is needed. */
    private fun createVideo(): File {
        val file = File.createTempFile("decode-preference-", ".mp4", instrumentation.targetContext.cacheDir)
        val codec = MediaCodec.createEncoderByType("video/avc")
        val muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxerStarted = false
        try {
            val format = MediaFormat.createVideoFormat("video/avc", 160, 96).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                setInteger(MediaFormat.KEY_BIT_RATE, 100_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, 20)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            var frame = 0; var sentEnd = false; var ended = false; var track = -1
            val info = MediaCodec.BufferInfo()
            val deadline = android.os.SystemClock.elapsedRealtime() + 15_000
            while (!ended && android.os.SystemClock.elapsedRealtime() < deadline) {
                if (!sentEnd) {
                    val index = codec.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        if (frame == 240) {
                            codec.queueInputBuffer(index, 0, 0, frame * 50_000L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sentEnd = true
                        } else {
                            val data = ByteArray(160 * 96 * 3 / 2) { if (it < 160 * 96) (32 + frame % 100).toByte() else 128.toByte() }
                            codec.getInputBuffer(index)!!.apply { clear(); put(data) }
                            codec.queueInputBuffer(index, 0, data.size, frame * 50_000L, 0)
                            frame++
                        }
                    }
                }
                val output = codec.dequeueOutputBuffer(info, 10_000)
                if (output == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = muxer.addTrack(codec.outputFormat); muxer.start(); muxerStarted = true
                } else if (output >= 0) {
                    if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        val data = codec.getOutputBuffer(output)!!.apply { position(info.offset); limit(info.offset + info.size) }
                        muxer.writeSampleData(track, data, info)
                    }
                    ended = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    codec.releaseOutputBuffer(output, false)
                }
            }
            check(ended) { "Test video encoder timed out" }
        } finally {
            codec.release()
            if (muxerStarted) muxer.stop()
            muxer.release()
        }
        return file
    }
}
