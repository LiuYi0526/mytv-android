package top.yogiczy.mytv.core.data.network

import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response

/** Credentials from an HTTP(S) URL, with user information removed from the request URL. */
class HttpBasicAuth private constructor(
    val url: HttpUrl,
    val authorization: String,
    val urlWithEncodedCredentials: String,
) {
    fun hasSameOrigin(other: HttpUrl): Boolean =
        url.scheme == other.scheme && url.host == other.host && url.port == other.port

    companion object {
        fun parse(value: String): HttpBasicAuth? {
            val url = value.toHttpUrlOrNull() ?: return null
            if (url.username.isEmpty() && url.password.isEmpty()) return null

            return HttpBasicAuth(
                url.newBuilder().username("").password("").build(),
                Credentials.basic(url.username, url.password, Charsets.UTF_8),
                // FFmpeg decodes user information as form data, so a literal '+' must be escaped.
                url.newBuilder()
                    .encodedUsername(url.encodedUsername.replace("+", "%2B"))
                    .encodedPassword(url.encodedPassword.replace("+", "%2B"))
                    .build().toString(),
            )
        }
    }
}

/** Media playlists may resolve relative segment/key URLs without carrying user information. */
class HttpBasicAuthInterceptor(
    private val mediaAuth: HttpBasicAuth? = null,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val urlAuth = HttpBasicAuth.parse(request.url.toString())
        val auth = urlAuth ?: mediaAuth?.takeIf { it.hasSameOrigin(request.url) }
        if (auth == null) return chain.proceed(request)

        val authenticatedRequest = request.newBuilder().apply {
            urlAuth?.let { url(it.url) }
            // Preserve an explicitly configured Authorization header (e.g. WebDAV credentials).
            if (request.header("Authorization") == null) {
                header("Authorization", auth.authorization)
            }
        }.build()
        return chain.proceed(authenticatedRequest)
    }
}
