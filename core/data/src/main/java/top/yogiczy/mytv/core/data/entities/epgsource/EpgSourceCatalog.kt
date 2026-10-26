package top.yogiczy.mytv.core.data.entities.epgsource

import top.yogiczy.mytv.core.data.entities.iptvsource.IptvSource
import java.net.URI

/** URLs identify guides; subscription bindings remain compatible with existing epgUrls JSON. */
object EpgSourceCatalog {
    fun isValidUrl(url: String): Boolean = runCatching {
        val uri = URI(url)
        (uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) && !uri.host.isNullOrBlank()
    }.getOrDefault(false)

    fun prepare(name: String, url: String, others: List<EpgSource>): EpgSource {
        val result = EpgSource(name.trim(), url.trim())
        require(result.name.isNotEmpty()) { "请输入节目单名称" }
        require(isValidUrl(result.url)) { "请填写完整的 HTTP 或 HTTPS 节目单链接" }
        require(others.none { it.name == result.name }) { "节目单名称已存在" }
        require(others.none { it.url == result.url }) { "节目单链接已存在，请直接选择已有节目单" }
        return result
    }

    fun register(existing: List<EpgSource>, urls: List<String>, suggestedName: String): EpgSourceList {
        val result = existing.distinctBy { it.url }.toMutableList()
        for (url in urls.map { it.trim() }.distinct().filter(::isValidUrl)) {
            if (result.any { it.url == url }) continue
            val base = suggestedName.trim().ifBlank { "节目单" }
            var name = base
            var suffix = 2
            while (result.any { it.name == name }) name = "$base ${suffix++}"
            result += EpgSource(name, url)
        }
        return EpgSourceList(result)
    }

    fun migrate(existing: List<EpgSource>, default: EpgSource, subscriptions: List<IptvSource>): EpgSourceList {
        var result = register(existing, listOf(default.url), default.name)
        for (source in subscriptions) result = register(result, source.epgUrls, "${source.name} 节目单")
        return result
    }

    fun defaultSource(sources: List<EpgSource>, previous: EpgSource): EpgSource =
        sources.firstOrNull { it.url == previous.url } ?: sources.firstOrNull() ?: EpgSource()

    fun resolve(source: IptvSource, sources: List<EpgSource>, default: EpgSource): List<EpgSource> {
        val selected = source.epgUrls.distinct().mapNotNull { url -> sources.firstOrNull { it.url == url } }
        return selected.ifEmpty { listOfNotNull(default.takeIf { it.url.isNotBlank() }) }
    }

    fun updateBinding(source: IptvSource, oldUrl: String, replacement: String?): IptvSource =
        source.copy(epgUrls = source.epgUrls.mapNotNull { if (it == oldUrl) replacement else it }.distinct())
}
