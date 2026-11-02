package top.yogiczy.mytv.core.data.repositories.iptv

import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import top.yogiczy.mytv.core.data.entities.iptvsource.IptvSource
import top.yogiczy.mytv.core.data.entities.iptvsource.IptvSourceUrl
import top.yogiczy.mytv.core.data.network.HttpException
import top.yogiczy.mytv.core.data.network.request
import top.yogiczy.mytv.core.data.repositories.FileCacheRepository
import top.yogiczy.mytv.core.data.utils.Globals
import top.yogiczy.mytv.core.data.utils.Logger

/** Raw downloads belong to the request URL, independently of draft names and parser settings. */
internal class IptvRawRepository(
    private val source: IptvSource,
    private val download: suspend () -> String = { download(source) },
) : FileCacheRepository(cacheFileName(source), source.isLocal) {
    private val file = if (source.isLocal) File(source.url)
        else File(Globals.cacheDir, cacheFileName(source))
    private val legacyFile = File(Globals.cacheDir, source.cacheFileName("txt"))
    private val mutex = synchronized(locks) { locks.getOrPut(file.absolutePath) { Mutex() } }

    val cachedFile: File get() = if (file.exists()) file else legacyFile

    suspend fun getRaw(cacheTime: Long = 0): String = mutex.withLock {
        if (!source.isLocal) withContext(Dispatchers.IO) {
            if (!file.exists() && legacyFile.isFile && legacyFile.length() > 0) {
                legacyFile.copyTo(file, overwrite = false)
                file.setLastModified(legacyFile.lastModified())
            }
        }
        getOrRefresh(if (source.isLocal) Long.MAX_VALUE else cacheTime, download)
    }

    override suspend fun clearCache() {
        if (source.isLocal) return
        mutex.withLock {
            super.clearCache()
            withContext(Dispatchers.IO) { legacyFile.delete() }
        }
    }

    companion object {
        private val locks = mutableMapOf<String, Mutex>()

        private fun cacheFileName(source: IptvSource): String {
            if (source.isLocal) return source.url
            val requestUrl = IptvSourceUrl.parse(source.url).requestUrl
            val digest = MessageDigest.getInstance("SHA-256").digest(requestUrl.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            return "iptv_source_cache/iptv_raw_$digest.txt"
        }

        private suspend fun download(source: IptvSource): String {
            val log = Logger.create("IptvRawRepository")
            log.d("获取直播源（${source.name}）")
            try {
                return IptvSourceUrl.parse(source.url).requestUrl.request { body -> body.string() } ?: ""
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                log.e("获取直播源（${source.name}）失败", ex)
                throw HttpException("获取直播源失败，请检查网络连接", ex)
            }
        }
    }
}
