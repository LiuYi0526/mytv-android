package top.yogiczy.mytv.core.data.network

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import top.yogiczy.mytv.core.data.media.ClearKeyLicense
import java.io.IOException

/** Posts the platform ClearKey challenge and validates the complete license response. */
class ClearKeyLicenseFetcher(
    private val client: OkHttpClient,
    private val headers: Map<String, String> = emptyMap(),
) {
    fun load(url: String, challenge: ByteArray): ByteArray {
        val request = Request.Builder().url(url).apply {
            headers.forEach { (name, value) -> header(name, value) }
        }.post(challenge.toRequestBody("application/json".toMediaType())).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("ClearKey license HTTP ${response.code}")
            val source = response.body?.source() ?: throw IOException("Empty ClearKey license response")
            source.request(65537)
            if (source.buffer.size > 65536) throw IOException("ClearKey license response too large")
            return try {
                ClearKeyLicense.remoteResponse(source.readUtf8())
            } catch (_: Exception) {
                // Do not expose license data, URL tokens, or the response body in playback errors.
                throw IOException("Invalid ClearKey JWK response")
            }
        }
    }
}
