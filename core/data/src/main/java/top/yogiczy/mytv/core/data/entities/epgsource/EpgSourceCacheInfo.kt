package top.yogiczy.mytv.core.data.entities.epgsource

import java.io.File
import java.io.IOException
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.decodeFromStream
import top.yogiczy.mytv.core.data.utils.Globals

data class EpgSourceCacheInfo(val channelCount: Int, val cacheBytes: Long, val updatedAt: Long) {
    companion object {
        @OptIn(ExperimentalSerializationApi::class)
        fun read(parsedCache: File, xmlCache: File? = null): EpgSourceCacheInfo? {
            if (!parsedCache.isFile) return null
            return try {
                // Stream only channel entries; ignore programme payloads rather
                // than loading another full guide into memory for statistics.
                val channels = parsedCache.inputStream().use {
                    Globals.json.decodeFromStream<CachedChannels>(it)
                }
                EpgSourceCacheInfo(channels.value.size,
                    parsedCache.length() + (xmlCache?.length() ?: 0L), parsedCache.lastModified())
            } catch (_: IOException) {
                null
            } catch (_: SerializationException) {
                null
            }
        }
    }
}

@Serializable
private data class CachedChannels(val value: List<CachedChannel> = emptyList())

@Serializable
private class CachedChannel
