package top.yogiczy.mytv.tv.player

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.text.Spanned
import android.text.TextPaint
import android.text.style.AbsoluteSizeSpan
import android.text.style.MetricAffectingSpan
import android.view.View
import androidx.annotation.OptIn
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import top.yogiczy.mytv.tv.ui.screensold.videoplayer.player.mmt.MmtTtmlParser

@RunWith(AndroidJUnit4::class)
@OptIn(UnstableApi::class)
class MmtSubtitleRenderingTest {
    private fun document(content: String, rows: Int = 15) = """<tt xmlns="http://www.w3.org/ns/ttml"
        xmlns:tts="http://www.w3.org/ns/ttml#styling" xmlns:ttp="http://www.w3.org/ns/ttml#parameter"
        tts:extent="3840px 2160px" ttp:cellResolution="32 $rows">
        <head><layout><region xml:id="bottom" tts:origin="10% 70%" tts:extent="80% 20%"/></layout></head>
        <body begin="0s"><div begin="2s" end="4s"><p region="bottom">$content</p></div></body></tt>"""

    private fun parse(xml: String): CuesWithTiming {
        val bytes = xml.toByteArray()
        val samples = mutableListOf<CuesWithTiming>()
        // Explicit canvas must take precedence over an inaccurate descriptor.
        MmtTtmlParser(1080).parse(bytes, 0, bytes.size, SubtitleParser.OutputOptions.allCues()) { samples += it }
        return samples.single { it.cues.isNotEmpty() }
    }

    private fun fontPx(cue: Cue, index: Int, height: Int, density: Float): Float {
        val paint = TextPaint().apply { textSize = cue.textSize * height; this.density = density }
        val text = cue.text as Spanned
        text.getSpans(index, index + 1, MetricAffectingSpan::class.java).forEach { it.updateMeasureState(paint) }
        return paint.textSize
    }

    @Test fun broadcastPixelFontsScaleWithViewportAndIgnorePhoneDensity() {
        val sample = parse(document("""<span tts:fontSize="144px 144px" tts:color="yellow">字幕</span>"""))
        val cue = sample.cues.single()
        assertEquals(2_000_000L, sample.startTimeUs)
        assertEquals(2_000_000L, sample.durationUs)
        assertEquals("字幕", cue.text.toString())
        assertEquals(0.1f, cue.position, 0.001f)
        assertEquals(0.7f, cue.line, 0.001f)
        assertTrue((cue.text as Spanned).getSpans(0, 2, AbsoluteSizeSpan::class.java).isEmpty())
        assertEquals(72f, fontPx(cue, 0, 1080, 1f), 0.01f)
        assertEquals(72f, fontPx(cue, 0, 1080, 3f), 0.01f)
        assertEquals(144f, fontPx(cue, 0, 2160, 3f), 0.01f)
    }

    @Test fun nestedAbsoluteFontsOverrideInsteadOfMultiplying() {
        val cue = parse(document("""<span tts:fontSize="144px">甲<span tts:fontSize="72px">乙</span>丙</span>""")).cues.single()
        assertEquals(72f, fontPx(cue, 0, 1080, 3f), 0.01f)
        assertEquals(36f, fontPx(cue, 1, 1080, 3f), 0.01f)
        assertEquals(72f, fontPx(cue, 2, 1080, 3f), 0.01f)
    }

    @Test fun customCellResolutionDoesNotChangeExplicitPixelFontSize() {
        val cue = parse(document("""<span tts:fontSize="144px">字幕</span>""", rows = 30)).cues.single()
        assertEquals(72f, fontPx(cue, 0, 1080, 3f), 0.01f)
    }

    @Test fun relativeFontSizesRemainRelativeToRegion() {
        val cue = parse(document("""<span tts:fontSize="150%">字幕</span>""")).cues.single()
        assertEquals(108f, fontPx(cue, 0, 1080, 3f), 0.01f)
    }

    @Test fun actualSubtitleViewRendersInsideRegionAtTwoViewportSizes() {
        val cue = parse(document("""<span tts:fontSize="144px" tts:color="yellow">字幕</span>""")).cues.single()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            fun inkHeight(width: Int, height: Int): Int {
                val view = SubtitleView(instrumentation.targetContext)
                view.setStyle(CaptionStyleCompat(Color.WHITE, Color.TRANSPARENT, Color.TRANSPARENT,
                    CaptionStyleCompat.EDGE_TYPE_NONE, Color.TRANSPARENT, null))
                view.setCues(listOf(cue))
                view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                view.layout(0, 0, width, height)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(bitmap))
                var top = height; var bottom = -1
                for (y in 0 until height) for (x in 0 until width) {
                    if (Color.alpha(bitmap.getPixel(x, y)) > 100) { top = minOf(top, y); bottom = maxOf(bottom, y) }
                }
                bitmap.recycle()
                assertTrue("Subtitle did not render", bottom >= top)
                assertTrue("Subtitle escaped its region: $top..$bottom of $height", top >= height * 0.68f && bottom < height * 0.9f)
                return bottom - top + 1
            }
            val small = inkHeight(384, 216)
            val large = inkHeight(768, 432)
            assertTrue("Font did not scale with viewport: $small -> $large", large.toFloat() in (small * 1.6f)..(small * 2.5f))
        }
    }
}
