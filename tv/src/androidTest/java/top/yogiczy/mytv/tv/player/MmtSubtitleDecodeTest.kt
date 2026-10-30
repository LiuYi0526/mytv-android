package top.yogiczy.mytv.tv.player

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.text.ttml.TtmlParser
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Verify that the existing Media3 renderer understands preserved TTML times and line breaks. */
@RunWith(AndroidJUnit4::class)
@OptIn(UnstableApi::class)
class MmtSubtitleDecodeTest {
    @Test fun capturedStreamProducesVisibleCuesOnPlaybackTimeline() {
        val name = androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("mmtFixture")
        org.junit.Assume.assumeTrue(name != null)
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val bytes = java.io.File(context.getExternalFilesDir(null), name!!).readBytes()
        var cursor = 0
        val input = androidx.media3.extractor.DefaultExtractorInput(androidx.media3.common.DataReader { dst, offset, length ->
            if (cursor == bytes.size) -1 else minOf(length, bytes.size - cursor).also {
                bytes.copyInto(dst, offset, cursor, cursor + it); cursor += it
            }
        }, 0, bytes.size.toLong())
        val cues = mutableListOf<androidx.media3.extractor.text.CuesWithTiming>()
        val extractor = top.yogiczy.mytv.tv.ui.screensold.videoplayer.player.mmt.MmtExtractor()
        extractor.init(object : androidx.media3.extractor.ExtractorOutput {
            override fun endTracks() {}
            override fun seekMap(seekMap: androidx.media3.extractor.SeekMap) {}
            override fun track(id: Int, type: Int): androidx.media3.extractor.TrackOutput = object : androidx.media3.extractor.TrackOutput {
                val data = java.io.ByteArrayOutputStream()
                override fun format(format: androidx.media3.common.Format) {}
                override fun sampleData(input: androidx.media3.common.DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int) = input.read(ByteArray(length),0,length)
                override fun sampleData(b: androidx.media3.common.util.ParsableByteArray, length: Int, sampleDataPart: Int) {
                    if (type == androidx.media3.common.C.TRACK_TYPE_TEXT) data.write(b.data,b.position,length)
                    b.skipBytes(length)
                }
                override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: androidx.media3.extractor.TrackOutput.CryptoData?) {
                    if (type != androidx.media3.common.C.TRACK_TYPE_TEXT) return
                    val bytes = data.toByteArray()
                    cues += androidx.media3.extractor.text.CueDecoder().decode(timeUs, bytes, 0, bytes.size)
                    data.reset()
                }
            }
        })
        try {
            val position = androidx.media3.extractor.PositionHolder()
            while (extractor.read(input,position) != androidx.media3.extractor.Extractor.RESULT_END_OF_INPUT) {}
            val nonempty = cues.filter { it.cues.isNotEmpty() }
            println("MMT subtitle all samples=${cues.size}, cues=" + nonempty.map { "${it.startTimeUs}/${it.durationUs}:" + it.cues.map { c -> "${c.text} position=${c.position} line=${c.line} size=${c.size}" } })
            assertTrue("No subtitle text survived actual extractor/transcoder",nonempty.isNotEmpty())
            assertTrue("Subtitle time outside captured minute",nonempty.all { it.startTimeUs in -5_000_000L..90_000_000L })
            assertTrue("Subtitle region is outside the viewport", nonempty.flatMap { it.cues }.all {
                it.position in 0f..1f && it.line in 0f..1f && it.size > 0f && it.size <= 1f
            })
        } finally { extractor.release() }
    }

    @Test fun decodesJapaneseTextAndKeepsCueWindow() {
        val xml = """<tt xmlns="http://www.w3.org/ns/ttml"><body><div>
            <p begin="2s" end="4s">日本語字幕<br/>二行目</p>
            </div></body></tt>""".toByteArray()
        val subtitle = TtmlParser().parseToLegacySubtitle(xml, 0, xml.size)
        assertTrue(subtitle.getCues(1_999_999).isEmpty())
        assertEquals("日本語字幕\n二行目", subtitle.getCues(2_000_000).single().text.toString())
        assertTrue(subtitle.getCues(4_000_000).isEmpty())
    }
}
