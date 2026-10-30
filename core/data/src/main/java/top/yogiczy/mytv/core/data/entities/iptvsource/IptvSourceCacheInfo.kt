package top.yogiczy.mytv.core.data.entities.iptvsource

import java.io.File
import java.io.IOException
import kotlinx.serialization.SerializationException
import top.yogiczy.mytv.core.data.entities.channel.ChannelGroupList
import top.yogiczy.mytv.core.data.utils.Globals

data class IptvSourceCacheInfo(
    val groupCount: Int,
    val channelCount: Int,
    val cacheBytes: Long,
    val updatedAt: Long,
) {
    companion object {
        /** Read successful parsed data only; never fetch or change cache timestamps. */
        fun read(parsedCache: File, rawCache: File? = null): IptvSourceCacheInfo? {
            if (!parsedCache.isFile) return null
            return try {
                val groups = Globals.json.decodeFromString<ChannelGroupList>(parsedCache.readText())
                IptvSourceCacheInfo(
                    groupCount = groups.size,
                    channelCount = groups.sumOf { it.channelList.size },
                    cacheBytes = parsedCache.length() + (rawCache?.length() ?: 0L),
                    updatedAt = parsedCache.lastModified(),
                )
            } catch (_: IOException) {
                null
            } catch (_: SerializationException) {
                null
            }
        }
    }
}
