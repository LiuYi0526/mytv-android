package top.yogiczy.mytv.core.data.entities.iptvsource

import kotlinx.serialization.Serializable
import top.yogiczy.mytv.core.data.utils.Globals
import java.io.File

/**
 *  直播源
 */
@Serializable
data class IptvSource(
    /**
     * 名称
     */
    val name: String = "",

    /**
     * 链接
     */
    val url: String = "",

    /**
     * 是否本地
     */
    val isLocal: Boolean = false,

    /**
     * 转换js
     */
    val transformJs: String? = null,
    /** Explicit guide URLs for this subscription, in priority order. Empty follows the source/global guide. */
    val epgUrls: List<String> = emptyList(),
) {
    // Keep the original four-field key: editing guide settings must not discard channel caches.
    internal fun channelCacheKey(): Int {
        var key = name.hashCode()
        key = 31 * key + url.hashCode()
        key = 31 * key + isLocal.hashCode()
        return 31 * key + (transformJs?.hashCode() ?: 0)
    }

    fun cacheFileName(ext: String) =
        "${cacheDir.name}/iptv_source_${channelCacheKey().toUInt().toString(16)}.$ext"

    companion object {
        val cacheDir by lazy { File(Globals.cacheDir, "iptv_source_cache") }

        val EXAMPLE = IptvSource(
            name = "测试直播源1",
            url = "http://1.2.3.4/tv.txt",
            transformJs = "",
        )

        fun IptvSource.needExternalStoragePermission(): Boolean {
            return this.isLocal && !this.url.startsWith(Globals.fileDir.path)
        }
    }
}
