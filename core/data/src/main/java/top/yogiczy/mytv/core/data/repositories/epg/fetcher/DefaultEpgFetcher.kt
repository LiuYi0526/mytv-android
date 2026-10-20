package top.yogiczy.mytv.core.data.repositories.epg.fetcher

import okhttp3.ResponseBody
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * 缺省节目单数据获取
 */
class DefaultEpgFetcher : EpgFetcher {
    override fun isSupport(url: String): Boolean {
        return true
    }

    override suspend fun fetch(body: ResponseBody): InputStream {
        val input = body.byteStream().buffered()
        input.mark(2)
        val gzip = input.read() == 0x1f && input.read() == 0x8b
        input.reset()
        return if (gzip) GZIPInputStream(input) else input
    }
}
