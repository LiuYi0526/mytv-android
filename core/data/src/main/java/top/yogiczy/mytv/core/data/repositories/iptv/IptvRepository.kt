package top.yogiczy.mytv.core.data.repositories.iptv

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import top.yogiczy.mytv.core.data.entities.channel.ChannelGroupList
import top.yogiczy.mytv.core.data.entities.iptvsource.IptvSource
import top.yogiczy.mytv.core.data.entities.iptvsource.IptvSourceCacheInfo
import top.yogiczy.mytv.core.data.entities.iptvsource.IptvSourceUrl
import top.yogiczy.mytv.core.data.repositories.FileCacheRepository
import top.yogiczy.mytv.core.data.repositories.iptv.parser.IptvParser
import top.yogiczy.mytv.core.data.repositories.iptv.parser.IptvParser.ChannelItem.Companion.toChannelGroupList
import top.yogiczy.mytv.core.data.utils.Globals
import top.yogiczy.mytv.core.data.utils.Logger
import kotlin.time.measureTimedValue

/**
 * 直播源数据获取
 */
class IptvRepository(private val source: IptvSource) :
    // Reparse the raw playlist so existing subscriptions also acquire catchup metadata.
    FileCacheRepository(source.cacheFileName(PARSED_CACHE_EXTENSION)) {

    private val log = Logger.create("IptvRepository")
    private val sourceUrl = IptvSourceUrl.parse(source.url)
    private val rawRepository = IptvRawRepository(source.copy(url = sourceUrl.requestUrl))

    suspend fun getCachedInfo(): IptvSourceCacheInfo? = withContext(Dispatchers.IO) {
        IptvSourceCacheInfo.read(
            File(Globals.cacheDir, source.cacheFileName(PARSED_CACHE_EXTENSION)),
            if (source.isLocal) null else rawRepository.cachedFile,
        )
    }

    private fun isExpired(lastModified: Long, cacheTime: Long): Boolean {
        val rawModified = rawRepository.lastModified()
        // Parsing a prefetched playlist must not extend the freshness of its downloaded data.
        val downloadedAt = if (rawModified > 0) minOf(lastModified, rawModified) else lastModified
        val timeout = System.currentTimeMillis() - downloadedAt >=
            (if (source.isLocal) Long.MAX_VALUE else cacheTime)
        val rawChanged = lastModified < rawModified

        return timeout || rawChanged
    }

    private suspend fun refresh(
        cacheTime: Long,
        transform: suspend ((List<IptvParser.ChannelItem>) -> List<IptvParser.ChannelItem>) = { it -> it },
    ): String {
        val raw = rawRepository.getRaw(cacheTime)
        val parser = IptvParser.instances.first { it.isSupport(sourceUrl.requestUrl, raw) }

        log.d("开始解析直播源（${source.name}）...")
        return measureTimedValue {
            val list = parser.parse(raw)
            Globals.json.encodeToString(withContext(Dispatchers.Default) {
                runCatching { transform(list) }
                    .getOrDefault(list)
                    .toChannelGroupList()
            })
        }.let {
            log.i("解析直播源（${source.name}）完成", null, it.duration)
            it.value
        }
    }

    private suspend fun transform(channelList: List<IptvParser.ChannelItem>): List<IptvParser.ChannelItem> =
        withContext(Dispatchers.IO) {
            if (source.transformJs.isNullOrBlank()) return@withContext channelList

            val context = org.mozilla.javascript.Context.enter()
            context.optimizationLevel = -1
            val result = runCatching {
                val scope = context.initStandardObjects()
                context.evaluateString(
                    scope, """
                    (function() {
                        var channelList = ${Globals.json.encodeToString(channelList)};
                        ${source.transformJs}
                        return JSON.stringify(main(channelList));
                    })();
                    """.trimIndent(), "JavaScript", 1, null
                ) as String
            }
            org.mozilla.javascript.Context.exit()

            if (result.isFailure) {
                log.e("转换直播源（${source.name}）错误: ${result.exceptionOrNull()}")
            }

            if (result.isSuccess) Globals.json.decodeFromString(result.getOrNull()!!)
            else channelList
        }

    /**
     * 获取直播源分组列表
     */
    suspend fun getChannelGroupList(cacheTime: Long): ChannelGroupList {
        try {
            val json = getOrRefresh({ lastModified, _ -> isExpired(lastModified, cacheTime) }) {
                refresh(cacheTime) { transform(it) }
            }

            val (groupList, duration) = measureTimedValue {
                Globals.json.decodeFromString<ChannelGroupList>(json)
            }
            log.i("加载直播源（${source.name}）：${groupList.size}个分组，${groupList.sumOf { it.channelList.size }}个频道: $duration")
            return sourceUrl.applyTo(groupList)
        } catch (ex: Exception) {
            log.e("加载直播源（${source.name}）失败", ex)
            throw ex
        }
    }

    suspend fun getEpgUrl(): String? = getEpgUrls().firstOrNull()

    suspend fun getEpgUrls(cacheTime: Long = Long.MAX_VALUE): List<String> {
        return try {
            val sourceData = rawRepository.getRaw(cacheTime)
            val parser = IptvParser.instances.first { it.isSupport(sourceUrl.requestUrl, sourceData) }
            parser.getEpgUrls(sourceData)
        } catch (ex: CancellationException) {
            throw ex
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            emptyList()
        }
    }

    /** Guide discovery is optional: an unavailable playlist must not prevent adding a source. */
    suspend fun withDetectedEpgUrls(cacheTime: Long = Long.MAX_VALUE): IptvSource {
        if (source.epgUrls.isNotEmpty()) return source
        val detected = withTimeoutOrNull(10_000) { getEpgUrls(cacheTime) }.orEmpty()
        return source.copy(epgUrls = detected)
    }

    override suspend fun clearCache() {
        rawRepository.clearCache()
        super.clearCache()
    }

    companion object {
        private const val PARSED_CACHE_EXTENSION = "v4.json"
        suspend fun clearAllCache() = withContext(Dispatchers.IO) {
            IptvSource.cacheDir.deleteRecursively()
        }
    }
}
