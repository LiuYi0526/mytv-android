package top.yogiczy.mytv.core.data.entities.epg

import java.util.Calendar
import java.util.TimeZone

/** Display-only entries: real programme times and the persisted guide stay unchanged. */
object EpgDisplaySchedule {
    const val UNKNOWN_TITLE = "无信息"
    private val hourlySchedules = linkedMapOf<Pair<Long, String>, EpgProgrammeList>()

    fun fillGaps(programmes: EpgProgrammeList, zone: TimeZone = TimeZone.getDefault()): EpgProgrammeList {
        val sorted = programmes.filter { it.endAt > it.startAt }.sortedBy { it.startAt }
        if (sorted.isEmpty()) return if (programmes.isEmpty()) programmes else EpgProgrammeList()
        val result = ArrayList<EpgProgramme>(sorted.size)
        var coveredUntil = sorted.first().startAt
        for (programme in sorted) {
            var cursor = coveredUntil
            while (cursor < programme.startAt) {
                // Keep a cross-midnight gap visible in both dates' programme lists.
                val midnight = dayStart(cursor, zone).apply { add(Calendar.DAY_OF_MONTH, 1) }.timeInMillis
                val end = minOf(programme.startAt, midnight)
                result.add(EpgProgramme(cursor, end, UNKNOWN_TITLE))
                cursor = end
            }
            result.add(programme)
            coveredUntil = maxOf(coveredUntil, programme.endAt)
        }
        return if (result == programmes.value) programmes else EpgProgrammeList(result)
    }

    /** Extend only the uncovered edges within the rolling display window; retain real history. */
    fun extendEdges(programmes: EpgProgrammeList, hours: EpgProgrammeList): EpgProgrammeList {
        if (programmes.isEmpty()) return hours
        val start = programmes.first().startAt
        val end = programmes.maxOf { it.endAt }
        val before = hours.mapNotNull { hour ->
            val stop = minOf(hour.endAt, start)
            if (hour.startAt >= stop) null else hour.copy(endAt = stop)
        }
        val after = hours.mapNotNull { hour ->
            val begin = maxOf(hour.startAt, end)
            if (begin >= hour.endAt) null else hour.copy(startAt = begin)
        }
        return if (before.isEmpty() && after.isEmpty()) programmes
        else EpgProgrammeList(before + programmes.value + after)
    }

    @Synchronized
    fun hourly(at: Long = System.currentTimeMillis(), zone: TimeZone = TimeZone.getDefault()): EpgProgrammeList {
        val today = dayStart(at, zone)
        val key = today.timeInMillis to zone.id
        hourlySchedules[key]?.let { return it }
        val end = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, 2) }.timeInMillis
        val cursor = today.apply { add(Calendar.DAY_OF_MONTH, -1) }
        val result = ArrayList<EpgProgramme>()
        while (cursor.timeInMillis < end) {
            val start = cursor.timeInMillis
            cursor.add(Calendar.HOUR_OF_DAY, 1)
            result.add(EpgProgramme(start, minOf(cursor.timeInMillis, end), UNKNOWN_TITLE))
        }
        return EpgProgrammeList(result).also {
            hourlySchedules[key] = it
            // Share a small number of immutable schedules across all missing channels.
            if (hourlySchedules.size > 3) hourlySchedules.remove(hourlySchedules.keys.first())
        }
    }

    fun recent(programmes: EpgProgrammeList, at: Long): EpgProgrammeRecent {
        val index = programmes.binarySearch {
            when {
                at < it.startAt -> 1
                at >= it.endAt -> -1
                else -> 0
            }
        }
        return if (index >= 0) EpgProgrammeRecent(programmes[index], programmes.getOrNull(index + 1))
        else EpgProgrammeRecent()
    }

    private fun dayStart(at: Long, zone: TimeZone) = Calendar.getInstance(zone).apply {
        timeInMillis = at
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
}
