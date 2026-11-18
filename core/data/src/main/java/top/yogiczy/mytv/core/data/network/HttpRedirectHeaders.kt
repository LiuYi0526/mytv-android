package top.yogiczy.mytv.core.data.network

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

/** Redirect-supplied headers, scoped to one playback and the origin that supplied/received them. */
class HttpRedirectHeaders {
    private data class Origin(val scheme: String, val host: String, val port: Int)
    private fun HttpUrl.origin() = Origin(scheme, host, port)
    private val overrides = linkedMapOf<Origin, Map<String, String>>()

    @Synchronized
    fun apply(request: Request): Request {
        val headers = overrides[request.url.origin()] ?: return request
        return request.newBuilder().apply { headers.forEach { (key, value) -> header(key, value) } }.build()
    }

    @Synchronized
    fun follow(from: HttpUrl, target: HttpUrl, headers: Map<String, String>) {
        val inherited = overrides[from.origin()].orEmpty().filterKeys {
            from.origin() == target.origin() || !isCredential(it)
        }
        if (inherited.isEmpty() && headers.isEmpty()) return
        val merged = inherited.toMutableMap()
        headers.forEach { (key, value) ->
            merged.keys.removeAll { it.equals(key, true) }
            merged[key] = value
        }
        overrides.remove(target.origin())
        overrides[target.origin()] = merged
        if (overrides.size > 32) overrides.remove(overrides.keys.first())
    }

    @Synchronized
    fun clear() = overrides.clear()

    private fun isCredential(name: String) = listOf("Authorization", "Proxy-Authorization", "Cookie", "Host")
        .any { it.equals(name, true) }
}

/** Network interceptor: fix Location before OkHttp follows it; never probe or open a second body. */
class HttpRedirectHeadersInterceptor(private val session: HttpRedirectHeaders = HttpRedirectHeaders()) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = session.apply(chain.request())
        val response = chain.proceed(request)
        if (response.code !in listOf(300, 301, 302, 303, 307, 308)) return response
        val location = response.header("Location") ?: return response
        val target = request.url.resolve(location.substringBefore('|')) ?: return response
        val options = if ('|' in location) "|" + location.substringAfter('|') else ""
        val parsed = HttpUrlHeaders.parse(target.toString() + options)
        session.follow(request.url, target, parsed.headers)
        return if ('|' in location) response.newBuilder().header("Location", parsed.url).build() else response
    }
}
