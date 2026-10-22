package top.yogiczy.mytv.core.data.media

/** AVS3-P3 AATF header, matching the AV3A reference decoder bundled with FFmpeg. */
data class Av3aHeader(val sampleRate: Int, val channelCount: Int, val bitrate: Int, val frameSize: Int) {
    companion object {
        const val MIME_TYPE = "audio/av3a"
        const val HEADER_SIZE = 9 // Longest header, including mixed sound-bed/object audio.
        const val SAMPLES_PER_FRAME = 1024
        private val rates = intArrayOf(192000, 96000, 48000, 44100, 32000, 24000, 22050, 16000, 8000)
        private val channels = intArrayOf(1, 2, 6, 8, 12, 24, 4, 8, 10, 10, 12, 4, 9, 16)
        private val bitrates = arrayOf(
            intArrayOf(16, 32, 44, 56, 64, 72, 80, 96, 128, 144, 164, 192),
            intArrayOf(24, 32, 48, 64, 80, 96, 128, 144, 192, 256, 320),
            intArrayOf(192, 256, 320, 384, 448, 512, 640, 720, 144, 96, 128, 160),
            intArrayOf(192, 480, 256, 384, 576, 640, 128, 160),
            intArrayOf(), intArrayOf(),
            intArrayOf(48, 96, 128, 192, 256),
            intArrayOf(152, 320, 480, 576),
            intArrayOf(176, 384, 576, 704, 256, 448),
            intArrayOf(216, 480, 576, 384, 768),
            intArrayOf(240, 608, 384, 512, 832),
            intArrayOf(48, 96, 128, 192, 256),
            intArrayOf(192, 256, 320, 384, 480, 512, 640),
            intArrayOf(256, 320, 384, 512, 640, 896),
        )

        fun parse(data: ByteArray): Av3aHeader? {
            if (data.size < HEADER_SIZE) return null
            var position = 0
            fun bits(count: Int): Int {
                var value = 0
                repeat(count) {
                    value = (value shl 1) or ((data[position / 8].toInt() ushr (7 - position % 8)) and 1)
                    position++
                }
                return value
            }
            fun bitrate(channel: Int, index: Int) = bitrates.getOrNull(channel)?.getOrNull(index)?.times(1000)
            if (bits(12) != 0xfff || bits(4) != 2 || bits(1) != 0) return null
            if (bits(3) > 1) return null // Reference decoder supports main and low-complexity models.
            val profile = bits(3)
            val sampleRate = rates.getOrNull(bits(4)) ?: return null
            bits(8) // CRC high byte
            var channelConfig: Int
            val channelCount: Int
            var totalBitrate = 0
            when (profile) {
                0 -> {
                    channelConfig = bits(7)
                    if (channelConfig !in 0..10) return null
                    channelCount = channels[channelConfig]
                }
                1 -> {
                    channelConfig = 0
                    when (bits(2)) {
                        0 -> {
                            channelCount = bits(7) + 1
                            totalBitrate = channelCount * (bitrate(0, bits(4)) ?: return null)
                        }
                        1 -> {
                            channelConfig = bits(7)
                            if (channelConfig !in 0..10) return null
                            val bedBitrate = bitrate(channelConfig, bits(4)) ?: return null
                            val objects = bits(7) + 1
                            channelCount = channels[channelConfig] + objects
                            totalBitrate = bedBitrate + objects * (bitrate(0, bits(4)) ?: return null)
                        }
                        else -> return null
                    }
                }
                2 -> {
                    val order = bits(4) + 1
                    if (order !in 1..3) return null
                    channelConfig = 10 + order
                    channelCount = channels[channelConfig]
                }
                else -> return null
            }
            if (bits(2) == 3) return null // Reserved sample resolution.
            if (profile != 1) totalBitrate = bitrate(channelConfig, bits(4)) ?: return null
            bits(8) // CRC low byte; checked by the decoder together with the payload.
            // The reference encoder floors the bit budget before rounding up to whole bytes.
            val frameBits = totalBitrate.toLong() * SAMPLES_PER_FRAME / sampleRate
            val frameSize = ((frameBits + 7) / 8).toInt()
            if (channelCount !in 1..64 || frameSize !in HEADER_SIZE..65536) return null
            return Av3aHeader(sampleRate, channelCount, totalBitrate, frameSize)
        }
    }
}
