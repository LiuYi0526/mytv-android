package top.yogiczy.mytv.core.data.media

import org.junit.Assert.*
import org.junit.Test

class DolbyVisionTsConfigTest {
    private fun bytes(vararg values: Int) = values.map { it.toByte() }.toByteArray()
    private val broadcast = bytes(0xb0, 5, 1, 0, 0x10, 0x4d, 0x10)

    @Test fun parsesRealProfile8Hdr10CompatibleBroadcastDescriptor() {
        val config = DolbyVisionTsConfig.parse(broadcast)!!
        assertEquals(8, config.profile)
        assertEquals(9, config.level)
        assertEquals(1, config.baseLayerCompatibilityId)
        assertEquals("dvhe.08.09", config.codecs)
    }

    @Test fun acceptsSingleLayerProfile5AndHlgCompatibleProfile8() {
        assertEquals("dvhe.05.06", DolbyVisionTsConfig.parse(bytes(0xb0, 5, 1, 0, 10, 0x35, 0))?.codecs)
        assertEquals(4, DolbyVisionTsConfig.parse(broadcast.copyOf().also { it[6] = 0x40 })?.baseLayerCompatibilityId)
    }

    @Test fun walksOtherDescriptorsWithoutMistakingTheirPayloadForDovi() {
        val unknown = bytes(0x05, 4, 0xb0, 5, 1, 0)
        assertNull(DolbyVisionTsConfig.parse(unknown))
        assertEquals("dvhe.08.09", DolbyVisionTsConfig.parse(unknown + broadcast + bytes(0x09, 0))?.codecs)
    }

    @Test fun truncatedOrEmptyDescriptorsAreSafe() {
        for (size in broadcast.indices) assertNull(DolbyVisionTsConfig.parse(broadcast.copyOf(size)))
        assertNull(DolbyVisionTsConfig.parse(bytes(0x05, 0xff) + broadcast))
        assertNull(DolbyVisionTsConfig.parse(bytes(0xb0, 0)))
    }

    @Test fun rejectsDualLayerMissingMetadataAndUnknownProfiles() {
        for (flags in listOf(0x48, 0x49, 0x4b, 0x4c, 0x4f)) {
            assertNull(DolbyVisionTsConfig.parse(broadcast.copyOf().also { it[5] = flags.toByte() }))
        }
        assertNull(DolbyVisionTsConfig.parse(broadcast.copyOf().also { it[4] = 14; it[5] = 0x4f }))
        assertNull(DolbyVisionTsConfig.parse(broadcast.copyOf().also { it[4] = 40 }))
    }

    @Test fun rejectsUnsupportedVersionLevelAndBaseLayerCompatibility() {
        assertNull(DolbyVisionTsConfig.parse(broadcast.copyOf().also { it[2] = 2 }))
        assertNull(DolbyVisionTsConfig.parse(broadcast.copyOf().also { it[3] = 1 }))
        assertNull(DolbyVisionTsConfig.parse(broadcast.copyOf().also { it[5] = 5 }))
        assertNull(DolbyVisionTsConfig.parse(broadcast.copyOf().also { it[5] = 0x7d }))
        assertNull(DolbyVisionTsConfig.parse(broadcast.copyOf().also { it[6] = 0 }))
    }
}
