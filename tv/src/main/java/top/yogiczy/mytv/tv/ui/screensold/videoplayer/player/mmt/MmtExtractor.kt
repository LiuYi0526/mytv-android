package top.yogiczy.mytv.tv.ui.screensold.videoplayer.player.mmt

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.AacUtil
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.text.SubtitleTranscodingExtractorOutput
import androidx.media3.extractor.ts.ElementaryStreamReader
import androidx.media3.extractor.ts.H264Reader
import androidx.media3.extractor.ts.H265Reader
import androidx.media3.extractor.ts.LatmReader
import androidx.media3.extractor.ts.SeiReader
import androidx.media3.extractor.ts.TsPayloadReader
import top.yogiczy.mytv.core.data.media.mmt.MmtTlvParser
import top.yogiczy.mytv.core.data.media.mmt.MmtTlvParser.Companion.MIME_TYPE
import top.yogiczy.mytv.core.data.media.mmt.MmtTrack
import top.yogiczy.mytv.tv.ui.screensold.videoplayer.player.av3a.Av3aExtractorsFactory
import java.io.EOFException

/** Leaves the existing AV3A/default extractors intact and adds TLV probing for extensionless URLs. */
@OptIn(UnstableApi::class)
class MmtExtractorsFactory(private val forceMmt: Boolean = false) : ExtractorsFactory {
    private val delegate = Av3aExtractorsFactory()
    override fun createExtractors(): Array<Extractor> = arrayOf<Extractor>(MmtExtractor()) + delegate.createExtractors()
    override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> {
        val mmt = MmtExtractor()
        if (forceMmt) return arrayOf<Extractor>(mmt)
        val defaults = delegate.createExtractors(uri, responseHeaders)
        val path = uri.lastPathSegment.orEmpty().lowercase()
        val declared = responseHeaders.any { (key, values) ->
            key.equals("Content-Type", true) && values.any { it.substringBefore(';').trim().equals(MmtTlvParser.MIME_TYPE, true) }
        }
        return if (declared || listOf(".mmt", ".mmts", ".tlv").any(path::endsWith)) arrayOf<Extractor>(mmt) + defaults
        else defaults + mmt
    }
}

@OptIn(UnstableApi::class)
class MmtExtractor : Extractor {
    private lateinit var output: ExtractorOutput
    private lateinit var subtitleOutput: SubtitleTranscodingExtractorOutput
    private val readers = linkedMapOf<Int, ElementaryStreamReader>()
    private val rawAudio = linkedMapOf<Int, TrackOutput>()
    private val subtitles = linkedMapOf<Int, TrackOutput>()
    private val rawFormats = hashMapOf<Int, List<Byte>>()
    private val buffer = ByteArray(32768)
    private var tracksEnded = false
    private val parser = MmtTlvParser(object : MmtTlvParser.Listener {
        override fun onTracks(context: Int, tracks: List<MmtTrack>) {
            for (track in tracks) {
                val id = (context shl 16) or track.id
                if (id in readers || id in rawAudio || id in subtitles || tracksEnded) continue
                val reader: ElementaryStreamReader = when (track.assetType) {
                    "hev1", "hvc1" -> H265Reader(SeiReader(emptyList(), MIME_TYPE), MIME_TYPE)
                    "avc1", "avc3" -> H264Reader(SeiReader(emptyList(), MIME_TYPE), true, true, MIME_TYPE)
                    "mp4a" -> {
                        if (track.audioObjectType == 4) continue
                        if (track.audioTransport == 28 && track.audioConfig != null) {
                            rawAudio[id] = output.track(id, C.TRACK_TYPE_AUDIO)
                            continue
                        }
                        LatmReader(track.language, 0, MIME_TYPE)
                    }
                    "stpp" -> {
                        if (!track.isSubtitle) continue
                        subtitles[id] = output.track(id, C.TRACK_TYPE_TEXT).apply {
                            format(Format.Builder().setId(id.toString()).setSampleMimeType(MimeTypes.APPLICATION_TTML)
                                .setSubsampleOffsetUs(Format.OFFSET_SAMPLE_RELATIVE)
                                .setLanguage(track.language).setLabel(if (track.subtitleSuperimpose) "叠加字幕" else "普通字幕")
                                .setRoleFlags(C.ROLE_FLAG_CAPTION).setWidth(1920 shl track.subtitleResolution)
                                .setHeight(1080 shl track.subtitleResolution).build())
                        }
                        continue
                    }
                    else -> continue
                }
                reader.createTracks(output, TsPayloadReader.TrackIdGenerator(id, 1))
                readers[id] = reader
            }
            if (!tracksEnded && (readers.isNotEmpty() || rawAudio.isNotEmpty())) {
                output.endTracks()
                tracksEnded = true
            }
        }

        override fun onSample(context: Int, track: MmtTrack, data: ByteArray, timeUs: Long) {
            val id = (context shl 16) or track.id
            subtitles[id]?.let {
                it.sampleData(ParsableByteArray(data), data.size)
                it.sampleMetadata(timeUs, C.BUFFER_FLAG_KEY_FRAME, data.size, 0, null)
                return
            }
            val raw = rawAudio[id]
            if (raw != null) {
                val config = track.audioConfig ?: return
                if (rawFormats[id] != config.toList()) {
                    val audio = try { AacUtil.parseAudioSpecificConfig(config) } catch (_: Exception) { return }
                    raw.format(Format.Builder().setId(id.toString()).setSampleMimeType(MimeTypes.AUDIO_AAC)
                        .setCodecs(audio.codecs).setSampleRate(audio.sampleRateHz).setChannelCount(audio.channelCount)
                        .setLanguage(track.language).setInitializationData(listOf(config)).build())
                    rawFormats[id] = config.toList()
                }
                raw.sampleData(ParsableByteArray(data), data.size)
                raw.sampleMetadata(timeUs, C.BUFFER_FLAG_KEY_FRAME, data.size, 0, null)
                return
            }
            readers[id]?.let {
                it.packetStarted(timeUs, TsPayloadReader.FLAG_DATA_ALIGNMENT_INDICATOR)
                it.consume(ParsableByteArray(data))
                it.packetFinished()
            }
        }

        override fun onDiscontinuity(context: Int, packetId: Int) {
            readers[(context shl 16) or packetId]?.seek()
        }
    })

    override fun sniff(input: ExtractorInput): Boolean {
        val probe = ByteArray(131082)
        var size = 0
        try {
            while (size < probe.size) {
                val read = input.peek(probe, size, minOf(4096, probe.size - size))
                if (read == -1) break
                size += read
                if (MmtTlvParser.sniff(probe, size)) return true
            }
        } catch (_: EOFException) {
            // Short input remains eligible for other extractors.
        } finally {
            input.resetPeekPosition()
        }
        return false
    }

    override fun init(output: ExtractorOutput) {
        subtitleOutput = SubtitleTranscodingExtractorOutput(output, MmtSubtitleParserFactory())
        this.output = subtitleOutput
        output.seekMap(SeekMap.Unseekable(C.TIME_UNSET))
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        val count = input.read(buffer, 0, buffer.size)
        if (count == -1) {
            readers.values.forEach { it.endOfInputReached() }
            return Extractor.RESULT_END_OF_INPUT
        }
        parser.consume(buffer, 0, count)
        return Extractor.RESULT_CONTINUE
    }

    override fun seek(position: Long, timeUs: Long) {
        parser.reset()
        subtitleOutput.resetSubtitleParsers()
        readers.values.forEach { it.seek() }
    }
    override fun release() { parser.reset(); readers.clear(); rawAudio.clear(); rawFormats.clear(); subtitles.clear() }
}
