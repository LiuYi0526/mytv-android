package top.yogiczy.mytv.tv.player

import android.graphics.SurfaceTexture
import android.view.Surface
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
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
import top.yogiczy.mytv.core.data.entities.channel.ChannelLine
import top.yogiczy.mytv.core.data.media.arib.AribCaptionPacket
import top.yogiczy.mytv.tv.ui.screensold.videoplayer.player.Media3VideoPlayer
import top.yogiczy.mytv.tv.ui.screensold.videoplayer.player.arib.AribCaptionController
import top.yogiczy.mytv.tv.ui.screensold.videoplayer.player.arib.AribNativeDecoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicLong

@RunWith(AndroidJUnit4::class)
class AribSubtitleTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)

    @Test fun nativeJisProducesReadableTextAndPositionedPixels() {
        AribNativeDecoder(false, 1).use { decoder ->
            assertFalse(decoder.decode(management(), 1000))
            assertTrue(decoder.decode(statement(), 1000))
            val frame = decoder.render(1000)!!
            assertTrue(frame.text.contains("ARIB") || frame.text.contains("ＡＲＩＢ"))
            assertEquals(1000L, frame.startMs)
            assertTrue(frame.cues.isNotEmpty())
            frame.cues.forEach { cue ->
                assertTrue(cue.position in 0f..1f)
                assertTrue(cue.line in 0f..1f)
                assertTrue(cue.bitmapHeight in 0f..0.4f)
                val bitmap = cue.bitmap!!
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                assertTrue(pixels.count { it ushr 24 > 0 } > 20)
            }
        }
    }

    @Test fun utf8JapaneseAndSuperimposeUseTheSameNativeRenderer() {
        AribNativeDecoder(true, 1).use { decoder ->
            decoder.decode(management(type = 0x81, utf8 = true), 2000)
            val positionedText = byteArrayOf(0x0c, 0x1c, 0x47, 0x45) + "字幕テスト".toByteArray()
            assertTrue(decoder.decode(statement(type = 0x81, text = positionedText), 2000))
            val frame = decoder.render(2000)!!
            assertTrue(frame.text.contains("字幕テスト"))
            assertTrue(frame.cues.isNotEmpty())
        }
    }

    @Test fun broadcastClearErasesPreviousPixels() {
        AribNativeDecoder(false, 1).use { decoder ->
            decoder.decode(management(), 0)
            decoder.decode(statement(), 0)
            assertTrue(decoder.render(0)!!.cues.isNotEmpty())
            assertTrue(decoder.decode(statement(text = byteArrayOf(0x0c)), 3000))
            assertTrue(decoder.render(3000)!!.cues.isEmpty())
        }
    }

    @Test fun controllerDiscoversOnlyAribAndSupportsSelectionOffAndReset() {
        val shown = CountDownLatch(1)
        val cleared = AtomicInteger()
        lateinit var controller: AribCaptionController
        try {
            main {
                controller = AribCaptionController({ 1000 }, {}, { cues ->
                    if (cues.isNotEmpty()) shown.countDown() else cleared.incrementAndGet()
                })
                controller.consume("ordinary-id3", management())
                assertTrue(controller.tracks.isEmpty())
                controller.consume(AribCaptionPacket.OWNER, management())
                assertEquals(1, controller.tracks.size)
                assertNull(controller.selectedIndex)
                controller.select(controller.tracks.single().index)
                controller.consume(AribCaptionPacket.OWNER, statement())
            }
            assertTrue("Decoded caption was not presented", shown.await(10, TimeUnit.SECONDS))
            main {
                controller.select(null)
                assertNull(controller.selectedIndex)
                assertTrue(cleared.get() >= 2)
                controller.reset()
                assertTrue(controller.tracks.isEmpty())
            }
        } finally { main { controller.close() } }
    }

    @Test fun finiteCaptionsFollowMediaTimeRatherThanWallTime() {
        val clock = AtomicLong(1000)
        val shown = CountDownLatch(1)
        val cleared = CountDownLatch(1)
        val wasShown = AtomicBoolean()
        lateinit var controller: AribCaptionController
        try {
            main {
                controller = AribCaptionController(clock::get, {}, { cues ->
                    if (cues.isNotEmpty()) { wasShown.set(true); shown.countDown() }
                    else if (wasShown.get()) cleared.countDown()
                })
                controller.consume(AribCaptionPacket.OWNER, management())
                controller.select(controller.tracks.single().index)
                val text = byteArrayOf(0x0c, 0x1c, 0x47, 0x45, 0x0e) + "ARIB TIME".toByteArray() + byteArrayOf(0x9d.toByte(), 0x20, 0x4a)
                controller.consume(AribCaptionPacket.OWNER, statement(text = text))
            }
            assertTrue(shown.await(10, TimeUnit.SECONDS))
            assertFalse("Pause must not expire a one-second caption", cleared.await(1500, TimeUnit.MILLISECONDS))
            clock.set(2100)
            assertTrue("Caption must expire when media time advances", cleared.await(3, TimeUnit.SECONDS))
        } finally { main { controller.close() } }
    }

    @Test fun optionalNhkgSourceDiscoversSelectsAndRendersRealAribCaptions() {
        val url = InstrumentationRegistry.getArguments().getString("aribUrl")
        assumeTrue("Supply a source at invocation time", !url.isNullOrBlank())
        val ready = CountDownLatch(1)
        val rendered = CountDownLatch(1)
        val caption = CountDownLatch(1)
        val chosen = AtomicBoolean()
        val failure = AtomicReference<String>()
        lateinit var player: Media3VideoPlayer
        lateinit var scope: CoroutineScope
        lateinit var texture: SurfaceTexture
        lateinit var surface: Surface
        try {
            main {
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
                player = Media3VideoPlayer(instrumentation.targetContext, scope)
                player.initialize()
                player.setVolume(0f)
                val field = Media3VideoPlayer::class.java.getDeclaredField("videoPlayer").apply { isAccessible = true }
                val engine = field.get(player) as ExoPlayer
                texture = SurfaceTexture(0).apply { setDefaultBufferSize(1920, 1080) }
                surface = Surface(texture)
                engine.setVideoSurface(surface)
                engine.addListener(object : Player.Listener {
                    override fun onRenderedFirstFrame() { rendered.countDown() }
                })
                player.onReady { ready.countDown() }
                player.onError { if (it != null) failure.set(it.toString()) }
                player.onMetadata { metadata ->
                    val track = metadata.subtitleTracks.firstOrNull { it.mimeType == AribCaptionPacket.MIME_TYPE && it.label == "ARIB 字幕" }
                    if (track != null && chosen.compareAndSet(false, true)) player.selectSubtitleTrack(track)
                }
                player.onCues { cues -> if (cues.any { it.bitmap != null }) caption.countDown() }
                player.prepare(ChannelLine(url = url!!))
            }
            assertTrue("Source not ready: ${failure.get()}", ready.await(45, TimeUnit.SECONDS))
            assertTrue("Video frame missing: ${failure.get()}", rendered.await(15, TimeUnit.SECONDS))
            assertTrue("No rendered ARIB caption: ${failure.get()}", caption.await(90, TimeUnit.SECONDS))
            main {
                val field = Media3VideoPlayer::class.java.getDeclaredField("videoPlayer").apply { isAccessible = true }
                val engine = field.get(player) as ExoPlayer
                assertTrue(androidx.media3.common.C.TRACK_TYPE_TEXT in engine.trackSelectionParameters.disabledTrackTypes)
                player.prepare(ChannelLine(url = url!!))
                assertFalse("Leaving the ARIB service must restore normal text selection",
                    androidx.media3.common.C.TRACK_TYPE_TEXT in engine.trackSelectionParameters.disabledTrackTypes)
            }
        } finally {
            main { player.release(); scope.cancel(); surface.release(); texture.release() }
        }
    }

    private fun management(type: Int = 0x80, utf8: Boolean = false) = group(type, 0,
        byteArrayOf(0x3f, 1, 0x1a, 'j'.code.toByte(), 'p'.code.toByte(), 'n'.code.toByte(),
            (if (utf8) 0x84 else 0x80).toByte(), 0, 0, 0))
    private fun statement(type: Int = 0x80, text: ByteArray = byteArrayOf(0x0c, 0x1c, 0x47, 0x45, 0x0e) + "ARIB TEST".toByteArray()): ByteArray {
        val unit = byteArrayOf(0x1f, 0x20) + size(text.size) + text
        return group(type, 4, byteArrayOf(0x3f) + size(unit.size) + unit)
    }
    private fun size(value: Int) = byteArrayOf((value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())
    private fun group(type: Int, id: Int, body: ByteArray) =
        byteArrayOf(type.toByte(), 0xff.toByte(), 0xf0.toByte(), id.toByte(), 0, 0,
            (body.size ushr 8).toByte(), body.size.toByte()) + body + byteArrayOf(0, 0)
}
