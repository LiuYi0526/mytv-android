package top.yogiczy.mytv.tv.player

import android.graphics.SurfaceTexture
import android.net.Uri
import android.view.Surface
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Test
import org.junit.runner.RunWith
import top.yogiczy.mytv.tv.ui.screensold.videoplayer.player.mmt.MmtExtractorsFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Opt-in integration checks: pass -e rtmpUrl <url>; never edits device subscriptions/settings. */
@RunWith(AndroidJUnit4::class)
@OptIn(UnstableApi::class)
class RtmpPlaybackTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun sourceUri(): Uri {
        val url = InstrumentationRegistry.getArguments().getString("rtmpUrl")
        assumeNotNull(url)
        return Uri.parse(url)
    }

    @Test fun defaultDataSourceOpensRtmpAndReadsFlv() {
        val source = DefaultDataSource.Factory(instrumentation.targetContext).createDataSource()
        try {
            source.open(DataSpec(sourceUri()))
            val header = ByteArray(3)
            var offset = 0
            while (offset < header.size) {
                val count = source.read(header, offset, header.size - offset)
                assertTrue("RTMP ended before its FLV header", count > 0)
                offset += count
            }
            assertArrayEquals("FLV".toByteArray(Charsets.US_ASCII), header)
        } finally { source.close() }
    }

    @Test fun media3DiscoversAudioAndVideoAndContinuesRendering() {
        val uri = sourceUri()
        val firstFrame = CountDownLatch(1)
        val error = AtomicReference<PlaybackException>()
        val tracks = AtomicReference<Tracks>()
        val frames = AtomicInteger()
        lateinit var player: ExoPlayer
        lateinit var texture: SurfaceTexture
        lateinit var surface: Surface
        instrumentation.runOnMainSync {
            texture = SurfaceTexture(0)
            surface = Surface(texture)
            player = ExoPlayer.Builder(instrumentation.targetContext).build()
            player.volume = 0f
            player.setVideoSurface(surface)
            player.setVideoFrameMetadataListener { _, _, _, _ -> frames.incrementAndGet() }
            player.addListener(object : Player.Listener {
                override fun onRenderedFirstFrame() { firstFrame.countDown() }
                override fun onTracksChanged(value: Tracks) { tracks.set(value) }
                override fun onPlayerError(value: PlaybackException) { error.set(value); firstFrame.countDown() }
            })
            // Same DefaultDataSource + progressive extractor chain as Media3VideoPlayer.
            val source = ProgressiveMediaSource.Factory(
                DefaultDataSource.Factory(instrumentation.targetContext), MmtExtractorsFactory(),
            ).createMediaSource(MediaItem.fromUri(uri))
            player.setMediaSource(source)
            player.prepare()
            player.play()
        }
        try {
            assertTrue("No decoded RTMP frame in 30 seconds", firstFrame.await(30, TimeUnit.SECONDS))
            assertNull("RTMP player error: ${error.get()?.stackTraceToString()}", error.get())
            val groups = tracks.get().groups
            assertTrue(groups.any { it.type == C.TRACK_TYPE_VIDEO })
            assertTrue(groups.any { it.type == C.TRACK_TYPE_AUDIO })
            val before = frames.get()
            Thread.sleep(6000)
            assertNull("RTMP sustained error: ${error.get()?.stackTraceToString()}", error.get())
            assertTrue("Video stopped rendering: ${frames.get() - before} frames", frames.get() - before > 60)
            android.util.Log.i("RtmpProbe", "audio/video tracks=${groups.size}, additional video frames=${frames.get() - before}")
        } finally {
            instrumentation.runOnMainSync { player.release(); surface.release(); texture.release() }
        }
    }
}
