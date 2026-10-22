package top.yogiczy.mytv.tv.player

import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.ffmpeg.FfmpegAudioDecoder
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import top.yogiczy.mytv.core.data.media.Av3aHeader
import top.yogiczy.mytv.core.data.media.Av3aStreamParser
import java.io.File
import java.nio.ByteOrder
import kotlin.math.abs

/** Optional device probe. Supply a local elementary stream; broadcast content is never committed. */
@RunWith(AndroidJUnit4::class)
@OptIn(UnstableApi::class)
class Av3aDecodeTest {
    @Test fun realFramesProduceNonSilentPcm() {
        val name = InstrumentationRegistry.getArguments().getString("av3aSample")
        assumeTrue("Pass -e av3aSample <cache filename> for the real-stream probe", name != null)
        require(name == File(name!!).name)
        val sample = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, name)
        require(sample.length() in 1..1024 * 1024)
        val frames = mutableListOf<Pair<Av3aHeader, ByteArray>>()
        Av3aStreamParser { header, data, _ -> frames += header to data }.consume(sample.readBytes())
        assertTrue("Insufficient complete AV3A frames", frames.size >= 4)
        val header = frames.first().first
        val format = Format.Builder().setSampleMimeType(Av3aHeader.MIME_TYPE)
            .setChannelCount(header.channelCount).setSampleRate(header.sampleRate).build()
        val decoder = FfmpegAudioDecoder(format, 4, 4, 65536, false)
        var peak = 0
        var decoded = 0
        try {
            for ((_, frame) in frames.take(48)) {
                val input = checkNotNull(decoder.dequeueInputBuffer())
                input.ensureSpaceForWrite(frame.size)
                input.data!!.put(frame)
                input.timeUs = decoded * 1024_000_000L / header.sampleRate
                input.flip()
                decoder.queueInputBuffer(input)
                val deadline = SystemClock.elapsedRealtime() + 2000
                var output = decoder.dequeueOutputBuffer()
                while (output == null && SystemClock.elapsedRealtime() < deadline) {
                    SystemClock.sleep(5)
                    output = decoder.dequeueOutputBuffer()
                }
                assertNotNull("Decoder produced no PCM", output)
                val pcm = output!!.data!!.order(ByteOrder.nativeOrder()).asShortBuffer()
                assertEquals(1024 * header.channelCount, pcm.remaining())
                while (pcm.hasRemaining()) peak = maxOf(peak, abs(pcm.get().toInt()))
                output.release()
                decoded++
            }
            assertEquals(header.channelCount, decoder.channelCount)
            assertEquals(header.sampleRate, decoder.sampleRate)
            assertTrue("Decoded PCM is entirely silent", peak > 0)
            Log.i("Av3aDecodeTest", "frames=$decoded channels=${decoder.channelCount} rate=${decoder.sampleRate} peak=$peak")
        } finally {
            decoder.release()
        }
    }
}
