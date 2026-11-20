package top.yogiczy.mytv.tv.ui.screensold.videoplayer.player

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
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import org.xmlpull.v1.XmlPullParserFactory
import java.io.IOException

@OptIn(UnstableApi::class)
internal class DashSubtitleParserFactory : SubtitleParser.Factory {
    private val delegate = DefaultSubtitleParserFactory()

    override fun supportsFormat(format: Format) = delegate.supportsFormat(format)
    override fun getCueReplacementBehavior(format: Format) = delegate.getCueReplacementBehavior(format)
    override fun create(format: Format): SubtitleParser =
        if (format.sampleMimeType == MimeTypes.APPLICATION_TTML) DashTtmlParser()
        else delegate.create(format)
}

@OptIn(UnstableApi::class)
private class DashTtmlParser : SubtitleParser {
    private val delegate = TtmlParser()

    override fun getCueReplacementBehavior() = delegate.cueReplacementBehavior
    override fun reset() = delegate.reset()

    override fun parse(
        data: ByteArray,
        offset: Int,
        length: Int,
        outputOptions: SubtitleParser.OutputOptions,
        output: Consumer<CuesWithTiming>,
    ) {
        val regionFraction = uniformRegionFontFraction(data, offset, length)
        if (regionFraction == null) {
            delegate.parse(data, offset, length, outputOptions, output)
            return
        }
        delegate.parse(data, offset, length, outputOptions) { sample ->
            output.accept(CuesWithTiming(sample.cues.map { cue ->
                // Media3 uses one full cell for a region and does not apply the region's fontSize.
                if (cue.text != null && cue.textSizeType == Cue.TEXT_SIZE_TYPE_FRACTIONAL_IGNORE_PADDING &&
                    cue.textSize == 1f
                ) {
                    cue.buildUpon().setTextSize(regionFraction, cue.textSizeType).build()
                } else {
                    cue
                }
            }, sample.startTimeUs, sample.durationUs))
        }
    }
}

private const val TTML = "http://www.w3.org/ns/ttml"
private const val TTS = "$TTML#styling"
private const val TTP = "$TTML#parameter"
private const val XML = "http://www.w3.org/XML/1998/namespace"
private val singleCellGrid = Regex("""\s*1\s+1\s*""")

private data class RegionFont(val size: String?, val styleId: String?) {
    fun percent(styles: Map<String, RegionFont>): Float? {
        var font = this
        val seen = mutableSetOf<String>()
        while (font.size == null) {
            val id = font.styleId ?: return null
            if (!seen.add(id)) return null
            font = styles[id] ?: return null
        }
        val size = font.size?.trim()?.takeIf { it.endsWith('%') } ?: return null
        val percent = size.dropLast(1).toFloatOrNull()?.takeIf { it > 0f && it <= 100f }
        return percent?.div(100f)
    }
}

/**
 * A percentage region font in a 1-by-1 grid is a fraction of the video height.
 * Media3 cues do not retain their region IDs. Only correct samples with an unambiguous size
 * shared by every region that contributes visible text.
 */
private fun uniformRegionFontFraction(data: ByteArray, offset: Int, length: Int): Float? {
    try {
        val parser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
        parser.setInput(data.inputStream(offset, length), null)
        parser.nextTag()
        if (parser.name != "tt" || parser.namespace != TTML ||
            parser.getAttributeValue(TTP, "cellResolution")?.matches(singleCellGrid) != true
        ) return null

        val styles = mutableMapOf<String, RegionFont>()
        val regions = mutableMapOf<String, RegionFont>()
        val activeRegions = mutableSetOf<String>()
        val inheritedRegions = ArrayDeque<String>()
        var paragraphDepth = 0
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    inheritedRegions.addLast(
                        parser.getAttributeValue(null, "region") ?: inheritedRegions.lastOrNull().orEmpty()
                    )
                    if (parser.namespace == TTML) {
                        when (parser.name) {
                            "p" -> paragraphDepth = parser.depth
                            "style", "region" -> parser.getAttributeValue(XML, "id")?.let { id ->
                                val fonts = if (parser.name == "style") styles else regions
                                fonts[id] = RegionFont(
                                    parser.getAttributeValue(TTS, "fontSize"),
                                    parser.getAttributeValue(null, "style")?.trim(),
                                )
                            }
                        }
                    }
                }
                XmlPullParser.TEXT -> if (paragraphDepth > 0 && !parser.text.isNullOrBlank()) {
                    activeRegions.add(inheritedRegions.last())
                }
                XmlPullParser.END_TAG -> {
                    if (parser.depth == paragraphDepth) paragraphDepth = 0
                    inheritedRegions.removeLast()
                }
            }
            parser.next()
        }
        return activeRegions.map { regions[it]?.percent(styles) }.distinct().singleOrNull()
    } catch (_: XmlPullParserException) {
        return null // Let Media3 handle unsupported or malformed input.
    } catch (_: IOException) {
        return null
    }
}
