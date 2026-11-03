package top.yogiczy.mytv.tv.ui.screensold.videoplayer.player.mmt

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.MetricAffectingSpan
import android.text.style.RelativeSizeSpan
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.text.Cue
import androidx.media3.common.util.Consumer
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.extractor.text.ttml.TtmlParser
import org.xmlpull.v1.XmlPullParserFactory

/** ARIB pixel sizes refer to the broadcast canvas, not Android density-independent pixels. */
@OptIn(UnstableApi::class)
internal class MmtSubtitleParserFactory : SubtitleParser.Factory {
    private val delegate = DefaultSubtitleParserFactory()
    override fun supportsFormat(format: Format) = delegate.supportsFormat(format)
    override fun getCueReplacementBehavior(format: Format) = delegate.getCueReplacementBehavior(format)
    override fun create(format: Format): SubtitleParser =
        if (format.sampleMimeType == MimeTypes.APPLICATION_TTML) MmtTtmlParser(format.height)
        else delegate.create(format)
}

@OptIn(UnstableApi::class)
internal class MmtTtmlParser(private val descriptorHeight: Int) : SubtitleParser {
    private val delegate = TtmlParser()
    override fun getCueReplacementBehavior() = delegate.cueReplacementBehavior
    override fun reset() = delegate.reset()

    override fun parse(data: ByteArray, offset: Int, length: Int,
        outputOptions: SubtitleParser.OutputOptions, output: Consumer<CuesWithTiming>) {
        var height = descriptorHeight.takeIf { it > 0 } ?: 1080
        var rows = 15
        try {
            val parser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
            parser.setInput(data.inputStream(offset, length), null)
            parser.nextTag()
            parser.getAttributeValue("http://www.w3.org/ns/ttml#styling", "extent")
                ?.trim()?.split(Regex("\\s+"))?.takeIf { it.size == 2 }
                ?.last()?.removeSuffix("px")?.toIntOrNull()?.takeIf { it > 0 }?.let { height = it }
            parser.getAttributeValue("http://www.w3.org/ns/ttml#parameter", "cellResolution")
                ?.trim()?.split(Regex("\\s+"))?.takeIf { it.size == 2 }
                ?.last()?.toIntOrNull()?.takeIf { it > 0 }?.let { rows = it }
        } catch (_: org.xmlpull.v1.XmlPullParserException) {
            // Let the official parser handle invalid documents.
        } catch (_: java.io.IOException) {
            // Use the descriptor canvas if root metadata cannot be read.
        }
        delegate.parse(data, offset, length, outputOptions) { sample ->
            output.accept(CuesWithTiming(sample.cues.map { scalePixelFonts(it, height, rows) },
                sample.startTimeUs, sample.durationUs))
        }
    }

    private fun scalePixelFonts(cue: Cue, height: Int, rows: Int): Cue {
        val original = cue.text as? Spanned ?: return cue
        val sizes = original.getSpans(0, original.length, MetricAffectingSpan::class.java)
            .filter { it is AbsoluteSizeSpan || it is RelativeSizeSpan }
        if (sizes.none { it is AbsoluteSizeSpan }) return cue
        val fraction = if (cue.textSize > 0 && cue.textSizeType in listOf(
            Cue.TEXT_SIZE_TYPE_FRACTIONAL, Cue.TEXT_SIZE_TYPE_FRACTIONAL_IGNORE_PADDING)) cue.textSize else 1f / rows
        val base = height * fraction
        val text = SpannableStringBuilder(original)
        sizes.forEach(text::removeSpan)
        // Resolve nested sizes in Android span order before converting to relative spans.
        // Converting every absolute span separately would incorrectly multiply nested sizes.
        val boundaries = (listOf(0, original.length) + sizes.flatMap {
            listOf(original.getSpanStart(it), original.getSpanEnd(it))
        }).distinct().sorted()
        boundaries.zipWithNext().forEach { (start, end) ->
            var size = base
            sizes.filter { original.getSpanStart(it) < end && original.getSpanEnd(it) > start }.forEach {
                size = when (it) {
                    is AbsoluteSizeSpan -> it.size.toFloat()
                    is RelativeSizeSpan -> size * it.sizeChange
                    else -> size
                }
            }
            text.setSpan(RelativeSizeSpan(size / base), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return cue.buildUpon().setText(text)
            .setTextSize(fraction, Cue.TEXT_SIZE_TYPE_FRACTIONAL_IGNORE_PADDING).build()
    }
}
