package top.yogiczy.mytv.core.data.repositories.iptv.parser

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * m3u直播源解析
 */
class M3uIptvParser : IptvParser {

    override fun isSupport(url: String, data: String): Boolean {
        return data.trimStart { it.isWhitespace() || it == '\uFEFF' }.startsWith("#EXTM3U", ignoreCase = true)
    }

    override suspend fun parse(data: String) =
        withContext(Dispatchers.Default) {
            val lines = data.split("\r\n", "\n")
            val channelList = mutableListOf<IptvParser.ChannelItem>()

            var addedChannels: List<IptvParser.ChannelItem> = listOf()
            lines.forEach { rawLine ->
                val line = rawLine.trim()
                if (line.isBlank()) return@forEach

                if (line.startsWith("#EXTINF")) {
                    val name = line.split(",").last().trim()
                    val epgName =
                        Regex("tvg-name=\"(.*?)\"").find(line)?.groupValues?.get(1)?.trim()
                            ?.ifBlank { name } ?: name
                    val epgId = Regex("tvg-id=\"(.*?)\"").find(line)?.groupValues?.get(1)?.trim().orEmpty()
                    val groupNames =
                        Regex("group-title=\"(.+?)\"").find(line)?.groupValues?.get(1)?.split(";")
                            ?.map { it.trim() }
                            ?: listOf("其他")
                    val logo = Regex("tvg-logo=\"(.+?)\"").find(line)?.groupValues?.get(1)?.trim()
                    val httpUserAgent =
                        Regex("http-user-agent=\"(.+?)\"").find(line)?.groupValues?.get(1)?.trim()

                    addedChannels = groupNames.map { groupName ->
                        IptvParser.ChannelItem(
                            name = name,
                            epgName = epgName,
                            epgId = epgId,
                            groupName = groupName,
                            url = "",
                            logo = logo,
                            httpUserAgent = httpUserAgent,
                        )
                    }
                } else if (line.substringBefore('=').trim() == "#EXTVLCOPT:http-user-agent") {
                    // VLC options belong to the current EXTINF entry, including all its groups.
                    val userAgent = line.substringAfter('=', "").trim().removeSurrounding("\"")
                    if (userAgent.isNotBlank()) {
                        addedChannels = addedChannels.map { it.copy(httpUserAgent = userAgent) }
                    }
                } else if (line.startsWith("#KODIPROP:inputstream.adaptive.manifest_type")) {
                    addedChannels =
                        addedChannels.map { it.copy(manifestType = line.split("=").last()) }
                } else if (line.startsWith("#KODIPROP:inputstream.adaptive.license_type")) {
                    addedChannels =
                        addedChannels.map { it.copy(licenseType = line.split("=").last()) }
                } else if (line.startsWith("#KODIPROP:inputstream.adaptive.license_key")) {
                    addedChannels =
                        addedChannels.map { it.copy(licenseKey = line.split("=").last()) }
                } else if (line.startsWith("#") || line.startsWith("//")) {
                    return@forEach
                } else {
                    channelList.addAll(addedChannels.map { it.copy(url = line.trim()) })
                }
            }

            channelList
        }

    override suspend fun getEpgUrl(data: String): String? = getEpgUrls(data).firstOrNull()

    override suspend fun getEpgUrls(data: String): List<String> {
        val header = data.lineSequence().map { it.trim().trimStart('\uFEFF').trimStart() }
            .firstOrNull { it.isNotEmpty() } ?: return emptyList()
        if (!header.startsWith("#EXTM3U", ignoreCase = true)) return emptyList()
        // Read header attributes only: channel attributes must not become subscription guides.
        val attributes = Regex("""(?:^|\s)([\w-]+)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s]+))""")
        return attributes.findAll(header).filter {
            it.groupValues[1].equals("url-tvg", true) || it.groupValues[1].equals("x-tvg-url", true)
        }.flatMap { match ->
            match.groupValues.drop(2).firstOrNull { it.isNotEmpty() }.orEmpty().split(',').asSequence()
        }.map { it.trim() }.filter { value ->
            runCatching {
                val uri = java.net.URI(value)
                (uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) && !uri.host.isNullOrBlank()
            }.getOrDefault(false)
        }.distinct().toList()
    }
}
