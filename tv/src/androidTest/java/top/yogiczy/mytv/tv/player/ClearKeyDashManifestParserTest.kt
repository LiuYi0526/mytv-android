package top.yogiczy.mytv.tv.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.*
import org.junit.Test
import top.yogiczy.mytv.tv.ui.screensold.videoplayer.player.ClearKeyDashManifestParser

@androidx.annotation.OptIn(UnstableApi::class)
class ClearKeyDashManifestParserTest {
    private fun parse(protection: String) = ClearKeyDashManifestParser { listOf(java.util.UUID.fromString("00112233-4455-6677-8899-aabbccddeeff")) }.parse(
        Uri.parse("https://example.test/live.mpd"),
        """<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" xmlns:cenc="urn:mpeg:cenc:2013" mediaPresentationDuration="PT60S" minBufferTime="PT2S">
        <Period><AdaptationSet mimeType="video/mp4" codecs="avc1.4d401f">
        $protection
        <Representation id="video" bandwidth="1000000" width="1280" height="720">
        <BaseURL>video.mp4</BaseURL><SegmentBase indexRange="100-200"><Initialization range="0-99"/></SegmentBase>
        </Representation></AdaptationSet></Period></MPD>""".byteInputStream(),
    ).getPeriod(0).adaptationSets[0].representations[0].format.drmInitData

    @Test fun emptyCommonCencDeclarationUsesLicenseKidsForPssh() {
        val drm = parse("""
            <ContentProtection schemeIdUri="urn:mpeg:dash:mp4protection:2011" value="cenc"/>
            <ContentProtection schemeIdUri="urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed"/>
            <ContentProtection schemeIdUri="urn:uuid:1077efec-c0b2-4d02-ace3-3c1e52e2fb4b"/>
        """.trimIndent())!!
        val common = (0 until drm.schemeDataCount).map { drm[it] }.first { it.matches(C.COMMON_PSSH_UUID) }
        assertNotNull(common.data)
        val buffer = java.nio.ByteBuffer.wrap(common.data!!).apply { position(28) }
        assertEquals(1, buffer.int)
        assertEquals(java.util.UUID.fromString("00112233-4455-6677-8899-aabbccddeeff"),
            java.util.UUID(buffer.long, buffer.long))
        assertEquals("cenc", drm.schemeType)
    }

    @Test fun existingDefaultKidPsshIsPreserved() {
        val drm = parse("""<ContentProtection schemeIdUri="urn:mpeg:dash:mp4protection:2011" value="cenc" cenc:default_KID="00112233-4455-6677-8899-aabbccddeeff"/>""")!!
        assertEquals(1, drm.schemeDataCount)
        assertTrue(drm[0].matches(C.COMMON_PSSH_UUID))
        assertNotNull(drm[0].data)
    }

    @Test fun cencWithoutKidUsesConfiguredKeysEvenWithoutCommonUuidDeclaration() {
        val drm = parse("""
            <ContentProtection schemeIdUri="urn:mpeg:dash:mp4protection:2011" value="cenc"/>
            <ContentProtection schemeIdUri="urn:uuid:9a04f079-9840-4286-ab92-e65be0885f95"/>
            <ContentProtection schemeIdUri="urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed"/>
        """.trimIndent())!!
        val common = (0 until drm.schemeDataCount).map { drm[it] }.single { it.matches(C.COMMON_PSSH_UUID) }
        assertNotNull(common.data)
        assertEquals("cenc", drm.schemeType)
        assertTrue((0 until drm.schemeDataCount).any { drm[it].matches(C.PLAYREADY_UUID) })
        assertTrue((0 until drm.schemeDataCount).any { drm[it].matches(C.WIDEVINE_UUID) })
    }

    @Test fun doesNotInventClearKeyForWidevineOnlyOrUnencryptedManifest() {
        assertNull(parse(""))
        val drm = parse("""<ContentProtection schemeIdUri="urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed"/>""")!!
        assertEquals(1, drm.schemeDataCount)
        assertFalse(drm[0].matches(C.COMMON_PSSH_UUID))
    }
}
