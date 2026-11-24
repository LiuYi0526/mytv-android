package top.yogiczy.mytv.core.data.network

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Measures time to first byte without buffering an entire (possibly endless) live stream. */
class ChannelLineProbe internal constructor(private val client: OkHttpClient) {
    class HttpError(val statusCode: Int) : IOException("HTTP $statusCode")

    class EmptyResponse : IOException("Empty response")

    suspend fun measure(url: String, userAgent: String? = null, headers: Map<String, String> = emptyMap()): Long =
        suspendCancellableCoroutine { continuation ->
            val request = Request.Builder().url(url).apply {
                headers.forEach { (name, value) -> header(name, value) }
                userAgent?.let { header("User-Agent", it) }
            }.build()
            // Each measurement owns its redirect headers; the singleton probe must not retain them.
            val call = client.newBuilder().addNetworkInterceptor(HttpRedirectHeadersInterceptor()).build().newCall(request)
            val startedAt = System.nanoTime()

            // Keep cancellation attached through body reading, not just until headers arrive.
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val elapsed = response.use {
                            try {
                                if (!it.isSuccessful) throw HttpError(it.code)
                                val body = it.body ?: throw EmptyResponse()
                                if (body.byteStream().read() == -1) throw EmptyResponse()
                                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
                                    .coerceAtLeast(1)
                            } finally {
                                // Stop the stream before close(), which may otherwise drain it.
                                call.cancel()
                            }
                        }
                        continuation.resume(elapsed)
                    } catch (e: IOException) {
                        continuation.resumeWithException(e)
                    }
                }
            })
        }

    companion object {
        val Default by lazy {
            ChannelLineProbe(
                OkHttpClient.Builder()
                    .addInterceptor(HttpBasicAuthInterceptor())
                    .sslSocketFactory(
                        TrustAllSSLSocketFactory.sslSocketFactory,
                        TrustAllSSLSocketFactory.trustManager,
                    )
                    .hostnameVerifier { _, _ -> true }
                    .connectTimeout(5, TimeUnit.SECONDS)
                    .readTimeout(5, TimeUnit.SECONDS)
                    .callTimeout(5, TimeUnit.SECONDS)
                    .build(),
            )
        }
    }
}
