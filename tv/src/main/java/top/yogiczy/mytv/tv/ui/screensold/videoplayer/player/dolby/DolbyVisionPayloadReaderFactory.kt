/*
 * Caption descriptor parsing adapted from AndroidX Media3 1.11.1
 * DefaultTsPayloadReaderFactory, Copyright (C) 2016 The Android Open Source Project.
 * Licensed under the Apache License, Version 2.0:
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package top.yogiczy.mytv.tv.ui.screensold.videoplayer.player.dolby

import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.CodecSpecificDataUtil
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.DolbyVisionH265Reader
import androidx.media3.extractor.ts.PesReader
import androidx.media3.extractor.ts.SeiReader
import androidx.media3.extractor.ts.TsPayloadReader
import top.yogiczy.mytv.core.data.media.DolbyVisionTsConfig

/** Only PMT-signalled, supported single-layer HEVC DV streams use the adapted reader. */
@OptIn(UnstableApi::class)
class DolbyVisionPayloadReaderFactory(
    private val delegate: TsPayloadReader.Factory = DefaultTsPayloadReaderFactory(),
    private val captionFormats: List<Format> = listOf(Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_CEA608).build()),
    private val overrideCaptionDescriptors: Boolean = false,
) : TsPayloadReader.Factory by delegate {
    override fun createPayloadReader(streamType: Int, esInfo: TsPayloadReader.EsInfo): TsPayloadReader? {
        val config = if (streamType == 0x24) DolbyVisionTsConfig.parse(esInfo.descriptorBytes) else null
        return if (config == null) delegate.createPayloadReader(streamType, esInfo)
        else PesReader(DolbyVisionH265Reader(
            SeiReader(closedCaptions(esInfo.descriptorBytes), MimeTypes.VIDEO_MP2T),
            MimeTypes.VIDEO_MP2T, config.codecs))
    }

    private fun closedCaptions(descriptors: ByteArray): List<Format> {
        if (overrideCaptionDescriptors) return captionFormats
        fun byte(index: Int) = descriptors[index].toInt() and 0xff
        var formats = captionFormats
        var offset = 0
        while (offset + 2 <= descriptors.size) {
            val tag = byte(offset)
            val start = offset + 2
            val size = byte(offset + 1)
            if (size > descriptors.size - start) break
            if (tag == 0x86 && size >= 1) {
                val count = byte(start) and 31
                if (size < 1 + 6 * count) break
                formats = (0 until count).map { index ->
                    val pos = start + 1 + 6 * index
                    val digital = byte(pos + 3) and 0x80 != 0
                    val mime = if (digital) MimeTypes.APPLICATION_CEA708 else MimeTypes.APPLICATION_CEA608
                    Format.Builder().setSampleMimeType(mime)
                        .setLanguage(String(descriptors, pos, 3, Charsets.US_ASCII))
                        .setAccessibilityChannel(if (digital) byte(pos + 3) and 63 else 1)
                        .setInitializationData(if (digital) CodecSpecificDataUtil.buildCea708InitializationData(byte(pos + 4) and 0x40 != 0) else null)
                        .build()
                }
            }
            offset = start + size
        }
        return formats
    }
}
