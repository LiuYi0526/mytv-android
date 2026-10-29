package top.yogiczy.mytv.core.data.network

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response

/** In-memory cookies owned by one playback session, including concurrent HLS track requests. */
class PlaybackCookieJar(private val clock: () -> Long = System::currentTimeMillis) : CookieJar {
    private data class Key(val name: String, val domain: String, val path: String)
    private val cookies = linkedMapOf<Key, Cookie>()

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        removeExpired()
        cookies.forEach { cookie ->
            val key = Key(cookie.name, cookie.domain, cookie.path)
            this.cookies.remove(key)
            if (cookie.expiresAt > clock()) this.cookies[key] = cookie
        }
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        removeExpired()
        return cookies.values.filter { it.matches(url) }.sortedByDescending { it.path.length }
    }

    @Synchronized
    fun clear() = cookies.clear()

    private fun removeExpired() {
        val now = clock()
        cookies.entries.removeAll { it.value.expiresAt <= now }
    }
}

/** Install as a network interceptor so Set-Cookie from each redirect is captured as well. */
class PlaybackCookieInterceptor(private val cookieJar: PlaybackCookieJar) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        // Preserve explicitly configured Cookie values, while adding other server-issued cookies.
        val explicit = request.headers.values("Cookie").joinToString("; ")
        val explicitNames = explicit.split(';').map { it.substringBefore('=').trim() }.toSet()
        val stored = cookieJar.loadForRequest(request.url).filter { it.name !in explicitNames }
            .joinToString("; ") { "${it.name}=${it.value}" }
        val cookieHeader = listOf(explicit, stored).filter { it.isNotBlank() }.joinToString("; ")
        val outgoing = if (cookieHeader.isEmpty()) request else request.newBuilder()
            .header("Cookie", cookieHeader).build()
        val response = chain.proceed(outgoing)
        cookieJar.saveFromResponse(request.url, Cookie.parseAll(request.url, response.headers))
        return response
    }
}
