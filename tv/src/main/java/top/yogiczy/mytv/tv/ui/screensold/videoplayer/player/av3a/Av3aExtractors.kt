package top.yogiczy.mytv.tv.ui.screensold.videoplayer.player.av3a

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.TimestampAdjuster
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.hls.BundledHlsMediaChunkExtractor
import androidx.media3.exoplayer.hls.DefaultHlsExtractorFactory
import androidx.media3.exoplayer.hls.HlsExtractorFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.ElementaryStreamReader
import androidx.media3.extractor.ts.PesReader
import androidx.media3.extractor.ts.TsExtractor
import androidx.media3.extractor.ts.TsPayloadReader
import top.yogiczy.mytv.core.data.media.Av3aHeader
import top.yogiczy.mytv.core.data.media.Av3aStreamParser
import java.io.EOFException

/** Adds AV3A to the public Media3 TS extension point; all other stream types use the defaults. */
@OptIn(UnstableApi::class)
class Av3aPayloadReaderFactory(
    private val delegate: TsPayloadReader.Factory = DefaultTsPayloadReaderFactory(),
) : TsPayloadReader.Factory by delegate {
    override fun createPayloadReader(streamType: Int, esInfo: TsPayloadReader.EsInfo): TsPayloadReader? =
        if (streamType == 0xd5) PesReader(Av3aReader(esInfo.language, esInfo.roleFlags))
        else delegate.createPayloadReader(streamType, esInfo)
}

@OptIn(UnstableApi::class)
private class Av3aReader(private val language: String?, private val roleFlags: Int) : ElementaryStreamReader {
    private lateinit var output: TrackOutput
    private lateinit var formatId: String
    private var format: Format? = null
    private val parser = Av3aStreamParser { header, bytes, timeUs ->
        val nextFormat = Format.Builder()
            .setId(formatId).setSampleMimeType(Av3aHeader.MIME_TYPE)
            .setSampleRate(header.sampleRate).setChannelCount(header.channelCount)
            .setAverageBitrate(header.bitrate).setPeakBitrate(header.bitrate)
            .setLanguage(language).setRoleFlags(roleFlags).setMaxInputSize(65536).build()
        if (nextFormat != format) {
            output.format(nextFormat)
            format = nextFormat
        }
        if (timeUs != null) {
            output.sampleData(ParsableByteArray(bytes), bytes.size)
            output.sampleMetadata(timeUs, C.BUFFER_FLAG_KEY_FRAME, bytes.size, 0, null)
        }
    }

    override fun createTracks(output: ExtractorOutput, idGenerator: TsPayloadReader.TrackIdGenerator) {
        idGenerator.generateNewId()
        formatId = idGenerator.formatId
        this.output = output.track(idGenerator.trackId, C.TRACK_TYPE_AUDIO)
    }

    override fun seek() = parser.seek()
    override fun packetStarted(pesTimeUs: Long, flags: Int) =
        parser.packetStarted(pesTimeUs.takeUnless { it == C.TIME_UNSET })
    override fun packetFinished(isEndOfInput: Boolean) = Unit
    override fun consume(data: ParsableByteArray) {
        parser.consume(data.data, data.position, data.bytesLeft())
        data.setPosition(data.limit())
    }
}

@OptIn(UnstableApi::class)
class Av3aExtractorsFactory : ExtractorsFactory {
    private val delegate = DefaultExtractorsFactory()
    override fun createExtractors(): Array<Extractor> = replaceTs(delegate.createExtractors())
    override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
        replaceTs(delegate.createExtractors(uri, responseHeaders))

    private fun replaceTs(extractors: Array<Extractor>): Array<Extractor> = extractors.map {
        if (it.underlyingImplementation is TsExtractor) {
            TsExtractor(TsExtractor.MODE_SINGLE_PMT, TsExtractor.FLAG_EMIT_RAW_SUBTITLE_DATA,
                SubtitleParser.Factory.UNSUPPORTED, TimestampAdjuster(0), Av3aPayloadReaderFactory(),
                TsExtractor.DEFAULT_TIMESTAMP_SEARCH_BYTES)
        } else it
    }.toTypedArray()
}

@OptIn(UnstableApi::class)
class Av3aHlsExtractorFactory(
    private val delegate: DefaultHlsExtractorFactory = DefaultHlsExtractorFactory(),
) : HlsExtractorFactory by delegate {
    override fun createExtractor(
        uri: Uri, format: Format, muxedCaptionFormats: List<Format>?,
        timestampAdjuster: TimestampAdjuster, responseHeaders: Map<String, List<String>>,
        sniffingExtractorInput: ExtractorInput, playerId: PlayerId,
    ): BundledHlsMediaChunkExtractor {
        var flags = DefaultTsPayloadReaderFactory.FLAG_IGNORE_SPLICE_INFO_STREAM
        if (muxedCaptionFormats != null) flags = flags or DefaultTsPayloadReaderFactory.FLAG_OVERRIDE_CAPTION_DESCRIPTORS
        val captions = muxedCaptionFormats ?: listOf(
            Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_CEA608).build()
        )
        if (!format.codecs.isNullOrEmpty()) {
            if (!MimeTypes.containsCodecsCorrespondingToMimeType(format.codecs, MimeTypes.AUDIO_AAC)) {
                flags = flags or DefaultTsPayloadReaderFactory.FLAG_IGNORE_AAC_STREAM
            }
            if (!MimeTypes.containsCodecsCorrespondingToMimeType(format.codecs, MimeTypes.VIDEO_H264)) {
                flags = flags or DefaultTsPayloadReaderFactory.FLAG_IGNORE_H264_STREAM
            }
        }
        val ts = TsExtractor(
            TsExtractor.MODE_HLS, TsExtractor.FLAG_EMIT_RAW_SUBTITLE_DATA, SubtitleParser.Factory.UNSUPPORTED,
            timestampAdjuster, Av3aPayloadReaderFactory(DefaultTsPayloadReaderFactory(flags, captions)),
            TsExtractor.DEFAULT_TIMESTAMP_SEARCH_BYTES,
        )
        sniffingExtractorInput.resetPeekPosition()
        val isTs = try {
            ts.sniff(sniffingExtractorInput)
        } catch (_: EOFException) {
            false
        } finally {
            sniffingExtractorInput.resetPeekPosition()
        }
        if (isTs) return BundledHlsMediaChunkExtractor(ts, format, timestampAdjuster)
        return delegate.createExtractor(uri, format, muxedCaptionFormats, timestampAdjuster,
            responseHeaders, sniffingExtractorInput, playerId)
    }
}
