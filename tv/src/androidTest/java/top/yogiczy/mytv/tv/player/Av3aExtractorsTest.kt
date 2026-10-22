package top.yogiczy.mytv.tv.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.TimestampAdjuster
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.ffmpeg.FfmpegLibrary
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.extractor.*
import androidx.media3.extractor.ts.TsExtractor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import top.yogiczy.mytv.tv.ui.screensold.videoplayer.player.av3a.Av3aExtractorsFactory
import top.yogiczy.mytv.tv.ui.screensold.videoplayer.player.av3a.Av3aHlsExtractorFactory

@RunWith(AndroidJUnit4::class)
@OptIn(UnstableApi::class)
class Av3aExtractorsTest {
    private fun fixture() = InstrumentationRegistry.getInstrumentation().context.assets
        .open("av3a-ten-channel.ts").use { it.readBytes() }

    private fun input(bytes: ByteArray, position: Long = 0): DefaultExtractorInput {
        var cursor = position.toInt()
        return DefaultExtractorInput(DataReader { target, offset, length ->
            if (cursor == bytes.size) C.RESULT_END_OF_INPUT else {
                val size = minOf(length, bytes.size - cursor)
                bytes.copyInto(target, offset, cursor, cursor + size)
                cursor += size
                size
            }
        }, position, C.LENGTH_UNSET.toLong())
    }

    private class Output : ExtractorOutput, TrackOutput {
        val formats = mutableListOf<Format>()
        val samples = mutableListOf<Pair<Long, Int>>()
        override fun track(id: Int, type: Int): TrackOutput = this
        override fun endTracks() = Unit
        override fun seekMap(seekMap: SeekMap) = Unit
        override fun format(format: Format) { formats += format }
        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int) =
            input.read(ByteArray(length), 0, length)
        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) = data.skipBytes(length)
        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
            samples += timeUs to size
        }
    }

    private fun extract(extractor: Extractor): Output {
        val bytes = fixture()
        var input = input(bytes)
        val output = Output()
        extractor.init(output)
        val seek = PositionHolder()
        repeat(100) {
            when (extractor.read(input, seek)) {
                Extractor.RESULT_END_OF_INPUT -> return output
                Extractor.RESULT_SEEK -> input = input(bytes, seek.position)
            }
        }
        error("Extractor did not finish")
    }

    private fun assertAv3a(output: Output) {
        val audio = output.formats.single { it.sampleMimeType == "audio/av3a" }
        assertEquals(10, audio.channelCount)
        assertEquals(48000, audio.sampleRate)
        assertEquals(384000, audio.averageBitrate)
        assertEquals(listOf(0L to 1024), output.samples)
    }

    @Test fun defaultMedia3DoesNotExposeAv3aButCustomProgressiveExtractorDoes() {
        assertTrue(extract(TsExtractor()).formats.isEmpty())
        val ts = Av3aExtractorsFactory().createExtractors().single { it is TsExtractor }
        assertAv3a(extract(ts))
    }

    @Test fun hlsTransportStreamExposesAv3a() {
        val input = input(fixture())
        val extractor = Av3aHlsExtractorFactory().createExtractor(
            Uri.parse("https://example.invalid/segment.ts"), Format.Builder().build(), null,
            TimestampAdjuster(0), emptyMap(), input, PlayerId.UNSET,
        )
        val output = Output()
        extractor.init(output)
        var reads = 0
        while (extractor.read(input)) check(++reads < 100)
        assertTrue(extractor.isReusable)
        assertAv3a(output)
    }

    @Test fun hlsWebVttStillUsesDefaultExtractor() {
        val input = input("WEBVTT\n\n00:00.000 --> 00:01.000\nTest\n".toByteArray())
        val extractor = Av3aHlsExtractorFactory().createExtractor(
            Uri.parse("https://example.invalid/subtitle.vtt"),
            Format.Builder().setSampleMimeType("text/vtt").build(), null,
            TimestampAdjuster(0), emptyMap(), input, PlayerId.UNSET,
        )
        assertFalse(extractor.isReusable)
        assertFalse(extractor.isPackedAudioExtractor)
    }

    @Test fun bundledFfmpegRecognizesAv3aMimeType() {
        assertTrue(FfmpegLibrary.isAvailable())
        assertTrue(FfmpegLibrary.supportsFormat("audio/av3a"))
    }
}
