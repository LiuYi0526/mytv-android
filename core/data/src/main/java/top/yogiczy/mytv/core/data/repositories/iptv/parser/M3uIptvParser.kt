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
            var defaults = emptyMap<String, String>()
            lines.forEach { rawLine ->
                val line = rawLine.trim().trimStart('\uFEFF')
                if (line.isBlank()) return@forEach

                if (line.startsWith("#EXTM3U", ignoreCase = true)) {
                    defaults = attributes(line)
                } else if (line.startsWith("#EXTINF")) {
                    // A catchup template may contain commas inside its quoted attributes.
                    var quote: Char? = null
                    val separator = line.indexOfFirst { char ->
                        if (char == '"' || char == '\'') {
                            if (quote == char) quote = null else if (quote == null) quote = char
                        }
                        char == ',' && quote == null
                    }
                    val name = if (separator >= 0) line.substring(separator + 1).trim() else ""
                    val options = defaults + attributes(if (separator >= 0) line.take(separator) else line)
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
                            catchup = options["catchup"],
                            catchupSource = options["catchup-source"],
                            pltv2tvod = !options["pltv2tvod"].equals("false", ignoreCase = true),
                        )
                    }
                } else if (line.substringBefore('=').trim() == "#EXTVLCOPT:http-user-agent") {
                    // VLC options belong to the current EXTINF entry, including all its groups.
                    val userAgent = line.substringAfter('=', "").trim().removeSurrounding("\"")
                    if (userAgent.isNotBlank()) {
                        addedChannels = addedChannels.map { it.copy(httpUserAgent = userAgent) }
                    }
                } else if (line.startsWith("#KODIPROP:", ignoreCase = true)) {
                    val property = line.substringBefore('=').trim().lowercase()
                    val value = line.substringAfter('=', "").trim().removeSurrounding("\"")
                    addedChannels = addedChannels.map {
                        when (property) {
                            "#kodiprop:inputstream.adaptive.manifest_type" -> it.copy(manifestType = value)
                            "#kodiprop:inputstream.adaptive.license_type" -> it.copy(licenseType = value)
                            "#kodiprop:inputstream.adaptive.license_key" -> it.copy(licenseKey = value)
                            "#kodiprop:catchup" -> it.copy(catchup = value)
                            "#kodiprop:catchup-source" -> it.copy(catchupSource = value)
                            "#kodiprop:pltv2tvod" -> it.copy(pltv2tvod = !value.equals("false", true))
                            else -> it
                        }
                    }
                } else if (line.startsWith("#") || line.startsWith("//")) {
                    return@forEach
                } else {
                    channelList.addAll(addedChannels.map { it.copy(url = line.trim()) })
                }
            }

            channelList
        }

    private fun attributes(line: String): Map<String, String> =
        Regex("""(?:^|\s)([\w-]+)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s]+))""")
            .findAll(line).associate { match ->
                match.groupValues[1].lowercase() to match.groupValues.drop(2)
                    .firstOrNull { it.isNotEmpty() }.orEmpty()
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
