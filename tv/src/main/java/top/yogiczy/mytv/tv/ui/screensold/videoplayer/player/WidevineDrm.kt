package top.yogiczy.mytv.tv.ui.screensold.videoplayer.player

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.drm.DefaultDrmSessionManagerProvider
import androidx.media3.datasource.okhttp.OkHttpDataSource
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import top.yogiczy.mytv.core.data.network.HttpBasicAuthInterceptor
import java.util.concurrent.TimeUnit

@androidx.annotation.OptIn(UnstableApi::class)
internal object WidevineDrm {
    fun isSupported(type: String?): Boolean =
        type?.trim()?.removeSurrounding("\"")?.lowercase() in setOf("widevine", "com.widevine.alpha")

    fun configuration(url: String?, headers: Map<String, String>): MediaItem.DrmConfiguration {
        val licenseUrl = url?.trim()?.removeSurrounding("\"")?.toHttpUrlOrNull()
        require(licenseUrl != null) { "Widevine requires an HTTP(S) license URL" }
        return MediaItem.DrmConfiguration.Builder(C.WIDEVINE_UUID)
            .setLicenseUri(licenseUrl.toString())
            // The playlist proxy overrides license URLs embedded in MPD/PSSH requests.
            .setForceDefaultLicenseUri(true)
            .setLicenseRequestHeaders(headers)
            .setMultiSession(true)
            .setPlayClearContentWithoutKey(true)
            .build()
    }

    fun sessionManagerProvider(userAgent: String, headers: Map<String, String>, timeoutMs: Long): DefaultDrmSessionManagerProvider {
        // License traffic has its own authentication and no media-session cookies or credentials.
        val client = OkHttpClient.Builder()
            .addInterceptor(HttpBasicAuthInterceptor())
            .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .build()
        val dataSource = OkHttpDataSource.Factory(client)
            .setUserAgent(userAgent)
            .setDefaultRequestProperties(headers)
        return DefaultDrmSessionManagerProvider().apply { setDrmHttpDataSourceFactory(dataSource) }
    }
}
