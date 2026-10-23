package top.yogiczy.mytv.core.data.entities.iptvsource

object IptvSourceEdit {
    fun prepare(original: IptvSource, name: String, url: String, epgText: String, others: List<IptvSource>): IptvSource {
        val trimmedName = name.trim()
        val trimmedUrl = url.trim()
        require(trimmedName.isNotEmpty()) { "请输入订阅源名称" }
        require(others.none { it.name == trimmedName }) { "订阅源名称已存在，请使用其他名称" }
        require(trimmedUrl.isNotEmpty()) { "请输入订阅源链接" }
        require(trimmedUrl.none { it == '\n' || it == '\r' }) { "订阅源链接只能填写一行" }
        val local = trimmedUrl.startsWith("/") || trimmedUrl.startsWith("file://")
        val normalizedUrl = if (trimmedUrl.startsWith("file://")) java.net.URI(trimmedUrl).path else trimmedUrl
        require(local || Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://.+").matches(trimmedUrl)) { "请填写完整链接，例如 http://example.com/tv.m3u" }
        val guides = epgText.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.distinct().toList()
        require(guides.all { it.startsWith("http://", true) || it.startsWith("https://", true) }) {
            "节目单链接需使用 http:// 或 https://，每行一个"
        }
        return original.copy(name = trimmedName, url = normalizedUrl, isLocal = local, epgUrls = guides)
    }
}
