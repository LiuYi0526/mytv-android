package top.yogiczy.mytv.core.data.repositories.epg

import java.util.Calendar
import java.util.TimeZone

/** The refresh hour starts a new cache cycle; it never prevents reading cached programmes. */
internal object EpgCachePolicy {
    fun isExpired(
        lastModified: Long,
        refreshHour: Int,
        now: Long = System.currentTimeMillis(),
        timeZone: TimeZone = TimeZone.getDefault(),
    ): Boolean {
        val boundary = Calendar.getInstance(timeZone).apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, refreshHour.coerceIn(0, 23))
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis > now) add(Calendar.DAY_OF_MONTH, -1)
        }
        return lastModified < boundary.timeInMillis
    }
}
