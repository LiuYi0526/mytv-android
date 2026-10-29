package top.yogiczy.mytv.tv.ui.screensold.videoplayer.player

import android.util.Pair
import androidx.media3.common.C
import androidx.media3.common.DrmInitData
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.dash.manifest.DashManifestParser
import androidx.media3.extractor.mp4.PsshAtomUtil
import org.xmlpull.v1.XmlPullParser
import java.util.UUID

/** Used only for configured ClearKey streams that omit initialization data from the MPD. */
@androidx.annotation.OptIn(UnstableApi::class)
internal class ClearKeyDashManifestParser(private val keyIds: () -> List<UUID>) : DashManifestParser() {
    private val missingPssh by lazy {
        val ids = keyIds()
        require(ids.isNotEmpty()) { "ClearKey license contains no KIDs" }
        PsshAtomUtil.buildPsshAtom(C.COMMON_PSSH_UUID, ids.toTypedArray(), null)
    }
    override fun parseContentProtection(xpp: XmlPullParser): Pair<String?, DrmInitData.SchemeData?> {
        val scheme = xpp.getAttributeValue(null, "schemeIdUri")
        val parsed = super.parseContentProtection(xpp)
        val commonDeclaration = scheme.equals("urn:uuid:${C.COMMON_PSSH_UUID}", true)
        val cencWithoutKid = scheme.equals("urn:mpeg:dash:mp4protection:2011", true) && parsed.first == "cenc"
        if (parsed.second == null && (commonDeclaration || cencWithoutKid)) {
            // License lookup runs on the manifest loader thread, never during UI preparation.
            return Pair(parsed.first, DrmInitData.SchemeData(C.COMMON_PSSH_UUID, MimeTypes.VIDEO_MP4, missingPssh))
        }
        return parsed
    }
}
