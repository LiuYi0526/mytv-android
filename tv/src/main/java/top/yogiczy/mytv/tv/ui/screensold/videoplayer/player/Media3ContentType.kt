package top.yogiczy.mytv.tv.ui.screensold.videoplayer.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util

/** Playlist hints select the source before opening an extensionless or redirecting URL. */
@OptIn(UnstableApi::class)
internal object Media3ContentType {
    fun normalize(manifestType: String?): String? =
        manifestType?.trim()?.removeSurrounding("\"")?.trim()?.lowercase()

    fun explicit(manifestType: String?): Int? = when (normalize(manifestType)) {
        "hls", "m3u8" -> C.CONTENT_TYPE_HLS
        "mpd" -> C.CONTENT_TYPE_DASH
        "mmt", "custom" -> C.CONTENT_TYPE_OTHER
        else -> null
    }

    fun resolve(uri: Uri, manifestType: String?, fallback: Int? = null): Int =
        explicit(manifestType) ?: when {
            uri.scheme.equals("rtp", true) || uri.scheme.equals("rtsp", true) -> C.CONTENT_TYPE_RTSP
            else -> fallback ?: Util.inferContentType(uri)
        }
}
