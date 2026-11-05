package top.yogiczy.mytv.core.data.network

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

class TransparentGzipInterceptorTest {
    private val text = "#EXTM3U\n" + (0 until 210).joinToString("") {
        "#EXTINF:10,\nsegment-$it.ts\n"
    } + "#EXT-X-ENDLIST\n"

    private fun compressedResponse() = ByteArrayOutputStream().let { output ->
        GZIPOutputStream(output).use { it.write(text.toByteArray()) }
        val bytes = output.toByteArray()
        MockResponse().setBody(Buffer().write(bytes))
            .setHeader("Content-Encoding", "gzip")
            .setHeader("Content-Range", "bytes 0-${bytes.size - 1}/${bytes.size}")
    }

    @Test fun decodedPlaylistDoesNotKeepACompressedByteLimit() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(compressedResponse())
            server.enqueue(compressedResponse())
            val request = Request.Builder().url(server.url("/replay.m3u8")).build()
            // Reproduce the header that would make Media3 truncate the decoded playlist.
            OkHttpClient().newCall(request).execute().use { response ->
                assertNull(response.header("Content-Length"))
                assertNotNull(response.header("Content-Range"))
                assertTrue(response.header("Content-Range")!!.substringAfterLast('/').toLong() < text.length)
                assertEquals(text, response.body!!.string())
            }
            OkHttpClient.Builder().addInterceptor(TransparentGzipInterceptor()).build()
                .newCall(request).execute().use { response ->
                    assertNull(response.header("Content-Range"))
                    assertNull(response.header("Content-Encoding"))
                    assertEquals(text, response.body!!.string())
                }
        }
    }

    @Test fun redirectAuthenticationAndCookiesStillReachTheFullDecodedBody() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/hls/replay.m3u8")
                .addHeader("Set-Cookie", "session=keep; Path=/hls/"))
            server.enqueue(compressedResponse())
            val auth = HttpBasicAuth.parse(server.url("/").newBuilder().username("user").password("pass")
                .build().toString())
            val client = OkHttpClient.Builder().addInterceptor(HttpBasicAuthInterceptor(auth))
                .addInterceptor(TransparentGzipInterceptor())
                .addNetworkInterceptor(PlaybackCookieInterceptor(PlaybackCookieJar())).build()
            client.newCall(Request.Builder().url(server.url("/entry"))
                .header("User-Agent", "replay-player").build()).execute().use {
                assertEquals(text, it.body!!.string())
                assertNull(it.header("Content-Range"))
            }
            server.takeRequest()
            val redirected = server.takeRequest()
            assertEquals("session=keep", redirected.getHeader("Cookie"))
            assertEquals("Basic dXNlcjpwYXNz", redirected.getHeader("Authorization"))
            assertEquals("replay-player", redirected.getHeader("User-Agent"))
        }
    }

    @Test fun identityAndPartialResponsesKeepTheirLengthAndRangeHeaders() {
        MockWebServer().use { server ->
            server.start()
            val client = OkHttpClient.Builder().addInterceptor(TransparentGzipInterceptor()).build()
            for (code in listOf(200, 206)) {
                server.enqueue(MockResponse().setResponseCode(code).setBody("test")
                    .setHeader("Content-Range", "bytes 0-3/10"))
                val request = Request.Builder().url(server.url("/segment.ts"))
                    .apply { if (code == 206) header("Range", "bytes=0-3") }.build()
                client.newCall(request).execute().use {
                    assertEquals("bytes 0-3/10", it.header("Content-Range"))
                    assertEquals("4", it.header("Content-Length"))
                    assertEquals("test", it.body!!.string())
                }
            }
        }
    }

    @Test fun explicitlyRequestedGzipIsNotMistakenForTransparentDecoding() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(compressedResponse())
            val client = OkHttpClient.Builder().addInterceptor(TransparentGzipInterceptor()).build()
            client.newCall(Request.Builder().url(server.url("/replay.m3u8"))
                .header("Accept-Encoding", "gzip").build()).execute().use {
                assertEquals("gzip", it.header("Content-Encoding"))
                assertNotNull(it.header("Content-Range"))
                assertNotNull(it.header("Content-Length"))
            }
        }
    }
}
