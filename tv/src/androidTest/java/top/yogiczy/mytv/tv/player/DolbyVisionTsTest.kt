package top.yogiczy.mytv.tv.player

import android.net.Uri
import android.util.Base64
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.TimestampAdjuster
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.extractor.*
import androidx.media3.extractor.ts.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import top.yogiczy.mytv.tv.ui.screensold.videoplayer.player.av3a.Av3aExtractorsFactory
import top.yogiczy.mytv.tv.ui.screensold.videoplayer.player.av3a.Av3aHlsExtractorFactory
import top.yogiczy.mytv.tv.ui.screensold.videoplayer.player.dolby.DolbyVisionPayloadReaderFactory
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(AndroidJUnit4::class)
@OptIn(UnstableApi::class)
class DolbyVisionTsTest {
    // VPS/SPS/PPS only (101 bytes); slices and RPUs below are synthetic parser fixtures.
    private val parameters = Base64.decode("AAABQAEMAf//AiAAAAMAsAAAAwAAAwCZGNAkAAAAAUIBAQIgAAADALAAAAMAAAMAmaAB4CACHE2NGNkkbK8BahIgEj+AAAH0gAB1MDCT8YiAALcbAAFuNiAAAAABRAHAck+HZAA=", Base64.DEFAULT)
    private fun nal(type: Int, vararg body: Int) = byteArrayOf(0, 0, 1, (type shl 1).toByte(), 1) + body.map { it.toByte() }.toByteArray()
    private fun elementary() = parameters + nal(35, 0x50) + nal(19, 0x80, 0x20) + nal(62, 0x19, 0x11) +
        nal(35, 0x50) + nal(1, 0x80, 0x21) + nal(62, 0x19, 0x22) +
        nal(35, 0x50) + nal(1, 0x80, 0x22) + nal(62, 0x19, 0x33) + nal(35, 0x50)

    private class Output : ExtractorOutput {
        val videos = mutableListOf<Track>()
        val texts = mutableListOf<Track>()
        override fun track(id: Int, type: Int) = Track().also {
            if (type == C.TRACK_TYPE_VIDEO) videos += it
            if (type == C.TRACK_TYPE_TEXT) texts += it
        }
        override fun endTracks() {}
        override fun seekMap(seekMap: SeekMap) {}
    }

    private class Track : TrackOutput {
        val formats = mutableListOf<Format>()
        val samples = mutableListOf<ByteArray>()
        val data = ByteArrayOutputStream()
        override fun format(format: Format) { formats += format }
        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
            val buffer = ByteArray(length)
            val size = input.read(buffer, 0, length)
            if (size > 0) data.write(buffer, 0, size)
            return size
        }
        override fun sampleData(buffer: ParsableByteArray, length: Int, sampleDataPart: Int) {
            data.write(buffer.data, buffer.position, length)
            buffer.skipBytes(length)
        }
        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
            val all = data.toByteArray()
            val end = all.size - offset
            samples += all.copyOfRange(end-size, end)
            data.reset()
            data.write(all, end, offset)
        }
    }

    private fun types(data: ByteArray): List<Int> = (0 until data.size-4).filter {
        data[it] == 0.toByte() && data[it+1] == 0.toByte() && data[it+2] == 1.toByte()
    }.map { (data[it+3].toInt() ushr 1) and 63 }

    private fun extractReader(reader: ElementaryStreamReader, chunkSize: Int): Track {
        val output = Output()
        reader.createTracks(output, TsPayloadReader.TrackIdGenerator(0, 1))
        reader.packetStarted(0, 0)
        elementary().asList().chunked(chunkSize).forEach { reader.consume(ParsableByteArray(it.toByteArray())) }
        reader.endOfInputReached()
        return output.videos.single()
    }

    @Test fun emitsDolbyVisionFormatAndKeepsRpuWithEachPictureEvenAcrossInputBoundaries() {
        for (chunkSize in listOf(1, 7, 31, 4096)) {
            val track = extractReader(DolbyVisionH265Reader(SeiReader(emptyList(), MimeTypes.VIDEO_MP2T),
                MimeTypes.VIDEO_MP2T, "dvhe.08.09"), chunkSize)
            assertEquals(MimeTypes.VIDEO_DOLBY_VISION, track.formats.single().sampleMimeType)
            assertEquals("dvhe.08.09", track.formats.single().codecs)
            assertEquals(3840, track.formats.single().width)
            assertEquals(2160, track.formats.single().height)
            assertEquals(3, track.samples.size)
            track.samples.forEach { assertEquals(1, types(it).count { type -> type == 62 }) }
        }
    }

    @Test fun reproducesTheUpstreamReaderLosingRpuFromSamples() {
        val track = extractReader(H265Reader(SeiReader(emptyList(), MimeTypes.VIDEO_MP2T), MimeTypes.VIDEO_MP2T), 7)
        assertEquals(MimeTypes.VIDEO_H265, track.formats.single().sampleMimeType)
        assertEquals(3, track.samples.size)
        assertTrue(track.samples.none { 62 in types(it) })
    }

    private fun esInfo(descriptors: ByteArray) = TsPayloadReader.EsInfo(-1, null, 0, null, descriptors)

    @Test fun doviReaderPreservesCaptionDescriptorsAndHlsOverrides() {
        val dovi = byteArrayOf(0xb0.toByte(), 5, 1, 0, 0x10, 0x4d, 0x10)
        val captions = byteArrayOf(0x86.toByte(), 7, 1, 0x65, 0x6e, 0x67, 0x81.toByte(), 0x40, 0)
        val reader = DolbyVisionPayloadReaderFactory().createPayloadReader(0x24, esInfo(dovi+captions))!!
        val output = Output()
        reader.init(TimestampAdjuster(0), output, TsPayloadReader.TrackIdGenerator(0, 1))
        assertEquals(MimeTypes.APPLICATION_CEA708, output.texts.single().formats.single().sampleMimeType)
        // Format normalizes ISO-639-2 eng to ISO-639-1 en.
        assertEquals("en", output.texts.single().formats.single().language)

        val override = listOf(Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_CEA608).setAccessibilityChannel(1).build())
        val forced = DolbyVisionPayloadReaderFactory(captionFormats = override, overrideCaptionDescriptors = true)
            .createPayloadReader(0x24, esInfo(dovi+captions))!!
        val forcedOutput = Output()
        forced.init(TimestampAdjuster(0), forcedOutput, TsPayloadReader.TrackIdGenerator(0, 1))
        assertEquals(MimeTypes.APPLICATION_CEA608, forcedOutput.texts.single().formats.single().sampleMimeType)
    }

    @Test fun ordinaryHevcAndUnknownConfigurationsUseTheDelegate() {
        var calls = 0
        val defaults = DefaultTsPayloadReaderFactory()
        val delegate = object : TsPayloadReader.Factory by defaults {
            override fun createPayloadReader(streamType: Int, esInfo: TsPayloadReader.EsInfo): TsPayloadReader? {
                calls++
                return defaults.createPayloadReader(streamType, esInfo)
            }
        }
        val factory = DolbyVisionPayloadReaderFactory(delegate)
        assertNotNull(factory.createPayloadReader(0x24, esInfo(byteArrayOf())))
        assertNotNull(factory.createPayloadReader(0x24, esInfo(byteArrayOf(0xb0.toByte(), 5, 1, 0, 40, 0x4d, 0x10))))
        assertNotNull(factory.createPayloadReader(0x24, esInfo(byteArrayOf(0xb0.toByte(), 5, 1, 0, 0x10, 0x4d, 0x10))))
        assertEquals(2, calls)
    }

    private fun input(bytes: ByteArray, position: Long = 0): DefaultExtractorInput {
        var cursor = position.toInt()
        return DefaultExtractorInput(DataReader { target, offset, length ->
            if (cursor == bytes.size) C.RESULT_END_OF_INPUT else {
                val size = minOf(length, bytes.size-cursor)
                bytes.copyInto(target, offset, cursor, cursor+size)
                cursor += size
                size
            }
        }, position, bytes.size.toLong())
    }

    @Test fun optionalBroadcastSampleWorksInBothHlsAndProgressiveAndKeepsMetadata() {
        val name = InstrumentationRegistry.getArguments().getString("dolbyFixture")
        assumeTrue("Supply a captured TS file in the target app's external files directory", !name.isNullOrEmpty())
        val file = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), name!!)
        val bytes = file.readBytes()
        val hls = Av3aHlsExtractorFactory().createExtractor(Uri.parse("https://example.invalid/picture.jpeg"),
            Format.Builder().build(), null, TimestampAdjuster(0), emptyMap(), input(bytes), PlayerId.UNSET)
        val hlsOutput = Output()
        hls.init(hlsOutput)
        val hlsInput = input(bytes)
        var reads = 0
        while (hls.read(hlsInput)) check(++reads < 100_000)
        val progressive = Av3aExtractorsFactory().createExtractors().single { it is TsExtractor }
        val output = Output()
        progressive.init(output)
        var progressiveInput = input(bytes)
        val position = PositionHolder()
        reads = 0
        while (true) {
            when (progressive.read(progressiveInput, position)) {
                Extractor.RESULT_END_OF_INPUT -> break
                Extractor.RESULT_SEEK -> progressiveInput = input(bytes, position.position)
            }
            check(++reads < 100_000)
        }
        for (result in listOf(hlsOutput, output)) {
            val track = result.videos.single()
            assertEquals(MimeTypes.VIDEO_DOLBY_VISION, track.formats.last().sampleMimeType)
            assertEquals("dvhe.08.09", track.formats.last().codecs)
            assertTrue(track.samples.size >= 350)
            assertTrue(track.samples.count { 62 in types(it) } >= 350)
        }
    }
}
