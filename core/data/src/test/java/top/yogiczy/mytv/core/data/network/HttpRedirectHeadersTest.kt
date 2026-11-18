package top.yogiczy.mytv.core.data.network

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.net.ProtocolException
import java.util.concurrent.TimeUnit

class HttpRedirectHeadersTest {
    private fun request(client: OkHttpClient, url: String) = client.newCall(Request.Builder().url(url)
        .header("User-Agent", "Original").header("Referer", "original").build()).execute().use { it.body!!.string() }

    @Test fun relativeAndAbsoluteRedirectsOverrideHeadersWithoutChangingSignedQueries() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location",
                "/mid?token=a%2Bb==|User-Agent=First&Referer=https%3A%2F%2Fsite.test%2F&X-Token=a%26b%3Dc"))
            server.enqueue(MockResponse().setResponseCode(307).setHeader("Location",
                server.url("/final%7Cfile?key=x%2By==").toString() + "|user-agent=Second&!X-Custom=added"))
            server.enqueue(MockResponse().setBody("ready"))
            val client = OkHttpClient.Builder().addNetworkInterceptor(HttpRedirectHeadersInterceptor()).build()
            assertEquals("ready", request(client, server.url("/entry").toString()))
            assertEquals("Original", server.takeRequest().getHeader("User-Agent"))
            val mid = server.takeRequest()
            assertEquals("/mid?token=a%2Bb==", mid.path)
            assertEquals("First", mid.getHeader("User-Agent"))
            val final = server.takeRequest()
            assertEquals("/final%7Cfile?key=x%2By==", final.path)
            assertEquals(listOf("Second"), final.headers.values("User-Agent"))
            assertEquals("https://site.test/", final.getHeader("Referer"))
            assertEquals("a&b=c", final.getHeader("X-Token"))
            assertEquals("added", final.getHeader("X-Custom"))
            assertEquals(3, server.requestCount)
        }
    }

    @Test fun followingMediaRequestsInheritHeadersButANewPlaybackDoesNot() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/manifest|User-Agent=New&Cookie=explicit%3Done&X-Token=secret"))
            repeat(3) { server.enqueue(MockResponse().setBody("ok")) }
            val client = OkHttpClient.Builder().addNetworkInterceptor(HttpRedirectHeadersInterceptor()).build()
            request(client, server.url("/entry").toString())
            server.takeRequest()
            val manifest = server.takeRequest()
            assertEquals("New", manifest.getHeader("User-Agent"))
            request(client, server.url("/segment.ts").toString())
            val segment = server.takeRequest()
            assertEquals("New", segment.getHeader("User-Agent"))
            assertEquals("explicit=one", segment.getHeader("Cookie"))
            assertEquals("secret", segment.getHeader("X-Token"))
            val next = OkHttpClient.Builder().addNetworkInterceptor(HttpRedirectHeadersInterceptor()).build()
            request(next, server.url("/plain").toString())
            val plain = server.takeRequest()
            assertEquals("Original", plain.getHeader("User-Agent"))
            assertNull(plain.getHeader("X-Token"))
            assertNull(plain.getHeader("Cookie"))
        }
    }

    @Test fun redirectCredentialsStayOnTheirDeclaredOrigin() {
        MockWebServer().use { first -> MockWebServer().use { second ->
            first.start(); second.start()
            first.enqueue(MockResponse().setResponseCode(302).setHeader("Location", second.url("/protected").toString() +
                "|User-Agent=New&Authorization=Bearer+redirect&Cookie=id%3Dprivate"))
            second.enqueue(MockResponse().setResponseCode(302).setHeader("Location", first.url("/final")))
            first.enqueue(MockResponse().setBody("ok"))
            val client = OkHttpClient.Builder().addNetworkInterceptor(HttpRedirectHeadersInterceptor()).build()
            request(client, first.url("/entry").toString())
            first.takeRequest()
            val protectedRequest = second.takeRequest()
            assertEquals("Bearer redirect", protectedRequest.getHeader("Authorization"))
            assertEquals("id=private", protectedRequest.getHeader("Cookie"))
            val final = first.takeRequest()
            assertEquals("New", final.getHeader("User-Agent"))
            assertNull(final.getHeader("Authorization"))
            assertNull(final.getHeader("Cookie"))
        } }
    }

    @Test fun nonRedirectLocationsAndInvalidOptionValuesDoNotChangeHeaders() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setHeader("Location", "/unused|User-Agent=Wrong").setBody("one"))
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/final|User-Agent=bad%0D%0AInjected%3Ayes&X-Bad=%ZZ"))
            server.enqueue(MockResponse().setBody("two"))
            val client = OkHttpClient.Builder().addNetworkInterceptor(HttpRedirectHeadersInterceptor()).build()
            assertEquals("one", request(client, server.url("/first").toString()))
            assertEquals("two", request(client, server.url("/second").toString()))
            repeat(2) { server.takeRequest() }
            val final = server.takeRequest()
            assertEquals("/final", final.path)
            assertEquals("Original", final.getHeader("User-Agent"))
            assertNull(final.getHeader("Injected"))
            assertNull(final.getHeader("X-Bad"))
        }
    }

    @Test fun ordinaryRedirectLoopStillUsesTheNetworkClientsLimit() {
        MockWebServer().use { server ->
            server.start()
            repeat(22) { server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/loop|User-Agent=Loop")) }
            val client = OkHttpClient.Builder().callTimeout(3, TimeUnit.SECONDS)
                .addNetworkInterceptor(HttpRedirectHeadersInterceptor()).build()
            try { request(client, server.url("/entry").toString()); fail("Redirect loop was accepted") }
            catch (_: ProtocolException) { assertEquals(21, server.requestCount) }
        }
    }
}
