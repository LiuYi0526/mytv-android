package top.yogiczy.mytv.tv.ui.screensold.videoplayer.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.ParserException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.ByteArrayOutputStream
import java.io.InterruptedIOException

/** One bounded initial playlist, scoped to a playback. Never caches segments or later refreshes. */
@OptIn(UnstableApi::class)
internal class HlsInitialResponse(private val maxPlaylistBytes: Int = 2 * 1024 * 1024) : AutoCloseable {
    private data class Response(val requestUri: Uri, val finalUri: Uri,
        val headers: Map<String, List<String>>, val bytes: ByteArray)

    private var pending: Response? = null
    @Volatile private var closed = false

    @Synchronized private fun save(response: Response) {
        if (closed) throw InterruptedIOException("Playback was replaced")
        pending = response
    }

    @Synchronized private fun take(spec: DataSpec): Response? {
        val response = pending ?: return null
        if (!isInitialHttpGet(spec) || spec.uri != response.requestUri) return null
        pending = null
        return response
    }

    @Synchronized override fun close() {
        closed = true
        pending = null
    }

    fun detectingFactory(upstream: DataSource.Factory) = DataSource.Factory {
        DetectingDataSource(upstream.createDataSource())
    }

    fun replayingFactory(upstream: DataSource.Factory) = DataSource.Factory {
        ReplayingDataSource(upstream.createDataSource())
    }

    private inner class DetectingDataSource(private val upstream: DataSource) : DataSource by upstream {
        private var prefix = byteArrayOf()
        private var prefixPosition = 0

        override fun open(spec: DataSpec): Long {
            if (closed) throw InterruptedIOException("Playback was replaced")
            prefix = byteArrayOf()
            prefixPosition = 0
            val detect = isInitialHttpGet(spec)
            // Progressive loads normally request identity encoding. Permit OkHttp's existing
            // transparent gzip handling so detection sees the same decoded bytes as HLS.
            val request = if (detect) spec.buildUpon().setFlags(spec.flags or DataSpec.FLAG_ALLOW_GZIP).build() else spec
            val length = upstream.open(request)
            if (!detect || upstream.responseHeaders.any { (name, values) ->
                name.equals("Content-Range", true) && values.isNotEmpty()
            }) return length

            // Read only enough to identify the header, retaining every byte for non-HLS media.
            val start = ByteArrayOutputStream()
            val chunk = ByteArray(16)
            while (start.size() < 256) {
                val count = upstream.read(chunk, 0, minOf(chunk.size, 256 - start.size()))
                if (count == C.RESULT_END_OF_INPUT) break
                start.write(chunk, 0, count)
                if (start.size() < 3) continue // UTF-8 BOM may be split across reads.
                val text = start.toByteArray().toString(Charsets.UTF_8).removePrefix("\uFEFF").trimStart()
                if (text.length >= 8 || (text.isNotEmpty() && !"#EXTM3U".startsWith(text))) break
            }
            prefix = start.toByteArray()
            val header = prefix.toString(Charsets.UTF_8).removePrefix("\uFEFF").trimStart()
            val isHls = header.startsWith("#EXTM3U") &&
                (header.length == 7 || header[7].isWhitespace())
            if (!isHls || length > maxPlaylistBytes) return length

            // A bad/missing Content-Type is common; verify the body rather than trusting it.
            // Oversized or unfinished responses retain the ordinary fallback, not a partial cache.
            val body = ByteArrayOutputStream().apply { write(prefix) }
            val buffer = ByteArray(8192)
            while (body.size() <= maxPlaylistBytes) {
                if (closed || Thread.currentThread().isInterrupted) throw InterruptedIOException()
                val count = upstream.read(buffer, 0, minOf(buffer.size, maxPlaylistBytes + 1 - body.size()))
                if (count == C.RESULT_END_OF_INPUT) {
                    save(Response(spec.uri, upstream.uri ?: spec.uri,
                        upstream.responseHeaders.mapValues { it.value.toList() }, body.toByteArray()))
                    // The existing container fallback switches to HLS without reopening HTTP.
                    throw ParserException.createForUnsupportedContainerFeature("Detected HLS playlist")
                }
                body.write(buffer, 0, count)
            }
            prefix = body.toByteArray()
            return length
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (prefixPosition < prefix.size) {
                val count = minOf(length, prefix.size - prefixPosition)
                prefix.copyInto(buffer, offset, prefixPosition, prefixPosition + count)
                prefixPosition += count
                return count
            }
            return upstream.read(buffer, offset, length)
        }

        override fun close() {
            prefix = byteArrayOf()
            prefixPosition = 0
            upstream.close()
        }
    }

    private inner class ReplayingDataSource(private val upstream: DataSource) : DataSource by upstream {
        private val listeners = mutableListOf<TransferListener>()
        private var response: Response? = null
        private var memory: ByteArrayDataSource? = null

        override fun addTransferListener(listener: TransferListener) {
            upstream.addTransferListener(listener)
            listeners.add(listener)
            memory?.addTransferListener(listener)
        }

        override fun open(spec: DataSpec): Long {
            val cached = take(spec) ?: return upstream.open(spec)
            response = cached
            val source = ByteArrayDataSource(cached.bytes)
            memory = source
            listeners.forEach(source::addTransferListener)
            return source.open(spec.buildUpon().setUri(cached.finalUri).build())
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            memory?.read(buffer, offset, length) ?: upstream.read(buffer, offset, length)

        override fun getUri(): Uri? = response?.finalUri ?: upstream.uri

        override fun getResponseHeaders(): Map<String, List<String>> = response?.headers ?: upstream.responseHeaders

        override fun close() {
            try {
                if (memory != null) memory?.close() else upstream.close()
            } finally {
                memory = null
                response = null
            }
        }
    }

    private fun isInitialHttpGet(spec: DataSpec) = spec.position == 0L &&
        spec.length == C.LENGTH_UNSET.toLong() &&
        spec.httpMethod == DataSpec.HTTP_METHOD_GET && spec.httpBody == null &&
        (spec.uri.scheme.equals("http", true) || spec.uri.scheme.equals("https", true))
}
