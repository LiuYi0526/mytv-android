package top.yogiczy.mytv.tv.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.ParserException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import org.junit.Assert.*
import org.junit.Test
import top.yogiczy.mytv.tv.ui.screensold.videoplayer.player.HlsInitialResponse
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InterruptedIOException

@OptIn(UnstableApi::class)
class HlsInitialResponseTest {
    private val uri = Uri.parse("https://example.test/entry?token=first")
    private val finalUri = Uri.parse("https://cdn.test/nested/list?session=first")
    private val spec get() = DataSpec(uri)
    private val playlist = "#EXTM3U\n#EXT-X-TARGETDURATION:1\n#EXTINF:1,\nsegment.ts\n#EXT-X-ENDLIST\n".toByteArray()

    @Test fun fragmentedBomHeaderReusesCompleteBytesFinalUriAndHeadersOnlyOnce() {
        val data = "\uFEFF \r\n".toByteArray() + playlist
        val upstream = MemoryFactory(data, chunkSize = 1)
        val cache = HlsInitialResponse()
        detect(cache, upstream)
        assertEquals(1, upstream.opens)
        assertEquals(1, upstream.closes)
        val refresh = MemoryFactory("fresh response".toByteArray())
        val source = cache.replayingFactory(refresh).createDataSource()
        try {
            assertEquals(data.size.toLong(), source.open(spec))
            assertEquals(finalUri, source.uri)
            assertEquals(listOf("test-etag"), source.responseHeaders["ETag"])
            assertArrayEquals(data, readAll(source))
        } finally { source.close() }
        assertEquals(0, refresh.opens)
        source.open(spec)
        try { assertEquals("fresh response", readAll(source).toString(Charsets.UTF_8)) }
        finally { source.close() }
        assertEquals(1, refresh.opens)
    }

    @Test fun nonHlsBytesArePreservedEvenWithMisleadingContentType() {
        val data = ByteArray(8192) { it.toByte() }
        val upstream = MemoryFactory(data, headers = mapOf("Content-Type" to listOf("application/vnd.apple.mpegurl")))
        val source = HlsInitialResponse().detectingFactory(upstream).createDataSource()
        try {
            assertEquals(data.size.toLong(), source.open(spec))
            assertTrue("Detection read an entire binary stream", upstream.bytesRead <= 256)
            assertEquals(0, source.read(ByteArray(0), 0, 0))
            assertArrayEquals(data, readAll(source))
        } finally { source.close() }
        assertEquals(1, upstream.opens)
    }

    @Test fun oversizedUnknownLengthPlaylistIsNotCachedOrTruncated() {
        val data = playlist + ByteArray(2048) { ' '.code.toByte() }
        val upstream = MemoryFactory(data, unknownLength = true)
        val cache = HlsInitialResponse(maxPlaylistBytes = 512)
        val source = cache.detectingFactory(upstream).createDataSource()
        try {
            assertEquals(C.LENGTH_UNSET.toLong(), source.open(spec))
            assertTrue(upstream.bytesRead <= 513)
            assertArrayEquals(data, readAll(source))
        } finally { source.close() }
        val fresh = MemoryFactory(playlist)
        val retry = cache.replayingFactory(fresh).createDataSource()
        try { retry.open(spec); assertArrayEquals(playlist, readAll(retry)) }
        finally { retry.close() }
        assertEquals(1, fresh.opens)
    }

    @Test fun knownOversizedResponseDoesNotDownloadTheWholePlaylistForDetection() {
        val upstream = MemoryFactory(playlist + ByteArray(2048) { ' '.code.toByte() })
        val source = HlsInitialResponse(512).detectingFactory(upstream).createDataSource()
        try {
            source.open(spec)
            assertTrue(upstream.bytesRead <= 256)
        } finally { source.close() }
    }

    @Test fun changedQueryAndRangeRequestsCannotConsumeTheInitialPlaylist() {
        val cache = HlsInitialResponse()
        detect(cache, MemoryFactory(playlist))
        val upstream = MemoryFactory("range response".toByteArray())
        val factory = cache.replayingFactory(upstream)
        for (other in listOf(DataSpec(Uri.parse("https://example.test/entry?token=other")),
            spec.buildUpon().setPosition(1).build())) {
            val source = factory.createDataSource()
            try { source.open(other); readAll(source) } finally { source.close() }
        }
        assertEquals(2, upstream.opens)
        val original = factory.createDataSource()
        try { original.open(spec); assertArrayEquals(playlist, readAll(original)) }
        finally { original.close() }
        assertEquals(2, upstream.opens)
    }

    @Test fun failedReadDoesNotPublishAPartialPlaylist() {
        val cache = HlsInitialResponse()
        val upstream = MemoryFactory(playlist, failAfter = 16)
        val source = cache.detectingFactory(upstream).createDataSource()
        try {
            try { source.open(spec); fail("Read failure was swallowed") }
            catch (expected: IOException) { assertFalse(expected is ParserException) }
        } finally { source.close() }
        val fresh = MemoryFactory(playlist)
        val retry = cache.replayingFactory(fresh).createDataSource()
        try { retry.open(spec); assertArrayEquals(playlist, readAll(retry)) }
        finally { retry.close() }
        assertEquals(1, fresh.opens)
    }

    @Test fun replacingPlaybackDiscardsPendingResponseAndRejectsLatePublication() {
        val cache = HlsInitialResponse()
        detect(cache, MemoryFactory(playlist))
        cache.close()
        val upstream = MemoryFactory("new session".toByteArray())
        val replay = cache.replayingFactory(upstream).createDataSource()
        try { replay.open(spec); assertEquals("new session", readAll(replay).toString(Charsets.UTF_8)) }
        finally { replay.close() }
        val closedSource = cache.detectingFactory(MemoryFactory(playlist)).createDataSource()
        try {
            try { closedSource.open(spec); fail("A replaced session was read") }
            catch (_: InterruptedIOException) { }
        } finally { closedSource.close() }

        val late = HlsInitialResponse()
        val lateSource = late.detectingFactory(MemoryFactory(playlist, onRead = { late.close() })).createDataSource()
        try {
            try { lateSource.open(spec); fail("An old load published data after replacement") }
            catch (_: InterruptedIOException) { }
        } finally { lateSource.close() }
    }

    @Test fun rangeAndNonHttpInputsBypassDetection() {
        for (input in listOf(spec.buildUpon().setPosition(1).build(), DataSpec(Uri.parse("asset:///list")),
            spec.buildUpon().setLength(16).build(),
            spec.buildUpon().setHttpMethod(DataSpec.HTTP_METHOD_POST).setHttpBody(byteArrayOf(1)).build())) {
            val upstream = MemoryFactory(playlist)
            val source = HlsInitialResponse().detectingFactory(upstream).createDataSource()
            try { source.open(input); assertEquals(0, upstream.bytesRead) }
            finally { source.close() }
        }
    }

    @Test fun partialHttpResponseIsNotCapturedAsACompletePlaylist() {
        val upstream = MemoryFactory(playlist, headers = mapOf("Content-Range" to listOf("bytes 0-63/1024")))
        val source = HlsInitialResponse().detectingFactory(upstream).createDataSource()
        try { source.open(spec); assertEquals(0, upstream.bytesRead) }
        finally { source.close() }
    }

    private fun detect(cache: HlsInitialResponse, upstream: MemoryFactory) {
        val source = cache.detectingFactory(upstream).createDataSource()
        try {
            try { source.open(spec); fail("HLS was not recognized") }
            catch (expected: ParserException) { assertFalse(expected.contentIsMalformed) }
        } finally { source.close() }
    }

    private fun readAll(source: DataSource): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(256)
        while (true) {
            val count = source.read(buffer, 0, buffer.size)
            if (count == C.RESULT_END_OF_INPUT) return output.toByteArray()
            output.write(buffer, 0, count)
        }
    }

    private inner class MemoryFactory(private val data: ByteArray, private val chunkSize: Int = Int.MAX_VALUE,
        private val headers: Map<String, List<String>> = mapOf("ETag" to listOf("test-etag")),
        private val unknownLength: Boolean = false, private val failAfter: Int = Int.MAX_VALUE,
        private val onRead: () -> Unit = {}) : DataSource.Factory {
        var opens = 0
        var closes = 0
        var bytesRead = 0
        override fun createDataSource(): DataSource {
            val delegate = ByteArrayDataSource(data)
            return object : DataSource by delegate {
                override fun open(dataSpec: DataSpec): Long {
                    opens++
                    val length = delegate.open(dataSpec)
                    return if (unknownLength) C.LENGTH_UNSET.toLong() else length
                }
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    onRead()
                    if (bytesRead >= failAfter) throw IOException("test read failure")
                    return delegate.read(buffer, offset, minOf(length, chunkSize)).also {
                        if (it > 0) bytesRead += it
                    }
                }
                override fun getUri(): Uri = finalUri
                override fun getResponseHeaders() = headers
                override fun close() { closes++; delegate.close() }
            }
        }
    }
}
