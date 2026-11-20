package top.yogiczy.mytv.tv.player

import android.text.Spanned
import android.text.style.StyleSpan
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.SubtitleParser
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import top.yogiczy.mytv.tv.ui.screensold.videoplayer.player.DashSubtitleParserFactory

@RunWith(AndroidJUnit4::class)
@OptIn(UnstableApi::class)
class DashSubtitleParserTest {
    @Test
    fun honoursRegionStyleInOneCellGridAndPreservesTimingPositionAndTextStyle() {
        val sample = parse(
            document("""<div region="bottom"><p begin="1s" end="2s">
                <span tts:fontStyle="italic">中文字幕</span></p></div>""")
        ).single { it.cues.isNotEmpty() }
        val cue = sample.cues.single()

        assertEquals(1_000_000L, sample.startTimeUs)
        assertEquals(1_000_000L, sample.durationUs)
        assertEquals(0.85f, cue.line, 0.0001f)
        assertEquals(Cue.TEXT_SIZE_TYPE_FRACTIONAL_IGNORE_PADDING, cue.textSizeType)
        assertEquals(0.04f, cue.textSize, 0.0001f)
        assertEquals("中文字幕", cue.text.toString())
        val text = cue.text as Spanned
        assertTrue(text.getSpans(0, text.length, StyleSpan::class.java).isNotEmpty())
    }

    @Test
    fun usesExplicitPercentageFromRegion() {
        val body = """<div><p begin="1s" end="2s" region="bottom">Caption</p></div>"""
        val sample = parse(document(body, bottomSize = "tts:fontSize=\"3.5%\""))
            .single { it.cues.isNotEmpty() }
        assertEquals(0.035f, sample.cues.single().textSize, 0.0001f)
    }

    @Test
    fun ordinaryGridKeepsMedia3Sizing() {
        val body = """<div><p begin="1s" end="2s" region="bottom">Caption</p></div>"""
        val cue = parse(document(body, rows = 15)).first { it.cues.isNotEmpty() }.cues.single()
        assertEquals(1f / 15f, cue.textSize, 0.0001f)
    }

    @Test
    fun doesNotAssignOneRegionSizeToTextInTwoDifferentlySizedRegions() {
        val body = """<div>
            <p begin="1s" end="2s" region="bottom">Bottom</p>
            <p begin="1s" end="2s" region="top">Top</p>
        </div>"""
        val cues = parse(document(body)).first { it.cues.isNotEmpty() }.cues
        assertEquals(2, cues.size)
        assertTrue(cues.all { it.textSize == 1f })
    }

    private fun parse(xml: String): List<CuesWithTiming> {
        val factory = DashSubtitleParserFactory()
        val format = Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_TTML).build()
        val samples = mutableListOf<CuesWithTiming>()
        // Matroska/MP4 extractors can pass in a slice of a larger sample buffer.
        val data = ("prefix" + xml + "suffix").toByteArray()
        factory.create(format).parse(data, 6, data.size - 12, SubtitleParser.OutputOptions.allCues()) {
            samples.add(it)
        }
        return samples
    }

    private fun document(body: String, rows: Int = 1, bottomSize: String = "") = """
        <tt xmlns="http://www.w3.org/ns/ttml" xmlns:tts="http://www.w3.org/ns/ttml#styling"
            xmlns:ttp="http://www.w3.org/ns/ttml#parameter" ttp:cellResolution="1 $rows">
            <head>
                <styling>
                    <style xml:id="caption" tts:fontSize="4.000000%" tts:origin="0% 85%" tts:extent="100% 4%"/>
                </styling>
                <layout>
                    <region xml:id="bottom" style="caption" $bottomSize/>
                    <region xml:id="top" tts:origin="0% 5%" tts:extent="100% 4%" tts:fontSize="6%"/>
                </layout>
            </head>
            <body>$body</body>
        </tt>
    """.trimIndent()
}
