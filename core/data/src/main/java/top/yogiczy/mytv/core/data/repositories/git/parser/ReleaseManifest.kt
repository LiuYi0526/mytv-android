package top.yogiczy.mytv.core.data.repositories.git.parser

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import top.yogiczy.mytv.core.data.entities.git.GitRelease

/** Selects an APK for the device's OS ABI order, ignoring checksums and other release assets. */
internal object ReleaseManifest {
    fun parse(data: String, supportedAbis: List<String>): GitRelease {
        val json = Json.parseToJsonElement(data).jsonObject
        val assets = json.getValue("assets").jsonArray.map { it.jsonObject }.mapNotNull { asset ->
            val url = asset["browser_download_url"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val name = asset["name"]?.jsonPrimitive?.content ?: url.substringBefore('?').substringAfterLast('/')
            if (name.endsWith(".apk", ignoreCase = true)) name to url else null
        }
        val selected = supportedAbis.firstNotNullOfOrNull { abi ->
            assets.firstOrNull { (name, _) -> name.contains(abi, ignoreCase = true) }
        } ?: assets.firstOrNull { (name, _) ->
            name.contains("universal", ignoreCase = true) || name.contains("-all-", ignoreCase = true)
        } ?: error("没有适合当前设备架构的安装包")
        return GitRelease(
            version = json.getValue("tag_name").jsonPrimitive.content.removePrefix("v"),
            downloadUrl = selected.second,
            description = json["body"]?.jsonPrimitive?.content.orEmpty(),
        )
    }
}
