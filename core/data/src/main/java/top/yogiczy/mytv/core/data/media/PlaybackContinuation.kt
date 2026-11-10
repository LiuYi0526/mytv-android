package top.yogiczy.mytv.core.data.media

/** Pace finite catchup windows and stop a source that repeatedly returns no new content. */
class PlaybackContinuation {
    private var lastEnd: Long? = null
    private var stalled = 0

    fun accept(position: Long): Boolean {
        stalled = if (lastEnd?.let { position <= it + 1_000 } == true) stalled + 1 else 0
        lastEnd = position
        return stalled < 3
    }

    fun waitMs(position: Long, now: Long): Long = (15_000L - (now - position)).coerceAtLeast(0)

    fun reset() { lastEnd = null; stalled = 0 }
}
