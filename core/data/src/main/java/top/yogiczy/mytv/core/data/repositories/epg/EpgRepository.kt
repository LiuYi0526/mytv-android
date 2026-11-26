package top.yogiczy.mytv.core.data.repositories.epg

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yogiczy.mytv.core.data.entities.epg.EpgList
import top.yogiczy.mytv.core.data.entities.epgsource.EpgSource
import top.yogiczy.mytv.core.data.entities.epgsource.EpgSourceCacheInfo
import top.yogiczy.mytv.core.data.network.HttpException
import top.yogiczy.mytv.core.data.network.request
import top.yogiczy.mytv.core.data.repositories.FileCacheRepository
import top.yogiczy.mytv.core.data.repositories.epg.fetcher.EpgFetcher
import top.yogiczy.mytv.core.data.utils.Globals
import top.yogiczy.mytv.core.data.utils.Logger
import java.io.File
import java.io.IOException

/**
 * 节目单获取
 */
class EpgRepository(private val source: EpgSource) :
    FileCacheRepository(source.cacheFileName(PARSED_CACHE_EXTENSION)) {

    private val log = Logger.create("EpgRepository")
    private val xmlCache = File(Globals.cacheDir, source.cacheFileName("xml"))
    private val parsedCache = File(Globals.cacheDir, source.cacheFileName(PARSED_CACHE_EXTENSION))
    private val cache = EpgCacheStore(parsedCache)

    suspend fun getCachedGuide(
        refreshHour: Int,
        select: (EpgList) -> EpgList,
    ): EpgCacheStore.CachedGuide? = cache.readCached(refreshHour, select)

    suspend fun getCachedInfo(): EpgSourceCacheInfo? = withContext(Dispatchers.IO) {
        EpgSourceCacheInfo.read(parsedCache, xmlCache)
    }

    private suspend fun refresh(
        previous: EpgValidators?,
        onProgress: (EpgUpdateProgress) -> Unit,
    ): EpgRefreshResult {
        onProgress(EpgUpdateProgress())
        val pendingXml = lazy { File.createTempFile("epg-", ".xml.tmp", xmlCache.parentFile) }
        try {
            val result = download(previous, pendingXml, onProgress)
                ?: (if (previous != null) download(null, pendingXml, onProgress) else null)
                ?: throw HttpException("节目单服务器未返回完整数据", null)
            if (result == Download.Unchanged) {
                log.i("节目单（${source.name}）未变更，继续使用缓存")
                return EpgRefreshResult.Unchanged
            }
            check(result is Download.Updated)
            onProgress(EpgUpdateProgress(EpgUpdateProgress.Stage.PARSING))
            val guide = pendingXml.value.inputStream().buffered().use { EpgParser.fromXml(it) }
            check(guide.any { it.programmeList.isNotEmpty() }) { "获取节目单为空" }
            if (!pendingXml.value.renameTo(xmlCache)) throw IOException("保存节目单 XML 失败")
            return EpgRefreshResult.Updated(guide, result.validators)
        } finally {
            if (pendingXml.isInitialized()) pendingXml.value.delete()
        }
    }

    private suspend fun download(
        previous: EpgValidators?,
        pendingXml: Lazy<File>,
        onProgress: (EpgUpdateProgress) -> Unit,
    ): Download? = try {
        source.url.request(
            builder = { previous?.applyTo(it) ?: it },
            acceptNotModified = true,
        ) { response, request ->
            response.use {
                if (response.code == 304) {
                    // A redirect may now select a different resource with the same opaque tag.
                    if (previous?.hasHttpValidator == true && previous.resource == EpgValidators.resourceOf(response)) {
                        return@request Download.Unchanged
                    }
                    return@request null
                }
                check(response.code == 200) { "节目单 HTTP ${response.code}" }
                val validators = EpgValidators.from(response)
                if (previous?.matchesGzip(validators) == true) return@request Download.Unchanged

                val fetcher = EpgFetcher.instances.first { it.isSupport(request.url.toString()) }
                fetcher.fetch(EpgProgressResponseBody(checkNotNull(response.body), onProgress)).use { input ->
                    pendingXml.value.outputStream().use { output -> input.copyTo(output) }
                }
                Download.Updated(validators)
            }
        }
    } catch (ex: CancellationException) {
        throw ex
    } catch (ex: Exception) {
        throw HttpException("获取节目单 XML 失败，请检查网络连接", ex)
    }

    private sealed interface Download {
        data object Unchanged : Download
        data class Updated(val validators: EpgValidators) : Download
    }

    /**
     * 获取节目单列表
     */
    suspend fun getEpgList(
        refreshHour: Int = 0, forceRefresh: Boolean = false,
        onProgress: (EpgUpdateProgress) -> Unit = {},
        displayed: EpgCacheStore.CachedGuide? = null,
        checkHttpOnStartup: Boolean = false,
        select: (EpgList) -> EpgList = { it },
    ): EpgList = withContext(Dispatchers.Default) {
        try {
            return@withContext cache.revalidate(
                refreshHour, forceRefresh, onProgress, displayed, checkHttpOnStartup, select,
            ) {
                refresh(it, onProgress)
            }.also { epgList ->
                log.i("加载节目单（${source.name}）：${epgList.size}个频道，${epgList.sumOf { it.programmeList.size }}个节目")
                onProgress(EpgUpdateProgress(EpgUpdateProgress.Stage.COMPLETE))
            }
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: Exception) {
            log.e("加载节目单（${source.name}）失败", ex)
            throw ex
        }
    }

    override suspend fun clearCache() {
        cache.clear { xmlCache.delete() }
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
