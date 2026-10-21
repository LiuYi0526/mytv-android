package top.yogiczy.mytv.core.data.network

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class ChannelLineProbeTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var probe: ChannelLineProbe
    private val headersReceived = CountDownLatch(1)
    private val canceled = CountDownLatch(1)
    private val bytesRead = AtomicLong()

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        client = OkHttpClient.Builder()
            .addInterceptor(HttpBasicAuthInterceptor())
            .callTimeout(500, TimeUnit.MILLISECONDS)
            .eventListener(object : EventListener() {
                override fun responseHeadersEnd(call: Call, response: Response) { headersReceived.countDown() }
                override fun responseBodyEnd(call: Call, byteCount: Long) { bytesRead.addAndGet(byteCount) }
                override fun canceled(call: Call) { canceled.countDown() }
            })
            .build()
        probe = ChannelLineProbe(client)
    }

    @After
    fun tearDown() {
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
        server.shutdown()
    }

    @Test(timeout = 5000)
    fun streamingBodyReturnsBeforeEndAndStopsDownloading() = runBlocking<Unit> {
        // Would take over 16 minutes to read fully; the server ignores any Range header.
        server.enqueue(MockResponse().setChunkedBody("x".repeat(1024 * 1024), 1024)
            .throttleBody(1024, 1, TimeUnit.SECONDS))
        assertTrue(probe.measure(server.url("/live").toString()) > 0)
        assertTrue(canceled.await(1, TimeUnit.SECONDS))
        assertTrue("Probe read too much: ${bytesRead.get()}", bytesRead.get() in 1..8192)
        assertEquals("GET", server.takeRequest().method)
    }

    @Test(timeout = 5000)
    fun cancelsWhileWaitingForHeaders() = runBlocking<Unit> {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            probe.measure(server.url("/live").toString())
            fail("Canceled probe returned a result")
        }
        assertNotNull(server.takeRequest(1, TimeUnit.SECONDS))
        withTimeout(1000) { job.cancelAndJoin() }
        assertTrue(canceled.await(1, TimeUnit.SECONDS))
    }

    @Test(timeout = 5000)
    fun cancelsAfterHeadersWhileWaitingForFirstByte() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody("x").setBodyDelay(2, TimeUnit.SECONDS))
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            probe.measure(server.url("/live").toString())
            fail("Canceled probe returned a result")
        }
        assertTrue(headersReceived.await(1, TimeUnit.SECONDS))
        withTimeout(1000) { job.cancelAndJoin() }
        assertTrue(canceled.await(1, TimeUnit.SECONDS))
    }

    @Test(timeout = 5000)
    fun totalTimeoutAlsoCoversBodyReading() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody("x").setBodyDelay(2, TimeUnit.SECONDS))
        try {
            probe.measure(server.url("/live").toString())
            fail("Expected timeout while waiting for body")
        } catch (_: IOException) {
            assertTrue(headersReceived.await(1, TimeUnit.SECONDS))
        }
    }

    @Test(timeout = 5000)
    fun rejectsHttpErrorsAndEmptyBodies() = runBlocking<Unit> {
        listOf(MockResponse().setResponseCode(403).setBody("forbidden"), MockResponse().setBody(""))
            .forEach { response ->
                server.enqueue(response)
                try {
                    probe.measure(server.url("/live").toString())
                    fail("Expected an unavailable line")
                } catch (_: IOException) { }
            }
    }

    @Test(timeout = 5000)
    fun preservesAuthenticationUserAgentAndRedirects() = runBlocking<Unit> {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/live")))
        server.enqueue(MockResponse().setBody("#EXTM3U"))
        val url = server.url("/redirect").newBuilder().username("user").password("pass").build()
        assertTrue(probe.measure(url.toString(), "Custom Player") > 0)
        repeat(2) {
            val request = server.takeRequest()
            assertEquals("Basic dXNlcjpwYXNz", request.getHeader("Authorization"))
            assertEquals("Custom Player", request.getHeader("User-Agent"))
            assertFalse(request.requestLine.contains("user:pass"))
        }
    }
}
