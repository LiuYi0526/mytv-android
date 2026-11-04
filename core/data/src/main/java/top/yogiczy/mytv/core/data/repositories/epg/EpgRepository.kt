package top.yogiczy.mytv.core.data.repositories.epg

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import top.yogiczy.mytv.core.data.entities.epg.EpgList
import top.yogiczy.mytv.core.data.entities.epgsource.EpgSource
import top.yogiczy.mytv.core.data.entities.epgsource.EpgSourceCacheInfo
import top.yogiczy.mytv.core.data.network.HttpException
import top.yogiczy.mytv.core.data.network.request
import top.yogiczy.mytv.core.data.repositories.FileCacheRepository
import top.yogiczy.mytv.core.data.repositories.epg.fetcher.EpgFetcher
import top.yogiczy.mytv.core.data.utils.Globals
import top.yogiczy.mytv.core.data.utils.Logger
import java.io.InputStream
import java.io.File
import kotlin.time.measureTimedValue

/**
 * 节目单获取
 */
class EpgRepository(private val source: EpgSource) :
    FileCacheRepository(source.cacheFileName(PARSED_CACHE_EXTENSION)) {

    private val log = Logger.create("EpgRepository")
    private val epgXmlRepository = EpgXmlRepository(source)

    suspend fun getCachedInfo(): EpgSourceCacheInfo? = withContext(Dispatchers.IO) {
        EpgSourceCacheInfo.read(
            File(Globals.cacheDir, source.cacheFileName(PARSED_CACHE_EXTENSION)),
            File(Globals.cacheDir, source.cacheFileName("xml")),
        )
    }

    private suspend fun refresh(onProgress: (EpgUpdateProgress) -> Unit): String {
        onProgress(EpgUpdateProgress())
        val xml = epgXmlRepository.getXml(onProgress)
        onProgress(EpgUpdateProgress(EpgUpdateProgress.Stage.PARSING))
        val epgList = xml.use { EpgParser.fromXml(it) }
        if (epgList.isEmpty()) throw Exception("获取节目单为空")

        onProgress(EpgUpdateProgress(EpgUpdateProgress.Stage.SAVING))
        return Globals.json.encodeToString(epgList)
    }

    /**
     * 获取节目单列表
     */
    suspend fun getEpgList(
        refreshHour: Int = 0, forceRefresh: Boolean = false,
        onProgress: (EpgUpdateProgress) -> Unit = {},
    ): EpgList = withContext(Dispatchers.Default) {
        try {
            val xmlJson = getOrRefresh({ lastModified, _ ->
                EpgCachePolicy.isExpired(lastModified, refreshHour, forceRefresh = forceRefresh)
            }) { refresh(onProgress) }

            onProgress(EpgUpdateProgress(EpgUpdateProgress.Stage.LOADING))
            return@withContext Globals.json.decodeFromString<EpgList>(xmlJson).also { epgList ->
                log.i("加载节目单（${source.name}）：${epgList.size}个频道，${epgList.sumOf { it.programmeList.size }}个节目")
                onProgress(EpgUpdateProgress(EpgUpdateProgress.Stage.COMPLETE))
            }
        } catch (ex: Exception) {
            log.e("加载节目单（${source.name}）失败", ex)
            throw ex
        }
    }

    override suspend fun clearCache() {
        epgXmlRepository.clearCache()
        super.clearCache()
    }

    companion object {
        // v2 discarded XMLTV descriptions. Reparse once rather than showing a
        // description-less cache until the next scheduled refresh.
        private const val PARSED_CACHE_EXTENSION = "v3.json"
        suspend fun clearAllCache() = withContext(Dispatchers.IO) {
            EpgSource.cacheDir.deleteRecursively()
        }
    }
}

/**
 * 节目单xml获取
 */
private class EpgXmlRepository(private val source: EpgSource) :
    FileCacheRepository(source.cacheFileName("xml")) {

    private val log = Logger.create("EpgXmlRepository")

    suspend fun getXml(onProgress: (EpgUpdateProgress) -> Unit): InputStream {
        return getOrRefreshInputStream(0) {
            log.i("开始获取节目单（${source.name}）xml: ${source.url}")

            try {
                val t = measureTimedValue {
                    source.url.request { response, request ->
                        val fetcher =
                            EpgFetcher.instances.first { it.isSupport(request.url.toString()) }
                        fetcher.fetch(EpgProgressResponseBody(response.body!!, onProgress))
                    }
                }
                log.i("获取节目单（${source.name}）xml成功", null, t.duration)

                t.value
            } catch (ex: Exception) {
                log.e("获取节目单（${source.name}）xml失败", ex)
                throw HttpException("获取节目单xml失败，请检查网络连接", ex)
            }
        }
    }
}
