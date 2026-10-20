package top.yogiczy.mytv.core.data.entities.epg

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import top.yogiczy.mytv.core.data.entities.channel.Channel
import top.yogiczy.mytv.core.data.entities.channel.ChannelList
import top.yogiczy.mytv.core.data.entities.epg.Epg.Companion.recentProgramme
import java.util.Locale

/**
 * 频道节目单列表
 */
@Serializable
@Immutable
data class EpgList(
    val value: List<Epg> = emptyList(),
) : List<Epg> by value {
    // Indexes belong to this immutable guide, so a refresh/source switch cannot reuse old matches.
    private val byId by lazy {
        buildMap {
            value.forEach { epg -> epg.channelId?.trim()?.takeIf { it.isNotEmpty() }?.let { id ->
                if (!containsKey(id)) put(id, epg)
            } }
        }
    }

    private val byName by lazy {
        buildMap {
            value.forEach { epg -> epg.channelList.forEach { name ->
                val normalized = name.trim().lowercase(Locale.ROOT)
                if (normalized.isNotEmpty() && !containsKey(normalized)) put(normalized, epg)
            } }
        }
    }

    companion object {
        fun EpgList.recentProgramme(channel: Channel): EpgProgrammeRecent? {
            if (isEmpty()) return null

            return match(channel)?.recentProgramme()
        }

        fun EpgList.match(channel: Channel): Epg? {
            if (isEmpty()) return null

            channel.epgId.trim().takeIf { it.isNotEmpty() }?.let { id ->
                byId[id]?.let { return it }
                // Older cached EPG data may have ids only in channelList.
                byName[id.lowercase(Locale.ROOT)]?.let { return it }
            }
            for (name in listOf(channel.epgName, channel.standardName, channel.name)) {
                byName[name.trim().lowercase(Locale.ROOT)]?.let { return it }
            }
            return null
        }

        fun example(channelList: ChannelList): EpgList {
            return EpgList(channelList.map(Epg.Companion::example))
        }

        private val semaphore = Semaphore(1)
        suspend fun <T> action(action: () -> T): T {
            return semaphore.withPermit {
                withContext(Dispatchers.Default) { action() }
            }
        }
    }
}
