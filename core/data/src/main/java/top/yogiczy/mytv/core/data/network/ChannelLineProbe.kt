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
    suspend fun measure(url: String, userAgent: String? = null): Long =
        suspendCancellableCoroutine { continuation ->
            val request = Request.Builder().url(url).apply {
                userAgent?.let { header("User-Agent", it) }
            }.build()
            val call = client.newCall(request)
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
                                if (!it.isSuccessful) throw IOException("HTTP ${it.code}")
                                val body = it.body ?: throw IOException("Empty response")
                                if (body.byteStream().read() == -1) throw IOException("Empty response")
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
