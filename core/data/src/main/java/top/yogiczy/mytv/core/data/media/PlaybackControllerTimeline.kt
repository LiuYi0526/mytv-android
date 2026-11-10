package top.yogiczy.mytv.core.data.media

import top.yogiczy.mytv.core.data.entities.epg.EpgProgramme

/** The controller's clock can differ from the engine's relative media position. */
data class PlaybackControllerTimeline(
    val position: Long,
    val range: Pair<Long, Long>,
    val absolute: Boolean,
    val epgFallback: Boolean = false,
    val seekEnabled: Boolean = true,
) {
    companion object {
        fun resolve(
            position: Long,
            duration: Long,
            nativeAbsolute: Boolean,
            replay: EpgProgramme?,
            live: EpgProgramme?,
            supportsReplay: Boolean,
            now: Long,
        ): PlaybackControllerTimeline {
            val programme = replay ?: live
            if (nativeAbsolute) return PlaybackControllerTimeline(
                position, (programme?.startAt ?: 0L) to (programme?.endAt ?: 0L), true,
            )
            if (replay == null && duration <= 0L && live != null &&
                now >= live.startAt && now < live.endAt) {
                return PlaybackControllerTimeline(
                    now, live.startAt to live.endAt, true,
                    epgFallback = true, seekEnabled = supportsReplay,
                )
            }
            return PlaybackControllerTimeline(position, 0L to duration.coerceAtLeast(0L), false)
        }
    }
}
