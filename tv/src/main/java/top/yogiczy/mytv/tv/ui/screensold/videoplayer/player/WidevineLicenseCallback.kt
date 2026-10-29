package top.yogiczy.mytv.tv.ui.screensold.videoplayer.player

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.drm.HttpMediaDrmCallback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

@androidx.annotation.OptIn(UnstableApi::class)
internal object WidevineLicenseCallback {
    fun isSupported(type: String?): Boolean =
        type?.trim()?.removeSurrounding("\"")?.lowercase() in setOf("widevine", "com.widevine.alpha")

    fun create(url: String?, dataSourceFactory: DataSource.Factory, headers: Map<String, String>): HttpMediaDrmCallback {
        val licenseUrl = url?.trim()?.removeSurrounding("\"")?.toHttpUrlOrNull()
        require(licenseUrl != null) { "Widevine requires an HTTP(S) license URL" }
        // The playlist proxy must take priority over an upstream URL embedded in the DRM request.
        return HttpMediaDrmCallback(licenseUrl.toString(), true, dataSourceFactory).apply {
            headers.forEach { (name, value) -> setKeyRequestProperty(name, value) }
        }
    }
}
