package top.yogiczy.mytv.core.data.repositories.epg.fetcher

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ResponseBody

/**
 * 节目单xml.gz数据获取
 */
class XmlGzEpgFetcher : EpgFetcher {
    override fun isSupport(url: String): Boolean {
        return url.substringBefore('?').substringBefore('#').endsWith(".gz", ignoreCase = true)
    }

    override suspend fun fetch(body: ResponseBody) = withContext(Dispatchers.IO) {
        DefaultEpgFetcher().fetch(body)
    }
}
