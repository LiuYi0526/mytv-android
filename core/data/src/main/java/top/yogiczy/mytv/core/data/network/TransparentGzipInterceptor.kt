package top.yogiczy.mytv.core.data.network

import okhttp3.Interceptor
import okhttp3.Response

/** Install as an application interceptor, after OkHttp has transparently decoded gzip. */
class TransparentGzipInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        // Some IPTV servers send Content-Range for the compressed representation even on
        // a full 200 response. BridgeInterceptor removes Content-Length/Content-Encoding
        // after decoding, but leaves Content-Range. Media3 would use that compressed byte
        // count as a limit for the decoded stream, truncating a playlist before ENDLIST.
        val decodedFullResponse = response.code == 200 &&
            response.request.header("Range") == null &&
            response.header("Content-Encoding") == null &&
            response.networkResponse?.header("Content-Encoding").equals("gzip", ignoreCase = true)
        return if (decodedFullResponse && response.header("Content-Range") != null) {
            response.newBuilder().removeHeader("Content-Range").build()
        } else response
    }
}
