package top.yogiczy.mytv.tv.player

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.drm.DefaultDrmSessionManagerProvider
import androidx.media3.exoplayer.drm.ExoMediaDrm
import androidx.media3.exoplayer.drm.MediaDrmCallback
import okhttp3.Credentials
import org.junit.Assert.*
import org.junit.Test
import top.yogiczy.mytv.tv.ui.screensold.videoplayer.player.WidevineDrm
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

@androidx.annotation.OptIn(UnstableApi::class)
class WidevineDrmTest {
    private val challenge = byteArrayOf(0, -1, 12, -128, 0, 45)
    private val license = byteArrayOf(-1, 0, -128, 9, 7)

    // Exercise the callback assembled by the real provider, without a CDM/device-specific challenge.
    // Reflection is confined to tests because the provider does not expose this transport boundary.
    private fun callback(provider: DefaultDrmSessionManagerProvider, configuration: MediaItem.DrmConfiguration): MediaDrmCallback {
        val item = MediaItem.Builder().setUri("https://media.test/stream.mpd")
            .setDrmConfiguration(configuration).build()
        val manager = provider.get(item)
        return manager.javaClass.getDeclaredField("callback").apply { isAccessible = true }
            .get(manager) as MediaDrmCallback
    }

    private fun callback(url: String, headers: Map<String, String> = emptyMap()): MediaDrmCallback =
        callback(WidevineDrm.sessionManagerProvider("PlayerTest/1", headers, 5_000),
            WidevineDrm.configuration(url, headers))

    private fun request(callback: MediaDrmCallback) = callback.executeKeyRequest(C.WIDEVINE_UUID,
        ExoMediaDrm.KeyRequest(challenge, "https://upstream.test/embedded-license"))

    @Test fun configurationKeepsExistingSessionPolicyAndPlaylistLicensePriority() {
        val configuration = WidevineDrm.configuration(" \"https://example.test/license?s=wv\" ",
            mapOf("X-License" to "value"))
        assertEquals(C.WIDEVINE_UUID, configuration.scheme)
        assertEquals("https://example.test/license?s=wv", configuration.licenseUri.toString())
        assertTrue(configuration.forceDefaultLicenseUri)
        assertTrue(configuration.multiSession)
        assertTrue(configuration.playClearContentWithoutKey)
        assertEquals(mapOf("X-License" to "value"), configuration.licenseRequestHeaders)
    }

    @Test fun officialProviderPostsOpaqueChallengeToPlaylistProxyAndReturnsOpaqueLicense() {
        var captured: DataSpec? = null
        val factory = DataSource.Factory {
            val delegate = ByteArrayDataSource(license)
            object : DataSource by delegate {
                override fun open(dataSpec: DataSpec): Long {
                    captured = dataSpec
                    return delegate.open(dataSpec)
                }
            }
        }
        val url = "http://example.test/singtelcast/license?s=wv&token=a=b=="
        val provider = DefaultDrmSessionManagerProvider().apply { setDrmHttpDataSourceFactory(factory) }
        val response = request(callback(provider, WidevineDrm.configuration(url, mapOf("User-Agent" to "PlayerTest/1"))))
        assertArrayEquals(license, response.data)
        assertEquals(DataSpec.HTTP_METHOD_POST, captured!!.httpMethod)
        assertEquals(url, captured!!.uri.toString())
        assertArrayEquals(challenge, captured!!.httpBody)
        assertEquals("application/octet-stream", captured!!.httpRequestHeaders["Content-Type"])
        assertEquals("PlayerTest/1", captured!!.httpRequestHeaders["User-Agent"])
    }

    @Test fun acceptsWidevineNamesButDoesNotTreatClearKeyAsWidevine() {
        listOf("com.widevine.alpha", " WIDEVINE ", "\"com.widevine.alpha\"").forEach {
            assertTrue(WidevineDrm.isSupported(it))
        }
        listOf(null, "clearkey", "org.w3.clearkey", "com.microsoft.playready").forEach {
            assertFalse(WidevineDrm.isSupported(it))
        }
    }

    @Test fun invalidLicenseLocationsAreRejectedBeforePlayback() {
        listOf(null, "", "file:///license", "001122:334455").forEach {
            assertTrue(runCatching { WidevineDrm.configuration(it, emptyMap()) }.isFailure)
        }
    }

    @Test fun realHttpTransportKeepsUrlAuthenticationUaQueryAndCustomHeaders() {
        LicenseServer(license).use { server ->
            val response = request(callback(server.url("user:pass@"), mapOf("X-License" to "custom")))
            assertArrayEquals(license, response.data)
            val sent = server.take()
            assertEquals("POST", sent.method)
            assertEquals("/license?s=wv&token=a=b==", sent.path)
            assertArrayEquals(challenge, sent.body)
            assertEquals(Credentials.basic("user", "pass"), sent.headers["authorization"])
            assertEquals("PlayerTest/1", sent.headers["user-agent"])
            assertEquals("custom", sent.headers["x-license"])
            assertEquals("application/octet-stream", sent.headers["content-type"])
        }
    }

    @Test fun explicitAuthorizationAndCustomHeadersKeepExistingUaOrder() {
        LicenseServer(license).use { server ->
            request(callback(server.url("user:pass@"), mapOf("Authorization" to "Bearer explicit",
                "User-Agent" to "ExplicitAgent/2", "Content-Type" to "application/custom", "Cookie" to "explicit=1")))
            val sent = server.take()
            assertEquals("Bearer explicit", sent.headers["authorization"])
            // Preserve the old wire behavior: OkHttpDataSource appends the player UA after
            // an explicitly configured same-name header. Do not silently change this in a refactor.
            assertEquals(listOf("ExplicitAgent/2", "PlayerTest/1"), sent.headerValues["user-agent"])
            assertEquals("application/custom", sent.headers["content-type"])
            assertEquals("explicit=1", sent.headers["cookie"])
        }
    }

    @Test fun licenseRequestsDoNotStartPersistingSessionCookies() {
        LicenseServer(license, mapOf("Set-Cookie" to "session=license; Path=/")).use { server ->
            val callback = callback(server.url())
            repeat(2) {
                request(callback)
                assertNull(server.take().headers["cookie"])
            }
        }
    }

    @Test fun provisioningKeepsItsOwnUrlAndLicenseTransportHeaders() {
        LicenseServer(license).use { server ->
            val callback = callback("https://playlist-proxy.test/license", mapOf("X-License" to "custom"))
            val response = callback.executeProvisionRequest(C.WIDEVINE_UUID,
                ExoMediaDrm.ProvisionRequest("signed".toByteArray(), server.url()))
            assertArrayEquals(license, response.data)
            val sent = server.take()
            assertEquals("POST", sent.method)
            assertEquals("/license?s=wv&token=a=b==", sent.path)
            assertEquals("PlayerTest/1", sent.headers["user-agent"])
            assertEquals("custom", sent.headers["x-license"])
            assertEquals("{\"signedRequest\":\"signed\"}", sent.body.toString(Charsets.UTF_8))
        }
    }

    private data class Request(val method: String, val path: String,
        val headerValues: Map<String, List<String>>, val body: ByteArray) {
        val headers: Map<String, String> get() = headerValues.mapValues { it.value.last() }
    }

    private class LicenseServer(private val response: ByteArray,
        private val responseHeaders: Map<String, String> = emptyMap()) : Closeable {
        private val server = ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"))
        private val requests = LinkedBlockingQueue<Request>()
        private val worker = thread(name = "widevine-test-license", isDaemon = true) {
            try {
                while (!server.isClosed) server.accept().use { socket ->
                    socket.soTimeout = 5_000
                    val input = socket.getInputStream()
                    fun line(): String {
                        val data = java.io.ByteArrayOutputStream()
                        while (true) {
                            val next = input.read()
                            check(next != -1) { "Unexpected end of HTTP request" }
                            if (next == 10) return data.toString("ISO-8859-1").trimEnd('\r')
                            data.write(next)
                        }
                    }
                    val first = line().split(' ')
                    val headers = linkedMapOf<String, MutableList<String>>()
                    while (true) {
                        val value = line()
                        if (value.isEmpty()) break
                        headers.getOrPut(value.substringBefore(':').lowercase()) { mutableListOf() }
                            .add(value.substringAfter(':').trim())
                    }
                    val body = ByteArray(headers["content-length"]?.last()?.toInt() ?: 0)
                    var position = 0
                    while (position < body.size) {
                        val count = input.read(body, position, body.size - position)
                        check(count > 0)
                        position += count
                    }
                    requests.put(Request(first[0], first[1], headers, body))
                    val extra = responseHeaders.entries.joinToString("") { "${it.key}: ${it.value}\r\n" }
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Length: ${response.size}\r\nConnection: close\r\n$extra\r\n".toByteArray())
                        write(response)
                        flush()
                    }
                }
            } catch (error: java.net.SocketException) {
                if (!server.isClosed) throw error
            }
        }
        fun url(credentials: String = "") = "http://${credentials}127.0.0.1:${server.localPort}/license?s=wv&token=a=b=="
        fun take(): Request = requireNotNull(requests.poll(5, TimeUnit.SECONDS)) { "No license request received" }
        override fun close() { server.close(); worker.join(1_000) }
    }
}
