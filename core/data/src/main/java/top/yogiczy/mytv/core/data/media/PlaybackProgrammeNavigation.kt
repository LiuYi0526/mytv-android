package top.yogiczy.mytv.core.data.media

import top.yogiczy.mytv.core.data.entities.channel.ChannelLine
import top.yogiczy.mytv.core.data.entities.epg.EpgProgramme
import top.yogiczy.mytv.core.data.utils.ChannelPlayback

/** Neighbouring broadcasts are identified by time, including across dates and repeated titles. */
object PlaybackProgrammeNavigation {
    fun adjacent(programmes: List<EpgProgramme>, current: EpgProgramme?, direction: Int): EpgProgramme? {
        current ?: return null
        val valid = programmes.asSequence().filter { it.endAt > it.startAt }
        return when {
            direction < 0 -> valid.filter { it.startAt < current.startAt }.maxByOrNull { it.startAt }
            direction > 0 -> valid.filter { it.startAt > current.startAt }.minByOrNull { it.startAt }
            else -> null
        }
    }

    fun canStart(programme: EpgProgramme?, line: ChannelLine, now: Long = System.currentTimeMillis()): Boolean =
        programme != null && programme.endAt > programme.startAt && programme.startAt < now &&
            ChannelPlayback.isSupported(line)
}
