package top.yogiczy.mytv.core.data.repositories.epg

import kotlinx.serialization.Serializable
import okhttp3.Request
import okhttp3.Response
import okio.BufferedSource
import okio.ByteString.Companion.encodeUtf8
import top.yogiczy.mytv.core.data.entities.epg.EpgList

@Serializable
internal data class EpgValidators(
    val resource: String,
    val etag: String? = null,
    val lastModified: String? = null,
    val gzipMtime: Long? = null,
    val contentLength: Long? = null,
) {
    val hasHttpValidator: Boolean get() = etag != null || lastModified != null
    val canValidate: Boolean get() = hasHttpValidator || gzipMtime != null

    fun applyTo(builder: Request.Builder): Request.Builder = when {
        etag != null -> builder.header("If-None-Match", etag)
        lastModified != null -> builder.header("If-Modified-Since", lastModified)
        else -> builder
    }

    fun matchesGzip(current: EpgValidators): Boolean =
        !hasHttpValidator && !current.hasHttpValidator && resource == current.resource &&
            gzipMtime != null && gzipMtime == current.gzipMtime && contentLength == current.contentLength

    companion object {
        fun resourceOf(response: Response): String = response.request.url.toString().encodeUtf8().sha256().hex()

        fun from(response: Response): EpgValidators {
            val etag = response.header("ETag")?.takeIf { it.isNotBlank() }
            val lastModified = response.header("Last-Modified")?.takeIf { it.isNotBlank() }
            val body = checkNotNull(response.body)
            return EpgValidators(
                resource = resourceOf(response),
                etag = etag,
                lastModified = lastModified,
                gzipMtime = if (etag == null && lastModified == null) body.source().gzipMtime() else null,
                contentLength = body.contentLength().takeIf { it >= 0 },
            )
        }
    }
}

/** Peek at the fixed gzip header before the fetcher consumes the source. */
private fun BufferedSource.gzipMtime(): Long? {
    if (!request(3) || buffer[0] != 0x1f.toByte() || buffer[1] != 0x8b.toByte() ||
        buffer[2] != 8.toByte() || !request(10)
    ) return null
    return buffer.peek().run {
        skip(4)
        (readIntLe().toLong() and 0xffffffffL).takeIf { it != 0L }
    }
}

internal sealed interface EpgRefreshResult {
    data object Unchanged : EpgRefreshResult
    data class Updated(val guide: EpgList, val validators: EpgValidators? = null) : EpgRefreshResult
}
