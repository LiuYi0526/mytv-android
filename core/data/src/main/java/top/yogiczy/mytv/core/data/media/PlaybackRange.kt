package top.yogiczy.mytv.core.data.media

/** Runtime programme times; deliberately separate from persisted channel/DRM configuration. */
data class PlaybackRange(
    val startAt: Long, val endAt: Long, val sourceStartAt: Long = startAt,
    val continuous: Boolean = false,
) {
    init {
        require(endAt > startAt)
        require(sourceStartAt >= startAt && (continuous || sourceStartAt < endAt))
    }

    val durationMs: Long get() = endAt - startAt

    /** Convert programme UTC time to a seek position in the current player window. */
    fun windowPosition(
        targetAt: Long,
        windowStartAt: Long?,
        windowDurationMs: Long?,
    ): Long? {
        val start = windowStartAt ?: sourceStartAt
        val target = if (continuous) targetAt.coerceAtLeast(startAt) else targetAt.coerceIn(startAt, endAt)
        if (!continuous && start >= endAt) return null
        // Origins round programme starts to segment boundaries (observed: 40 seconds).
        // A resumed position must never silently jump ahead when its old window has expired.
        if (target < start && (target != sourceStartAt || start - target > 60_000L)) return null
        val position = (target - start).coerceAtLeast(0)
        if (windowDurationMs != null && position >= windowDurationMs) return null
        return position
    }

    fun absolutePosition(positionMs: Long, windowStartAt: Long?): Long =
        ((windowStartAt ?: sourceStartAt) + positionMs.coerceAtLeast(0)).let {
            if (continuous) it.coerceAtLeast(startAt) else it.coerceIn(startAt, endAt)
        }
}
