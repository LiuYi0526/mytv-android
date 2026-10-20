package top.yogiczy.mytv.core.data.network

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList

class HttpBasicAuthTest {
    private val authorization = "Basic dXNlcjpwYXNz"
    private val requests = CopyOnWriteArrayList<Pair<String, String?>>()
    private lateinit var server: MockWebServer
    private lateinit var otherServer: MockWebServer
    private val baseUrl get() = "http://127.0.0.1:${server.port}"
    private val authenticatedBaseUrl get() = "http://user:pass@127.0.0.1:${server.port}"

    @Before
    fun startServers() {
        otherServer = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests.add("other" to request.getHeader("Authorization"))
                    return MockResponse().setBody("other")
                }
            }
            start()
        }
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path!!
                    val auth = request.getHeader("Authorization")
                    requests.add(path to auth)
                    return when {
                        path == "/public" -> MockResponse().setBody("public")
                        path == "/custom" && auth == "Bearer custom" -> MockResponse().setBody("custom")
                        auth != authorization -> MockResponse().setResponseCode(401)
                            .setHeader("WWW-Authenticate", "Basic realm=\"test\"")
                            .setBody("unauthorized")
                        path == "/redirect-same" -> MockResponse().setResponseCode(302)
                            .setHeader("Location", "$baseUrl/protected")
                        path == "/redirect-other" -> MockResponse().setResponseCode(302)
                            .setHeader("Location", "http://127.0.0.1:${otherServer.port}/public")
                        else -> MockResponse().setBody("authenticated")
                    }
                }
            }
            start()
        }
    }

    @After
    fun stopServers() {
        server.shutdown()
        otherServer.shutdown()
    }

    @Test
    fun parsesCredentialsAndPreservesPathPortAndQuery() {
        val auth = HttpBasicAuth.parse("https://user:pass@[::1]:8443/list.m3u?token=a%2Bb")!!
        assertEquals("https://[::1]:8443/list.m3u?token=a%2Bb", auth.url.toString())
        assertEquals(authorization, auth.authorization)
    }

    @Test
    fun decodesUserInfoWithoutConvertingPlusToSpace() {
        val auth = HttpBasicAuth.parse("https://us%65r:pa%40ss%3Aword+%25@example.com/a")!!
        val expected = Base64.getEncoder().encodeToString("user:pa@ss:word+%".toByteArray())
        assertEquals("Basic $expected", auth.authorization)
        assertEquals("https://us%65r:pa%40ss%3Aword%2B%25@example.com/a", auth.urlWithEncodedCredentials)
    }

    @Test
    fun supportsUtf8AndEmptyPassword() {
        val auth = HttpBasicAuth.parse("https://用户:密码@example.com/a")!!
        val expected = Base64.getEncoder().encodeToString("用户:密码".toByteArray(Charsets.UTF_8))
        assertEquals("Basic $expected", auth.authorization)
        assertEquals("Basic dXNlcjo=", HttpBasicAuth.parse("http://user:@example.com")!!.authorization)
        assertEquals("Basic OnBhc3M=", HttpBasicAuth.parse("http://:pass@example.com")!!.authorization)
    }

    @Test
    fun ignoresOrdinaryAndNonHttpUrls() {
        assertNull(HttpBasicAuth.parse("http://example.com/path?email=user@example.com"))
        assertNull(HttpBasicAuth.parse("rtsp://user:pass@example.com/live"))
        assertNull(HttpBasicAuth.parse("file:///storage/list.m3u"))
    }

    @Test
    fun sharedRequestAuthenticatesAndRemovesUserInfo() = runBlocking {
        val result = "$authenticatedBaseUrl/protected".request { response, request ->
            assertTrue(request.url.username.isEmpty())
            assertTrue(request.url.password.isEmpty())
            response.body!!.string()
        }
        assertEquals("authenticated", result)
        assertEquals(listOf("/protected" to authorization), requests)
    }

    @Test
    fun sharedRequestPreservesExplicitAuthorizationAndUnauthenticatedRequests() = runBlocking {
        assertEquals("custom", "$authenticatedBaseUrl/custom".request(
            { it.header("authorization", "Bearer custom") },
        ) { body -> body.string() })
        assertEquals("public", "$baseUrl/public".request { body -> body.string() })
        assertEquals(listOf("/custom" to "Bearer custom", "/public" to null), requests)
    }

    @Test
    fun followsSameOriginRedirectWithAuthentication() = runBlocking {
        assertEquals("authenticated", "$authenticatedBaseUrl/redirect-same".request { body -> body.string() })
        assertEquals(listOf("/redirect-same" to authorization, "/protected" to authorization), requests)
    }

    @Test
    fun doesNotForwardCredentialsAcrossRedirectOrigins() = runBlocking {
        assertEquals("other", "$authenticatedBaseUrl/redirect-other".request { body -> body.string() })
        assertEquals(listOf("/redirect-other" to authorization, "other" to null), requests)
    }

    @Test
    fun mediaRequestsAuthenticateRelativeSegmentsAndKeysOnlyOnSameOrigin() {
        val client = OkHttpClient.Builder()
            .addInterceptor(HttpBasicAuthInterceptor(HttpBasicAuth.parse("$authenticatedBaseUrl/live.m3u8")))
            .build()
        try {
            listOf("$baseUrl/live.m3u8", "$baseUrl/segment.ts", "$baseUrl/key").forEach { url ->
                client.newCall(Request.Builder().url(url).build()).execute().use {
                    assertEquals(200, it.code)
                }
            }
            client.newCall(Request.Builder().url("http://127.0.0.1:${otherServer.port}/public").build())
                .execute().use { assertEquals(200, it.code) }
            client.newCall(Request.Builder().url("$baseUrl/redirect-other").build())
                .execute().use { assertEquals(200, it.code) }
            assertEquals(
                listOf(
                    "/live.m3u8" to authorization, "/segment.ts" to authorization,
                    "/key" to authorization, "other" to null,
                    "/redirect-other" to authorization, "other" to null,
                ),
                requests,
            )
        } finally {
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }

    @Test
    fun mediaCredentialsDoNotCrossSchemeOrPortBoundaries() {
        val auth = HttpBasicAuth.parse("https://user:pass@example.com:8443/live.m3u8")!!
        assertTrue(auth.hasSameOrigin(Request.Builder().url("https://example.com:8443/key").build().url))
        assertTrue(!auth.hasSameOrigin(Request.Builder().url("https://example.com/key").build().url))
        assertTrue(!auth.hasSameOrigin(Request.Builder().url("http://example.com:8443/key").build().url))
    }

}
