package top.yogiczy.mytv.core.data.repositories.epg

import okhttp3.ResponseBody
import okio.BufferedSource
import okio.Buffer
import okio.ForwardingSource
import okio.buffer

data class EpgUpdateProgress(
    val stage: Stage = Stage.PREPARING,
    val bytesRead: Long = 0,
    val totalBytes: Long? = null,
) {
    enum class Stage { PREPARING, DOWNLOADING, PARSING, SAVING, LOADING, COMPLETE }
}

/** Counts the HTTP payload before XML/GZIP sniffing or decompression. */
internal class EpgProgressResponseBody(
    private val delegate: ResponseBody,
    onProgress: (EpgUpdateProgress) -> Unit,
) : ResponseBody() {
    private val total = delegate.contentLength().takeIf { it > 0 }
    private var bytesRead = 0L
    private var lastReport = 0L
    private val trackedSource = object : ForwardingSource(delegate.source()) {
        override fun read(sink: Buffer, byteCount: Long): Long {
            val read = super.read(sink, byteCount)
            if (read > 0) bytesRead += read
            val now = System.nanoTime()
            if (read == -1L || bytesRead == total || now - lastReport >= 100_000_000L) {
                lastReport = now
                onProgress(EpgUpdateProgress(EpgUpdateProgress.Stage.DOWNLOADING, bytesRead, total))
            }
            return read
        }
    }.buffer()

    init { onProgress(EpgUpdateProgress(EpgUpdateProgress.Stage.DOWNLOADING, totalBytes = total)) }

    override fun contentType() = delegate.contentType()
    override fun contentLength() = delegate.contentLength()
    override fun source(): BufferedSource = trackedSource
}
