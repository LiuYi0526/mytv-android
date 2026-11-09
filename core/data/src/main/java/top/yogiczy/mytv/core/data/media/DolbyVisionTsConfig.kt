package top.yogiczy.mytv.core.data.media

/** Single-layer HEVC Dolby Vision configuration from a TS PMT DOVI descriptor (tag 0xB0). */
data class DolbyVisionTsConfig(
    val profile: Int,
    val level: Int,
    val baseLayerCompatibilityId: Int,
) {
    val codecs: String get() = "dvhe.${profile.toString().padStart(2, '0')}.${level.toString().padStart(2, '0')}"

    companion object {
        fun parse(descriptors: ByteArray): DolbyVisionTsConfig? {
            fun byte(index: Int) = descriptors[index].toInt() and 0xff
            var offset = 0
            while (offset + 2 <= descriptors.size) {
                val tag = byte(offset)
                val size = byte(offset + 1)
                val start = offset + 2
                if (size > descriptors.size - start) return null
                if (tag == 0xb0) {
                    if (size < 5 || byte(start) != 1 || byte(start + 1) != 0) return null
                    val flags = byte(start + 3)
                    // Dual-layer streams and absent RPU/BL need a different pipeline.
                    if (flags and 7 != 5) return null
                    val profile = byte(start + 2) ushr 1
                    val level = ((byte(start + 2) and 1) shl 5) or (flags ushr 3)
                    val compatibility = byte(start + 4) ushr 4
                    if (level !in 1..13) return null
                    if (profile == 5 && compatibility == 0 || profile == 8 && compatibility in listOf(1, 4)) {
                        return DolbyVisionTsConfig(profile, level, compatibility)
                    }
                    return null
                }
                offset = start + size
            }
            return null
        }
    }
}
