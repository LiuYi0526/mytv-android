package top.yogiczy.mytv.tv.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.AssetDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import top.yogiczy.mytv.tv.ui.screensold.videoplayer.player.av3a.Av3aHlsExtractorFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Exercises HLS sample queues and exposed track groups, not just TS extraction. */
@RunWith(AndroidJUnit4::class)
@OptIn(UnstableApi::class)
class Av3aHlsPlaybackTest {
    @Test fun muxedAacAndAv3aBothReachPlayerTrackGroups() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val discovered = CountDownLatch(1)
        val tracks = AtomicReference<Tracks>()
        val failure = AtomicReference<PlaybackException>()
        lateinit var player: ExoPlayer
        instrumentation.runOnMainSync {
            MimeTypes.registerCustomMimeType("audio/av3a", "av3a", C.TRACK_TYPE_AUDIO)
            player = ExoPlayer.Builder(instrumentation.targetContext).build()
            // Synthetic frames exercise extraction only; their payloads are not encoded audio.
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build()
            player.addListener(object : Player.Listener {
                override fun onTracksChanged(value: Tracks) {
                    if (!value.isEmpty) {
                        tracks.set(value)
                        discovered.countDown()
                    }
                }
                override fun onPlayerError(error: PlaybackException) {
                    failure.set(error)
                    discovered.countDown()
                }
            })
            player.setMediaSource(HlsMediaSource.Factory(DataSource.Factory { AssetDataSource(instrumentation.context) })
                .setExtractorFactory(Av3aHlsExtractorFactory())
                .setAllowChunklessPreparation(false)
                .createMediaSource(MediaItem.fromUri("asset:///aac-av3a.m3u8")))
            player.prepare()
        }
        try {
            assertTrue("HLS preparation timed out", discovered.await(15, TimeUnit.SECONDS))
            assertNull("HLS preparation failed", failure.get())
            val formats = tracks.get().groups.filter { it.type == C.TRACK_TYPE_AUDIO }
                .flatMap { group -> (0 until group.length).map { group.getTrackFormat(it) } }
            assertEquals(setOf("audio/mp4a-latm", "audio/av3a"), formats.map { it.sampleMimeType }.toSet())
            assertEquals(2, formats.size)
            assertEquals(10, formats.single { it.sampleMimeType == "audio/av3a" }.channelCount)
            assertEquals(48000, formats.single { it.sampleMimeType == "audio/av3a" }.sampleRate)
        } finally {
            instrumentation.runOnMainSync { player.release() }
        }
    }
}
