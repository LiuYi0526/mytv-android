package top.yogiczy.mytv.core.data.entities.epg

import kotlinx.serialization.Serializable
import top.yogiczy.mytv.core.data.entities.channel.Channel
import top.yogiczy.mytv.core.data.utils.Logger
import kotlin.time.measureTimedValue

/**
 * 频道节目单
 */
@Serializable
data class Epg(
    /**
     * 频道名称
     */
    val channelList: List<String> = emptyList(),

    /**
     * 图标
     */
    val logo: String? = null,

    /**
     * 节目列表
     */
    val programmeList: EpgProgrammeList = EpgProgrammeList(),

    val channelId: String? = null,
) {
    // Delegated properties are not serialized. Fill old cached guides without
    // downloading again or rebuilding the display schedule on every position tick.
    private val gapFilledGuide by lazy {
        val programmes = EpgDisplaySchedule.fillGaps(programmeList)
        if (programmes === programmeList) this else copy(programmeList = programmes)
    }

    // Delegated caches stay out of JSON. Bound retained views when the clock crosses midnight.
    private val displayGuides by lazy { linkedMapOf<Pair<Long, Long>, Epg>() }

    internal fun displayGuide(at: Long): Epg {
        val hours = EpgDisplaySchedule.hourly(at)
        val key = hours.first().startAt to hours.last().endAt
        return synchronized(displayGuides) {
            displayGuides.getOrPut(key) {
                val filled = gapFilledGuide
                val extended = EpgDisplaySchedule.extendEdges(filled.programmeList, hours)
                if (extended === filled.programmeList) filled else filled.copy(programmeList = extended)
            }.also {
                if (displayGuides.size > 2) displayGuides.remove(displayGuides.keys.first())
            }
        }
    }

    companion object {
        private val log = Logger.create("Epg")

        fun Epg.recentProgramme(): EpgProgrammeRecent {
            if (this == Epg()) return EpgProgrammeRecent()

            val t = measureTimedValue {
                val currentTime = System.currentTimeMillis()
                EpgDisplaySchedule.recent(programmeList, currentTime)
            }
            log.v("recentProgramme: ${channelList.firstOrNull()}", null, t.duration)

            return t.value
        }

        fun example(channel: Channel): Epg {
            return Epg(
                channelList = listOf(channel.epgName),
                programmeList = EpgProgrammeList(
                    List(100) { index ->
                        val startAt =
                            System.currentTimeMillis() - 3500 * 1000 * 24 * 2 + index * 3600 * 1000
                        EpgProgramme(
                            title = "${channel.epgName}节目${index + 1}",
                            startAt = startAt,
                            endAt = startAt + 3600 * 1000
                        )
                    }
                )
            )
        }

        fun empty(channel: Channel): Epg {
            return Epg(
                channelList = listOf(channel.epgName),
                programmeList = EpgProgrammeList(listOf(EpgProgramme.EMPTY))
            )
        }
    }
}
