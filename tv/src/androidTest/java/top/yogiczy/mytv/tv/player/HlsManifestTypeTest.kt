package top.yogiczy.mytv.tv.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsManifest
import androidx.media3.exoplayer.dash.manifest.DashManifest
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.Credentials
import org.junit.Assert.*
import org.junit.Test
import top.yogiczy.mytv.core.data.entities.channel.ChannelLine
import top.yogiczy.mytv.core.data.utils.SP
import top.yogiczy.mytv.tv.ui.screensold.videoplayer.player.Media3VideoPlayer
import top.yogiczy.mytv.tv.ui.screensold.videoplayer.player.VideoPlayer
import top.yogiczy.mytv.tv.ui.utils.Configs
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

/** Real HTTP/source preparation with synthetic tracks; does not claim audio/video decoding. */
@OptIn(UnstableApi::class)
class HlsManifestTypeTest {
    @Test fun explicitHlsOpensExtensionlessPlaylistOnce() = checkPreparation(" HLS ", redirect = false)

    @Test fun m3u8AliasOpensRedirectingEntryOnceAndKeepsCredentialsForKeyAndSegment() =
        checkPreparation("\" M3U8 \"", redirect = true)

    @Test fun m3u8UrlStillAutoDetectsWithoutHint() =
        checkPreparation(null, redirect = false, entryPath = "/entry.m3u8")

    @Test fun extensionlessUrlWithoutHintReusesTheInitialResponse() =
        checkPreparation(null, redirect = false, expectedPrepares = 2)

    @Test fun extensionlessRedirectWithoutHintReusesBodyAndResolvedRelativeUrls() =
        checkPreparation(null, redirect = true, expectedPrepares = 2)

    @Test fun automaticHlsAcceptsGenericContentTypeBomAndWhitespace() =
        checkPreparation(null, redirect = false, expectedPrepares = 2,
            contentType = "text/plain; charset=utf-8", leadingWhitespace = true)

    @Test fun automaticHlsReusesGzippedRedirectResponse() =
        checkPreparation(null, redirect = true, expectedPrepares = 2, gzip = true,
            contentType = "application/vnd.apple.mpegurl")

    @Test fun automaticMasterPlaylistReusesOnlyTheInitialResponse() =
        checkPreparation(null, redirect = true, expectedPrepares = 2, master = true)

    @Test fun automaticCrossOriginRedirectDoesNotLeakUrlCredentialsOrOriginalCookies() =
        checkPreparation(null, redirect = true, expectedPrepares = 2, crossOrigin = true)

    @Test fun livePlaylistRefreshStillUsesNetworkAfterInitialReuse() =
        checkPreparation(null, redirect = true, expectedPrepares = 2, refresh = true, leadingWhitespace = true)

    @Test fun explicitHlsAlsoAcceptsBomWithoutAContainerRetry() =
        checkPreparation("hls", redirect = false, leadingWhitespace = true)

    @Test fun explicitHlsWithUnsupportedSegmentDoesNotRestartAsTheSameContainer() =
        checkPreparation("hls", redirect = false, invalidSegment = true)

    @Test fun ordinaryTsUrlStillPreparesAsProgressiveMedia() =
        checkPreparation(null, redirect = false, entryPath = "/entry.ts", sourceKind = C.CONTENT_TYPE_OTHER)

    @Test fun explicitDashStillPreparesAnExtensionlessManifest() =
        checkPreparation("mpd", redirect = false, sourceKind = C.CONTENT_TYPE_DASH)

    private fun checkPreparation(hint: String?, redirect: Boolean, entryPath: String = "/entry",
        expectedPrepares: Int = 1, invalidSegment: Boolean = false, sourceKind: Int = C.CONTENT_TYPE_HLS,
        contentType: String? = null, leadingWhitespace: Boolean = false, gzip: Boolean = false,
        master: Boolean = false, refresh: Boolean = false, crossOrigin: Boolean = false) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val segment = if (invalidSegment) ByteArray(4096) else
            instrumentation.context.assets.open("aac-av3a.ts").use { it.readBytes() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val completed = CountDownLatch(1)
        val failure = AtomicReference<VideoPlayer.PlaybackException>()
        val manifest = AtomicReference<Any>()
        val prepares = AtomicInteger()
        val prefs = SP.getInstance(instrumentation.context)
        var player: Media3VideoPlayer? = null
        fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
        FixtureServer(segment, redirect, entryPath, sourceKind, contentType, leadingWhitespace, gzip, master, refresh, crossOrigin).use { server ->
            try {
                main {
                    prefs.edit().clear().commit()
                    SP.init(instrumentation.context)
                    Configs.videoPlayerHeaders = "X-Manifest-Test: preserved"
                    Configs.videoPlayerLoadTimeout = 15_000
                    val engine = Media3VideoPlayer(instrumentation.targetContext, scope)
                    player = engine
                    engine.initialize()
                    engine.onPrepared { prepares.incrementAndGet() }
                    engine.onError { if (it != null) { failure.set(it); completed.countDown() } }
                    val field = Media3VideoPlayer::class.java.getDeclaredField("videoPlayer").apply { isAccessible = true }
                    val exo = field.get(engine) as ExoPlayer
                    // The existing synthetic TS fixture has track headers, not decodable frames.
                    exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                        .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true).build()
                    exo.addListener(object : Player.Listener {
                        override fun onTracksChanged(tracks: Tracks) {
                            if (!tracks.isEmpty) {
                                manifest.set(exo.currentManifest)
                                completed.countDown()
                            }
                        }
                    })
                    engine.prepare(ChannelLine(server.url(), httpUserAgent = "ManifestTest/1", manifestType = hint))
                }
                assertTrue("Source preparation timed out", completed.await(20, TimeUnit.SECONDS))
                if (refresh) {
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                    while (System.nanoTime() < deadline && synchronized(server.requests) {
                        server.requests.count { it.path == "/nested/playlist" } < 2
                    }) Thread.sleep(20)
                }
                server.failure.get()?.let { throw AssertionError("Fixture server failed", it) }
                if (invalidSegment) assertNotNull("Invalid media should fail", failure.get())
                else {
                    assertNull("Media preparation failed", failure.get())
                    when (sourceKind) {
                        C.CONTENT_TYPE_HLS -> assertTrue(manifest.get() is HlsManifest)
                        C.CONTENT_TYPE_DASH -> assertTrue(manifest.get() is DashManifest)
                        else -> assertNull(manifest.get())
                    }
                }
                assertEquals("Unexpected container re-preparation", expectedPrepares, prepares.get())
                val requests = synchronized(server.requests) { server.requests.toList() }
                val entryRequests = requests.count { it.path == entryPath }
                // Progressive TS may reopen for duration/position probing without changing
                // containers. Only playlist entry requests must be exactly one per prepare.
                if (sourceKind == C.CONTENT_TYPE_OTHER) assertTrue(entryRequests >= 1)
                else assertEquals("Entry was opened again to probe another container", 1, entryRequests)
                if (redirect) {
                    val loads = requests.count { it.path == "/nested/playlist" }
                    if (refresh) assertTrue("A live refresh incorrectly reused stale data", loads >= 2)
                    else assertEquals(1, loads)
                }
                if (master) assertEquals(1, requests.count { it.path == "/nested/variant" })
                val hlsRequests = if (sourceKind == C.CONTENT_TYPE_HLS) 1 else 0
                val mediaPrefix = if (redirect) "/nested/" else "/"
                assertEquals(hlsRequests, requests.count { it.path == "${mediaPrefix}key" })
                assertEquals(hlsRequests, requests.count { it.path == "${mediaPrefix}segment.ts" })
                requests.forEach {
                    assertEquals("GET", it.method)
                    if (crossOrigin && it.path != entryPath) assertNull("URL credentials leaked to another host", it.headers["authorization"])
                    else assertEquals(Credentials.basic("user", "pass"), it.headers["authorization"])
                    assertEquals("ManifestTest/1", it.headers["user-agent"])
                    assertEquals("preserved", it.headers["x-manifest-test"])
                    if (crossOrigin && it.path == "/nested/playlist")
                        assertFalse("Cookie leaked from the original host", it.headers["cookie"].orEmpty().contains("hls_session=ok"))
                    else if (it.path != entryPath)
                        assertTrue("Session cookie missing on ${it.path}", it.headers["cookie"].orEmpty().contains("hls_session=ok"))
                }
            } finally {
                main {
                    player?.release()
                    scope.cancel()
                    prefs.edit().clear().commit()
                    SP.init(instrumentation.targetContext)
                }
            }
        }
    }

    private data class Request(val method: String, val path: String, val headers: Map<String, String>)

    private class FixtureServer(private val segment: ByteArray, private val redirect: Boolean,
        private val entryPath: String, private val sourceKind: Int, private val contentType: String?,
        leadingWhitespace: Boolean, private val gzip: Boolean, private val master: Boolean,
        refresh: Boolean, private val crossOrigin: Boolean) : Closeable {
        private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val requests = Collections.synchronizedList(mutableListOf<Request>())
        val failure = AtomicReference<Throwable>()
        private val key = ByteArray(16) { it.toByte() }
        private val encrypted = Cipher.getInstance("AES/CBC/PKCS5Padding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(ByteArray(16)))
            doFinal(segment)
        }
        private val playlist = """
            #EXTM3U
            #EXT-X-TARGETDURATION:1
            #EXT-X-MEDIA-SEQUENCE:0
            #EXT-X-KEY:METHOD=AES-128,URI="key",IV=0x00000000000000000000000000000000
            #EXTINF:1,
            segment.ts
            ${if (refresh) "" else "#EXT-X-ENDLIST"}
        """.trimIndent().let { (if (leadingWhitespace) "\uFEFF \r\n" else "") + it }.toByteArray()
        private val multivariant = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=128000
            variant
        """.trimIndent().toByteArray()
        private val dash = """
            <MPD xmlns="urn:mpeg:dash:schema:mpd:2011" mediaPresentationDuration="PT60S" minBufferTime="PT2S">
            <Period><AdaptationSet mimeType="video/mp4" codecs="avc1.4d401f">
            <Representation id="video" bandwidth="1000000" width="1280" height="720">
            <BaseURL>unused.mp4</BaseURL><SegmentBase indexRange="100-200"><Initialization range="0-99"/></SegmentBase>
            </Representation></AdaptationSet></Period></MPD>
        """.trimIndent().toByteArray()
        private val worker = thread(name = "hls-manifest-fixture", isDaemon = true) {
            try {
                while (!server.isClosed) server.accept().use { socket ->
                    socket.soTimeout = 5_000
                    val input = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                    val first = requireNotNull(input.readLine()).split(' ')
                    val headers = linkedMapOf<String, String>()
                    while (true) {
                        val line = requireNotNull(input.readLine())
                        if (line.isEmpty()) break
                        headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
                    }
                    val request = Request(first[0], first[1].substringBefore('?'), headers)
                    requests.add(request)
                    val isRedirect = redirect && request.path == entryPath
                    val file = request.path.substringAfterLast('/')
                    val raw = when (file) {
                        "key" -> key
                        "segment.ts" -> encrypted
                        "variant" -> playlist
                        else -> when {
                            isRedirect -> byteArrayOf()
                            sourceKind == C.CONTENT_TYPE_OTHER -> segment
                            sourceKind == C.CONTENT_TYPE_DASH -> dash
                            master -> multivariant
                            else -> playlist
                        }
                    }
                    val compress = gzip && !isRedirect && file !in listOf("key", "segment.ts")
                    val body = if (compress) java.io.ByteArrayOutputStream().also { output ->
                        java.util.zip.GZIPOutputStream(output).use { it.write(raw) }
                    }.toByteArray() else raw
                    val location = if (crossOrigin) "http://localhost:${server.localPort}/nested/playlist?session=first"
                        else "/nested/playlist?session=first"
                    val extra = (if (isRedirect) "Location: $location\r\n" else "") +
                        (if (contentType != null && file !in listOf("key", "segment.ts")) "Content-Type: $contentType\r\n" else "") +
                        (if (compress) "Content-Encoding: gzip\r\n" else "")
                    socket.getOutputStream().apply {
                        write(("HTTP/1.1 ${if (isRedirect) "302 Found" else "200 OK"}\r\n" +
                            "Content-Length: ${body.size}\r\nConnection: close\r\n" +
                            "Set-Cookie: hls_session=ok; Path=/\r\n$extra\r\n").toByteArray(Charsets.ISO_8859_1))
                        write(body)
                        flush()
                    }
                }
            } catch (error: Throwable) {
                if (!server.isClosed) failure.set(error)
            }
        }
        fun url() = "http://user:pass@127.0.0.1:${server.localPort}$entryPath?token=a=b"
        override fun close() { server.close(); worker.join(1_000) }
    }
}
