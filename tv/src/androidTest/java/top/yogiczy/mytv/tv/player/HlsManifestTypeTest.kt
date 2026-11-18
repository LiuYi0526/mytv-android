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
import top.yogiczy.mytv.tv.ui.screensold.videoplayer.player.IjkVideoPlayer
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
    @Test fun redirectSuffixOverridesAcrossTwoHopsAndReachesHlsChildrenWithoutProbing() =
        checkPreparation(null, redirect = true, expectedPrepares = 2, master = true,
            headerMode = HeaderMode.REDIRECT, followingPlainChannel = true)

    @Test fun explicitHlsAlsoAcceptsHeadersDeclaredInRedirectLocation() =
        checkPreparation("hls", redirect = true, headerMode = HeaderMode.REDIRECT)

    @Test fun kodiSuffixReachesRedirectMasterKeyAndSegmentWithoutASecondEntryRequest() =
        checkPreparation(null, redirect = true, expectedPrepares = 2, master = true,
            headerMode = HeaderMode.INLINE, followingPlainChannel = true)

    @Test fun explicitHlsAlsoUsesKodiSuffixHeaders() =
        checkPreparation("hls", redirect = false, headerMode = HeaderMode.INLINE)

    @Test fun channelHeadersReachRedirectedHlsKeysAndSegments() =
        checkPreparation("hls", redirect = true, headerMode = HeaderMode.LINE)

    @Test fun autoDetectedMasterKeepsHeadersAndTheNextChannelRestoresGlobalDefaults() =
        checkPreparation(null, redirect = true, expectedPrepares = 2, master = true,
            headerMode = HeaderMode.LINE, followingPlainChannel = true)

    @Test fun channelWithoutOptionsStillUsesGlobalHeaders() =
        checkPreparation("hls", redirect = false, headerMode = HeaderMode.GLOBAL)

    @Test fun ijkSendsChannelHeadersAndResetsThemOnTheNextPrepare() = checkIjkHeaders(false)

    @Test fun ijkUsesKodiSuffixAndClearsItOnTheNextChannel() = checkIjkHeaders(true)

    private fun checkIjkHeaders(inline: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val prefs = SP.getInstance(instrumentation.context)
        val segment = instrumentation.context.assets.open("aac-av3a.ts").use { it.readBytes() }
        var player: IjkVideoPlayer? = null
        fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
        FixtureServer(segment, false, "/entry.ts", C.CONTENT_TYPE_OTHER,
            null, false, false, false, false, false).use { server ->
            try {
                main {
                    prefs.edit().clear().commit()
                    SP.init(instrumentation.context)
                    Configs.videoPlayerHeaders = "referer: https://global.test/\nORIGIN: https://global.test\nX-Manifest-Test: preserved"
                    player = IjkVideoPlayer(instrumentation.targetContext, scope).also { it.initialize() }
                }
                listOf(true, false).forEach { hasOptions ->
                    val marker = if (hasOptions) "/protected.ts" else "/plain.ts"
                    main {
                        val suffix = if (inline && hasOptions) kodiSuffix else ""
                        player!!.prepare(ChannelLine(server.url().substringBefore("/entry.ts") + marker + suffix,
                            httpUserAgent = if (inline && hasOptions) "LowerPriority/1" else "ManifestTest/1",
                            httpReferrer = if (hasOptions) "https://channel.test/" else null,
                            httpOrigin = if (hasOptions) "https://channel.test" else null))
                    }
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                    var captured: Request? = null
                    while (captured == null && System.nanoTime() < deadline) {
                        captured = synchronized(server.requests) { server.requests.firstOrNull { it.path == marker } }
                        if (captured == null) Thread.sleep(20)
                    }
                    assertNotNull("IJK did not request $marker", captured)
                    val site = if (hasOptions) "channel" else "global"
                    assertEquals("https://$site.test/", captured!!.headers["referer"])
                    assertEquals("https://$site.test", captured!!.headers["origin"])
                    assertEquals("preserved", captured!!.headers["x-manifest-test"])
                    assertEquals("ManifestTest/1", captured!!.headers["user-agent"])
                    if (inline) assertEquals(if (hasOptions) "a=b&c+token" else null, captured!!.headers["x-pipe-token"])
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

    private enum class HeaderMode { NONE, LINE, GLOBAL, INLINE, REDIRECT }

    private val kodiSuffix = "|User-Agent=ManifestTest%2F1&Referer=https%3A%2F%2Fchannel.test%2F" +
        "&Origin=https%3A%2F%2Fchannel.test&X-Pipe-Token=a%3Db%26c%2Btoken&Cookie=custom%3Done"

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
        master: Boolean = false, refresh: Boolean = false, crossOrigin: Boolean = false,
        headerMode: HeaderMode = HeaderMode.NONE, followingPlainChannel: Boolean = false) {
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
        FixtureServer(segment, redirect, entryPath, sourceKind, contentType, leadingWhitespace, gzip, master, refresh, crossOrigin,
            redirectOptions = headerMode == HeaderMode.REDIRECT).use { server ->
            try {
                main {
                    prefs.edit().clear().commit()
                    SP.init(instrumentation.context)
                    Configs.videoPlayerHeaders = "X-Manifest-Test: preserved"
                    if (headerMode != HeaderMode.NONE) {
                        Configs.videoPlayerHeaders += "\nreferer: https://global.test/\nORIGIN: https://global.test"
                    }
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
                    engine.prepare(ChannelLine(server.url() + if (headerMode == HeaderMode.INLINE) kodiSuffix else "",
                        httpUserAgent = if (headerMode == HeaderMode.INLINE) "LowerPriority/1" else "ManifestTest/1", manifestType = hint,
                        httpReferrer = if (headerMode == HeaderMode.LINE) "https://channel.test/" else null,
                        httpOrigin = if (headerMode == HeaderMode.LINE) "https://channel.test" else null))
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
                if (headerMode == HeaderMode.REDIRECT) assertEquals(1, requests.count { it.path == "/mid" })
                val hlsRequests = if (sourceKind == C.CONTENT_TYPE_HLS) 1 else 0
                val mediaPrefix = if (redirect) "/nested/" else "/"
                assertEquals(hlsRequests, requests.count { it.path == "${mediaPrefix}key" })
                assertEquals(hlsRequests, requests.count { it.path == "${mediaPrefix}segment.ts" })
                requests.forEach {
                    assertEquals("GET", it.method)
                    if (crossOrigin && it.path != entryPath) assertNull("URL credentials leaked to another host", it.headers["authorization"])
                    else assertEquals(Credentials.basic("user", "pass"), it.headers["authorization"])
                    val isEntry = it.path == entryPath
                    val redirectFirst = it.path == "/mid"
                    val expectedAgent = if (headerMode != HeaderMode.REDIRECT || isEntry) "ManifestTest/1"
                        else if (redirectFirst) "RedirectFirst/1" else "RedirectFinal/1"
                    assertEquals(expectedAgent, it.headers["user-agent"])
                    assertEquals("preserved", it.headers["x-manifest-test"])
                    if (headerMode != HeaderMode.NONE) {
                        val fromRedirect = headerMode == HeaderMode.REDIRECT && !isEntry
                        val site = if (fromRedirect || headerMode in listOf(HeaderMode.LINE, HeaderMode.INLINE)) "channel" else "global"
                        val expectedReferer = if (fromRedirect && !redirectFirst) "https://redirect.test/final" else "https://$site.test/"
                        assertEquals(expectedReferer, it.headers["referer"])
                        assertEquals("https://$site.test", it.headers["origin"])
                    }
                    if (headerMode == HeaderMode.INLINE || (headerMode == HeaderMode.REDIRECT && !isEntry)) {
                        assertEquals("a=b&c+token", it.headers["x-pipe-token"])
                        assertTrue(it.headers["cookie"].orEmpty().contains("custom=one"))
                    }
                    if (headerMode == HeaderMode.REDIRECT && !isEntry && !redirectFirst) {
                        assertEquals("ok", it.headers["x-from-redirect"])
                    }
                    if (crossOrigin && it.path == "/nested/playlist")
                        assertFalse("Cookie leaked from the original host", it.headers["cookie"].orEmpty().contains("hls_session=ok"))
                    else if (it.path != entryPath)
                        assertTrue("Session cookie missing on ${it.path}", it.headers["cookie"].orEmpty().contains("hls_session=ok"))
                }
                if (followingPlainChannel) {
                    main { player!!.stop() }
                    synchronized(server.requests) { server.requests.clear() }
                    val nextUrl = if (headerMode == HeaderMode.REDIRECT) server.url().substringBefore(entryPath) + "/plain" else server.url()
                    main { player!!.prepare(ChannelLine(nextUrl, manifestType = "hls")) }
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                    while (System.nanoTime() < deadline && synchronized(server.requests) {
                        server.requests.none { it.path.endsWith("segment.ts") }
                    }) Thread.sleep(20)
                    val nextRequests = synchronized(server.requests) { server.requests.toList() }
                    assertTrue("Next channel did not load media", nextRequests.any { it.path.endsWith("segment.ts") })
                    nextRequests.forEach {
                        assertEquals("https://global.test/", it.headers["referer"])
                        assertEquals("https://global.test", it.headers["origin"])
                        if (headerMode in listOf(HeaderMode.INLINE, HeaderMode.REDIRECT)) {
                            assertNull(it.headers["x-pipe-token"])
                            assertNull(it.headers["x-from-redirect"])
                            assertFalse(it.headers["cookie"].orEmpty().contains("custom=one"))
                            assertEquals(Configs.videoPlayerUserAgent, it.headers["user-agent"])
                        }
                    }
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
        refresh: Boolean, private val crossOrigin: Boolean, private val redirectOptions: Boolean = false) : Closeable {
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
                    val isRedirect = redirect && (request.path == entryPath || redirectOptions && request.path == "/mid")
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
                    val location = when {
                        redirectOptions && request.path == entryPath -> "/mid?token=a%2Bb==" +
                            "|User-Agent=RedirectFirst%2F1&Referer=https%3A%2F%2Fchannel.test%2F&Origin=https%3A%2F%2Fchannel.test" +
                            "&X-Pipe-Token=a%3Db%26c%2Btoken&Cookie=custom%3Done"
                        redirectOptions -> "/nested/playlist?session=first|user-agent=RedirectFinal%2F1" +
                            "&Referer=https%3A%2F%2Fredirect.test%2Ffinal&X-From-Redirect=ok"
                        crossOrigin -> "http://localhost:${server.localPort}/nested/playlist?session=first"
                        else -> "/nested/playlist?session=first"
                    }
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
