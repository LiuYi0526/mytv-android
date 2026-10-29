package top.yogiczy.mytv.core.data.network

import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test

class PlaybackCookieJarTest {
    @Test fun redirectAndPlaylistRotationReachChildSegmentAndKeyWithoutChangingHeaders() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/hls/master.m3u8")
                .addHeader("Set-Cookie", "hdntl=first; Path=/hls/; HttpOnly"))
            server.enqueue(MockResponse().setBody("master")
                .addHeader("Set-Cookie", "hdntl=rotated; Path=/hls/; HttpOnly"))
            repeat(3) { server.enqueue(MockResponse().setBody("media")) }
            val auth = HttpBasicAuth.parse(server.url("/").newBuilder().username("user").password("pass").build().toString())
            val client = OkHttpClient.Builder().addInterceptor(HttpBasicAuthInterceptor(auth))
                .addNetworkInterceptor(PlaybackCookieInterceptor(PlaybackCookieJar())).build()
            client.newCall(Request.Builder().url(server.url("/entry?hdnea=original"))
                .header("User-Agent", "playlist-player").build()).execute().use { assertEquals(200, it.code) }
            assertEquals("/entry?hdnea=original", server.takeRequest().path)
            val redirected = server.takeRequest()
            assertEquals("hdntl=first", redirected.getHeader("Cookie"))
            assertEquals("Basic dXNlcjpwYXNz", redirected.getHeader("Authorization"))
            for (path in listOf("/hls/child.m3u8", "/hls/segment.ts", "/hls/key")) {
                client.newCall(Request.Builder().url(server.url(path)).header("User-Agent", "playlist-player")
                    .header("Range", "bytes=0-1023").build()).execute().use { assertEquals(200, it.code) }
                val request = server.takeRequest()
                assertEquals("hdntl=rotated", request.getHeader("Cookie"))
                assertEquals("playlist-player", request.getHeader("User-Agent"))
                assertEquals("bytes=0-1023", request.getHeader("Range"))
                assertEquals("Basic dXNlcjpwYXNz", request.getHeader("Authorization"))
            }
        }
    }

    @Test fun reproducesChild403AndFixesItWithServerIssuedSessionCookie() {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path == "/master" -> MockResponse().setBody("master")
                        .setHeader("Set-Cookie", "hdntl=valid; Path=/")
                    request.getHeader("Cookie") == "hdntl=valid" -> MockResponse().setBody("segment")
                    else -> MockResponse().setResponseCode(403)
                }
            }
            server.start()
            fun code(client: OkHttpClient, path: String) = client.newCall(Request.Builder()
                .url(server.url(path)).build()).execute().use { it.code }
            val before = OkHttpClient()
            assertEquals(200, code(before, "/master"))
            assertEquals(403, code(before, "/child"))
            val after = OkHttpClient.Builder()
                .addNetworkInterceptor(PlaybackCookieInterceptor(PlaybackCookieJar())).build()
            assertEquals(200, code(after, "/master"))
            assertEquals(200, code(after, "/child"))
        }
    }

    @Test fun expiryDeletionAndReplacementFollowServerAttributes() {
        var now = 1_000L
        val jar = PlaybackCookieJar { now }
        val url = "https://media.example.test/hls/master".toHttpUrl()
        fun cookie(value: String, expiry: Long) = Cookie.Builder().name("hdntl").value(value)
            .hostOnlyDomain(url.host).path("/hls/").expiresAt(expiry).build()
        jar.saveFromResponse(url, listOf(cookie("one", 2_000)))
        jar.saveFromResponse(url, listOf(cookie("two", 3_000)))
        assertEquals(listOf("two"), jar.loadForRequest(url).map { it.value })
        now = 3_000
        assertTrue(jar.loadForRequest(url).isEmpty())
        jar.saveFromResponse(url, listOf(cookie("three", 4_000)))
        jar.saveFromResponse(url, listOf(cookie("deleted", 0)))
        assertTrue(jar.loadForRequest(url).isEmpty())
    }

    @Test fun hostOnlyPathAndSecureRulesPreventSendingCookiesToUnrelatedRequests() {
        val jar = PlaybackCookieJar()
        val origin = "https://media.example.test/hls/master".toHttpUrl()
        jar.saveFromResponse(origin, listOf(Cookie.Builder().name("hdntl").value("session")
            .hostOnlyDomain(origin.host).path("/hls/").secure().build()))
        assertEquals(1, jar.loadForRequest("https://media.example.test/hls/video/1.ts".toHttpUrl()).size)
        for (url in listOf("http://media.example.test/hls/1.ts", "https://media.example.test/other/1.ts",
                "https://other.example.test/hls/1.ts", "https://sub.media.example.test/hls/1.ts",
                "https://media.example.test/hls-other/1.ts")) {
            assertTrue(url, jar.loadForRequest(url.toHttpUrl()).isEmpty())
        }
    }

    @Test fun domainCookiesMatchSubdomainsAndMoreSpecificPathsComeFirst() {
        val jar = PlaybackCookieJar()
        val url = "https://media.example.test/hls/master".toHttpUrl()
        jar.saveFromResponse(url, listOf(
            Cookie.Builder().name("id").value("root").domain("example.test").path("/").build(),
            Cookie.Builder().name("id").value("media").domain("example.test").path("/hls/").build()))
        assertEquals(listOf("media", "root"), jar.loadForRequest("https://cdn.example.test/hls/1".toHttpUrl()).map { it.value })
        assertTrue(jar.loadForRequest("https://example.test.evil/hls/1".toHttpUrl()).isEmpty())
    }

    @Test fun explicitlyConfiguredCookiesArePreservedAlongsideOtherSessionCookies() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("ok"))
            val jar = PlaybackCookieJar()
            val url = server.url("/hls/segment")
            jar.saveFromResponse(url, listOf(
                Cookie.Builder().name("hdntl").value("server").hostOnlyDomain(url.host).build(),
                Cookie.Builder().name("extra").value("retained").hostOnlyDomain(url.host).build()))
            val client = OkHttpClient.Builder().addNetworkInterceptor(PlaybackCookieInterceptor(jar)).build()
            client.newCall(Request.Builder().url(url).header("Cookie", "hdntl=manual; custom=keep")
                .build()).execute().close()
            assertEquals("hdntl=manual; custom=keep; extra=retained", server.takeRequest().getHeader("Cookie"))
        }
    }

    @Test fun newPlaybackAndClearDiscardPreviousSession() {
        val url = "https://media.example.test/master".toHttpUrl()
        val first = PlaybackCookieJar()
        first.saveFromResponse(url, listOf(Cookie.Builder().name("hdntl").value("one")
            .hostOnlyDomain(url.host).build()))
        assertEquals(1, first.loadForRequest(url).size)
        assertTrue(PlaybackCookieJar().loadForRequest(url).isEmpty())
        first.clear()
        assertTrue(first.loadForRequest(url).isEmpty())
    }

    @Test fun cookiesFromRedirectAreNotForwardedToADifferentHost() {
        MockWebServer().use { other -> MockWebServer().use { server ->
            other.start(); server.start()
            server.enqueue(MockResponse().setResponseCode(302)
                .setHeader("Location", other.url("/media").newBuilder().host("127.0.0.1").build())
                .setHeader("Set-Cookie", "hdntl=origin-only; Path=/"))
            other.enqueue(MockResponse().setBody("ok"))
            val client = OkHttpClient.Builder()
                .addNetworkInterceptor(PlaybackCookieInterceptor(PlaybackCookieJar())).build()
            client.newCall(Request.Builder().url(server.url("/entry")).build()).execute().use { assertEquals(200, it.code) }
            assertNull(other.takeRequest().getHeader("Cookie"))
        } }
    }
}
