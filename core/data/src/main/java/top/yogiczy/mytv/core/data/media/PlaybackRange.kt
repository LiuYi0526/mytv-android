package top.yogiczy.mytv.core.data.media

/** Runtime programme times; deliberately separate from persisted channel/DRM configuration. */
data class PlaybackRange(val startAt: Long, val endAt: Long) {
    init {
        require(endAt > startAt)
    }

    val durationMs: Long get() = endAt - startAt

    /** Convert programme UTC time to a seek position in the current player window. */
    fun windowPosition(
        targetAt: Long,
        windowStartAt: Long?,
        windowDurationMs: Long?,
    ): Long? {
        val start = windowStartAt ?: startAt
        val target = targetAt.coerceIn(startAt, endAt)
        if (start >= endAt) return null
        // Origins round programme starts to segment boundaries (observed: 40 seconds).
        // A resumed position must never silently jump ahead when its old window has expired.
        if (target < start && (target != startAt || start - target > 60_000L)) return null
        val position = (target - start).coerceAtLeast(0)
        if (windowDurationMs != null && position >= windowDurationMs) return null
        return position
    }

    fun absolutePosition(positionMs: Long, windowStartAt: Long?): Long =
        ((windowStartAt ?: startAt) + positionMs.coerceAtLeast(0)).coerceIn(startAt, endAt)
}
