package top.yogiczy.mytv.core.data.entities.timing

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** A one-session sleep timer. The clock must be monotonic, including time spent asleep. */
class TimingCloseTimer(
    private val scope: CoroutineScope,
    private val nowMillis: () -> Long,
    private val wait: suspend (Long) -> Unit = { delay(it) },
) {
    data class Status(
        val selectedMinutes: Int = 0,
        val remainingMillis: Long = 0,
        val hasExpired: Boolean = false,
    ) {
        val isActive get() = selectedMinutes > 0
        val remainingMinutes get() = (remainingMillis + 59_999L) / 60_000L
    }

    private val mutableStatus = MutableStateFlow(Status())
    val status = mutableStatus.asStateFlow()
    private var countdownJob: Job? = null

    fun setMinutes(minutes: Int) {
        require(minutes == 0 || minutes in PRESET_MINUTES)
        countdownJob?.cancel()
        countdownJob = null
        if (minutes == 0) {
            mutableStatus.value = Status()
            return
        }

        val duration = minutes * 60_000L
        val deadline = nowMillis() + duration
        mutableStatus.value = Status(minutes, duration)
        countdownJob = scope.launch {
            while (true) {
                val remaining = (deadline - nowMillis()).coerceAtLeast(0)
                if (remaining == 0L) {
                    mutableStatus.value = Status(hasExpired = true)
                    break
                }
                mutableStatus.value = Status(minutes, remaining)
                wait(minOf(1_000L, remaining))
            }
        }
    }

    companion object {
        val PRESET_MINUTES = listOf(15, 30, 45, 60, 90, 120)
    }
}
