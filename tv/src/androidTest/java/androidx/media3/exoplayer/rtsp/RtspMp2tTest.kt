package androidx.media3.exoplayer.rtsp

import android.graphics.SurfaceTexture
import android.net.Uri
import android.view.Surface
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.reader.RtpMp2tReader
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Test
import org.junit.runner.RunWith
import top.yogiczy.mytv.tv.ui.screensold.videoplayer.player.av3a.Av3aPayloadReaderFactory
import top.yogiczy.mytv.tv.ui.screensold.videoplayer.player.rtsp.RtspSourceFactory
import java.io.BufferedReader
import java.io.Closeable
import java.io.DataOutputStream
import java.io.InputStreamReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
@OptIn(UnstableApi::class)
class RtspMp2tTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun fixture() = instrumentation.context.assets.open("aac-av3a.ts").use { it.readBytes() }
    private fun sdp(media: String = "m=video 0 MP2T/AVP 33") =
        "v=0\r\no=- 1 1 IN IP4 127.0.0.1\r\ns=Test\r\nt=0 0\r\na=range:npt=0-\r\n$media\r\n"

    @Test fun static33AndMissingControlUseTheSessionUrl() {
        val description = SessionDescriptionParser.parse(sdp()).mediaDescriptionList.single()
        assertEquals("MP2T", description.rtpMapAttribute.mediaEncoding)
        assertEquals(90000, description.rtpMapAttribute.clockRate)
        assertTrue(RtpPayloadFormat.isFormatSupported(description))
        val uri = Uri.parse("rtsp://example.invalid/live?token=a+b")
        val track = RtspMediaTrack(RtspHeaders.Builder().build(), description, uri)
        assertEquals("video/mp2t", track.payloadFormat.format.sampleMimeType)
        assertEquals(uri, track.uri)
    }

    @Test fun explicitDynamicMp2tAndOrdinaryPcmuAreStillRecognized() {
        val dynamic = SessionDescriptionParser.parse(sdp("m=video 0 RTP/AVP 96\r\na=rtpmap:96 MP2T/90000\r\na=control:track1"))
        assertTrue(RtpPayloadFormat.isFormatSupported(dynamic.mediaDescriptionList.single()))
        val pcm = SessionDescriptionParser.parse(sdp("m=audio 0 RTP/AVP 0\r\na=control:track1"))
        assertEquals("PCMU", pcm.mediaDescriptionList.single().rtpMapAttribute.mediaEncoding)
        assertTrue(RtpPayloadFormat.isFormatSupported(pcm.mediaDescriptionList.single()))
    }

    private class Output : ExtractorOutput {
        val formats = mutableMapOf<Int, Format>()
        var samples = 0
        var ended = false
        override fun track(id: Int, type: Int): TrackOutput = object : TrackOutput {
            override fun format(format: Format) { formats[id] = format }
            override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int) =
                input.read(ByteArray(length), 0, length)
            override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) = data.skipBytes(length)
            override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) { samples++ }
        }
        override fun endTracks() { ended = true }
        override fun seekMap(seekMap: SeekMap) {}
    }

    private fun extract(m2ts: Boolean, gap: Boolean = false, chunkSize: Int = 77): Output {
        val ts = fixture()
        val bytes = if (m2ts) ts.asList().chunked(188).flatMap { listOf<Byte>(0, 0, 0, 1) + it }.toByteArray() else ts
        val reader = RtpMp2tReader(Av3aPayloadReaderFactory())
        val out = Output()
        reader.createTracks(out, 0)
        assertFalse("TS tracks must be discovered from PAT/PMT", out.ended)
        var sequence = 65534
        if (gap) {
            reader.consume(ParsableByteArray(bytes.copyOfRange(0, 50)), 0, sequence, false)
            sequence += 2
        }
        bytes.asList().chunked(chunkSize).forEach { chunk ->
            reader.consume(ParsableByteArray(chunk.toByteArray()), 0, sequence++ and 0xffff, false)
        }
        assertEquals(setOf("audio/mp4a-latm", "audio/av3a"), out.formats.values.map { it.sampleMimeType }.toSet())
        assertEquals(2, out.formats.size)
        assertTrue(out.ended)
        assertTrue(out.samples > 0)
        // Seek resets partial packets and the TS timestamp state, retaining track IDs.
        reader.seek(90000, 0)
        val before = out.samples
        reader.consume(ParsableByteArray(bytes), 90000, 1, false)
        assertTrue(out.samples > before)
        return out
    }

    @Test fun ts188FragmentsAndSequenceWrapPreserveBothAudioTracks() { extract(false) }
    @Test fun m2ts192FragmentsStripTheFourBytePrefixes() { extract(true) }
    @Test fun m2tsWaitsForTheLastFourBytesOfTheFirstPacket() { extract(true, chunkSize = 188) }
    @Test fun packetLossDiscardsOnlyTheUnfinishedTsPacket() { extract(false, gap = true) }

    @Test fun rtpBoundariesDoNotFinishAnUnboundedPesPacket() {
        val bytes = fixture().copyOf()
        // Video PES commonly has length 0; use the fixture's PES with that same framing rule.
        for (offset in bytes.indices step 188) {
            val flags = (bytes[offset + 3].toInt() ushr 4) and 3
            var payload = offset + 4
            if (flags and 2 != 0) payload += 1 + (bytes[payload].toInt() and 0xff)
            if (flags and 1 != 0 && payload + 6 <= offset + 188 &&
                bytes[payload] == 0.toByte() && bytes[payload + 1] == 0.toByte() && bytes[payload + 2] == 1.toByte()) {
                bytes[payload + 4] = 0
                bytes[payload + 5] = 0
            }
        }
        val ended = AtomicInteger()
        val consumed = AtomicInteger()
        val delegate = androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory()
        val factory = object : androidx.media3.extractor.ts.TsPayloadReader.Factory by delegate {
            override fun createPayloadReader(type: Int, info: androidx.media3.extractor.ts.TsPayloadReader.EsInfo): androidx.media3.extractor.ts.TsPayloadReader? =
                if (type == 15) androidx.media3.extractor.ts.PesReader(object : androidx.media3.extractor.ts.ElementaryStreamReader {
                    override fun seek() {}
                    override fun createTracks(output: ExtractorOutput, ids: androidx.media3.extractor.ts.TsPayloadReader.TrackIdGenerator) {}
                    override fun packetStarted(timeUs: Long, flags: Int) {}
                    override fun consume(data: ParsableByteArray) { consumed.addAndGet(data.bytesLeft()); data.skipBytes(data.bytesLeft()) }
                    override fun endOfInputReached() { ended.incrementAndGet() }
                }) else delegate.createPayloadReader(type, info)
        }
        val reader = RtpMp2tReader(factory)
        reader.createTracks(Output(), 0)
        bytes.asList().chunked(188).forEachIndexed { sequence, chunk ->
            reader.consume(ParsableByteArray(chunk.toByteArray()), 0, sequence, false)
        }
        assertTrue(consumed.get() > 0)
        assertEquals("An RTP packet ending must not signal video EOF", 0, ended.get())
    }

    /** A bounded local RTSP server; no device subscriptions or preferences are changed. */
    private class Server(private val bytes: ByteArray, private val pcm: Boolean = false,
        private val redirect: Boolean = false, private val dropUdp: Boolean = false,
        private val redirectTo: String? = null) : Closeable {
        private val server = ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"))
        @Volatile private var stopped = false
        private val sockets = java.util.Collections.synchronizedList(mutableListOf<Socket>())
        val failure = AtomicReference<Throwable>()
        val url = "rtsp://127.0.0.1:${server.localPort}/${if (redirect || redirectTo != null) "start" else "live"}"
        @Volatile var sawTcp = false
        @Volatile var sawRedirect = false
        @Volatile var userAgent = ""
        private val worker = thread(name = "RTSP fixture", isDaemon = true) {
            try {
                while (!stopped) {
                    val socket = server.accept()
                    sockets.add(socket)
                    thread(isDaemon = true) {
                        try { connection(socket) } catch (e: Throwable) { if (!stopped) failure.compareAndSet(null, e) }
                    }
                }
            } catch (e: Throwable) { if (!stopped) failure.compareAndSet(null, e) }
        }
        private fun connection(socket: Socket) = socket.use {
            socket.soTimeout = 15000
            val input = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.US_ASCII))
            val output = DataOutputStream(socket.getOutputStream())
            var tcp = false
            var udpPort = 0
            var interleaved = 0
            while (!stopped) {
                val request = input.readLine() ?: return
                val headers = mutableMapOf<String, String>()
                while (true) {
                    val line = input.readLine() ?: return
                    if (line.isEmpty()) break
                    headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
                }
                userAgent = headers["user-agent"].orEmpty()
                val method = request.substringBefore(' ')
                val seq = headers["cseq"].orEmpty()
                fun reply(extra: String = "", body: String = "", status: String = "200 OK") {
                    val encoded = body.toByteArray(Charsets.US_ASCII)
                    output.writeBytes("RTSP/1.0 $status\r\nCSeq: $seq\r\n$extra" +
                        "Content-Length: ${encoded.size}\r\n\r\n")
                    output.write(encoded)
                    output.flush()
                }
                when (method) {
                    "OPTIONS" -> reply("Public: OPTIONS, DESCRIBE, SETUP, PLAY, TEARDOWN, GET_PARAMETER\r\n")
                    "DESCRIBE" -> {
                        if ((redirect || redirectTo != null) && request.contains("/start ")) {
                            sawRedirect = true
                            reply("Location: ${redirectTo ?: "rtsp://127.0.0.1:${server.localPort}/live"}\r\n", status = "302 Moved Temporarily")
                        } else {
                            val media = if (pcm) "m=audio 0 RTP/AVP 0\r\na=control:*" else "m=video 0 MP2T/AVP 33"
                            reply("Content-Type: application/sdp\r\nContent-Base: rtsp://127.0.0.1:${server.localPort}/live\r\n",
                                "v=0\r\no=- 1 1 IN IP4 127.0.0.1\r\ns=Test\r\nt=0 0\r\na=range:npt=0-\r\n$media\r\n")
                        }
                    }
                    "SETUP" -> {
                        val transport = headers["transport"].orEmpty()
                        tcp = transport.contains("TCP")
                        sawTcp = sawTcp || tcp
                        udpPort = Regex("client_port=(\\d+)").find(transport)?.groupValues?.get(1)?.toInt() ?: 0
                        interleaved = Regex("interleaved=(\\d+)").find(transport)?.groupValues?.get(1)?.toInt() ?: 0
                        reply("Session: fixture;timeout=60\r\nTransport: $transport\r\n")
                    }
                    "PLAY" -> {
                        reply("Session: fixture\r\nRange: npt=0-\r\n")
                        if (!tcp && dropUdp) continue
                        DatagramSocket().use { udp ->
                            val chunks = if (pcm) listOf(List(160) { 0x7f.toByte() }) else bytes.asList().chunked(188 * 7)
                            chunks.forEachIndexed { index, payload ->
                                val packet = byteArrayOf(0x80.toByte(), if (pcm) 0 else 33,
                                    (index ushr 8).toByte(), index.toByte(), 0, 0, 0, 0, 0, 0, 0, 1) + payload.toByteArray()
                                if (tcp) {
                                    output.writeByte('$'.code)
                                    output.writeByte(interleaved)
                                    output.writeShort(packet.size)
                                    output.write(packet)
                                    output.flush()
                                } else udp.send(DatagramPacket(packet, packet.size, socket.inetAddress, udpPort))
                            }
                        }
                    }
                    "TEARDOWN" -> {
                        // TCP fallback closes the old RTSP socket without awaiting this reply.
                        try { reply("Session: fixture\r\n") } catch (_: java.net.SocketException) {}
                        return
                    }
                    else -> reply("Session: fixture\r\n")
                }
            }
        }
        override fun close() {
            stopped = true
            server.close()
            synchronized(sockets) { sockets.forEach { it.close() } }
            worker.join(1000)
        }
    }

    private fun prepareFixture(forceTcp: Boolean, pcm: Boolean = false, redirect: Boolean = false,
        fallback: Boolean = false, disableAudio: Boolean = !pcm, target: Server? = null, timeoutMs: Long = 700) {
        Server(fixture(), pcm, redirect, fallback, target?.url).use { server ->
            val latch = CountDownLatch(1)
            val tracks = AtomicReference<Tracks>()
            val error = AtomicReference<PlaybackException>()
            lateinit var player: ExoPlayer
            instrumentation.runOnMainSync {
                player = ExoPlayer.Builder(instrumentation.targetContext).build()
                player.volume = 0f
                if (disableAudio) player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build()
                player.addListener(object : Player.Listener {
                    override fun onTracksChanged(value: Tracks) {
                        if (!value.isEmpty) { tracks.set(value); latch.countDown() }
                    }
                    override fun onPlayerError(value: PlaybackException) { error.set(value); latch.countDown() }
                })
                player.setMediaSource(RtspSourceFactory.create(Uri.parse(server.url), "MyTV RTSP test", timeoutMs, forceTcp))
                player.prepare()
            }
            try {
                assertTrue("RTSP prepare timeout; server=${server.failure.get()}", latch.await(12, TimeUnit.SECONDS))
                assertNull("RTSP player error: ${error.get()?.stackTraceToString()}", error.get())
                val formats = tracks.get().groups.flatMap { group -> (0 until group.length).map { group.getTrackFormat(it).sampleMimeType } }.toSet()
                assertEquals(if (pcm) setOf("audio/g711-mlaw") else setOf("audio/mp4a-latm", "audio/av3a"), formats)
                val mediaServer = target ?: server
                assertEquals("MyTV RTSP test", mediaServer.userAgent)
                if ((forceTcp || fallback) && !(pcm && disableAudio)) {
                    // Ordinary formats are known before SETUP; wait for the transport callback.
                    val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                    while (!mediaServer.sawTcp && System.nanoTime() < until) Thread.sleep(10)
                    assertTrue(mediaServer.sawTcp)
                }
                if (redirect || target != null) assertTrue(server.sawRedirect)
                assertNull(server.failure.get())
                assertNull(mediaServer.failure.get())
            } finally { instrumentation.runOnMainSync { player.release() } }
        }
    }
    @Test fun mp2tTcpDiscoversSeparateAacAndAv3aTracks() { prepareFixture(true) }
    @Test fun mp2tUdpDiscoversSeparateAacAndAv3aTracks() { prepareFixture(false) }
    @Test fun noUdpPacketsFallBackToTcpBeforeTsTracksAreKnown() { prepareFixture(false, fallback = true) }
    @Test fun udpStartupProbeDoesNotWaitForTheFullFifteenSecondTimeout() {
        // prepareFixture itself has a 12-second deadline, shorter than the configured packet timeout.
        prepareFixture(false, fallback = true, timeoutMs = 15000)
    }
    @Test fun redirectedMp2tRetainsSessionAndBothTracks() { prepareFixture(true, redirect = true) }
    @Test fun redirectToAnotherServerReconnectsBeforeDescribe() {
        Server(fixture()).use { target -> prepareFixture(true, target = target) }
    }
    @Test fun ordinaryPcmuRtspStillPrepares() { prepareFixture(true, pcm = true) }
    @Test fun noSelectedTrackDoesNotSendPlayWithoutASession() { prepareFixture(true, pcm = true, disableAudio = true) }

    @Test fun optionalDeviceSourceRendersAFrame() {
        val args = InstrumentationRegistry.getArguments()
        val url = args.getString("rtspUrl")
        assumeNotNull(url)
        val result = CountDownLatch(1)
        val failure = AtomicReference<PlaybackException>()
        val frames = AtomicInteger()
        lateinit var player: ExoPlayer
        lateinit var texture: SurfaceTexture
        lateinit var surface: Surface
        instrumentation.runOnMainSync {
            texture = SurfaceTexture(0)
            surface = Surface(texture)
            player = ExoPlayer.Builder(instrumentation.targetContext).build()
            player.volume = 0f
            player.setVideoFrameMetadataListener { _, _, _, _ -> frames.incrementAndGet() }
            player.setVideoSurface(surface)
            player.addListener(object : Player.Listener {
                override fun onRenderedFirstFrame() { result.countDown() }
                override fun onPlaybackStateChanged(state: Int) { android.util.Log.i("RtspProbe", "state=$state") }
                override fun onTracksChanged(tracks: Tracks) {
                    android.util.Log.i("RtspProbe", "tracks=" + tracks.groups.flatMap { group ->
                        (0 until group.length).map { group.getTrackFormat(it).toString() }
                    })
                }
                override fun onPlayerError(error: PlaybackException) { failure.set(error); result.countDown() }
            })
            player.setMediaSource(RtspMediaSource.Factory().setUserAgent("AndroidXMedia3/1.11.1")
                .setTimeoutMs(args.getString("rtspTimeout")?.toLong() ?: 15000).setForceUseRtpTcp(args.getString("rtspTcp") == "true")
                .setTsPayloadReaderFactory(Av3aPayloadReaderFactory()).setDebugLoggingEnabled(true)
                .createMediaSource(MediaItem.fromUri(Uri.parse(url))))
            player.prepare()
            player.play()
        }
        try {
            assertTrue("No decoded video frame in 25s", result.await(25, TimeUnit.SECONDS))
            assertNull("RTSP playback error: ${failure.get()?.cause}", failure.get())
            if (args.getString("rtspSustain") == "true") {
                val before = frames.get()
                Thread.sleep(8000)
                assertNull("RTSP sustained playback error: ${failure.get()?.cause}", failure.get())
                assertTrue("Video did not continue rendering: ${frames.get() - before} frames", frames.get() - before > 100)
            }
        } finally {
            instrumentation.runOnMainSync { player.release(); surface.release(); texture.release() }
        }
    }
}
