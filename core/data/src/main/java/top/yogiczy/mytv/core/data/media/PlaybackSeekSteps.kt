package top.yogiczy.mytv.core.data.media

import kotlinx.serialization.Serializable

@Serializable
data class PlaybackSeekSteps(
    val progressRewindSeconds: Int = 60,
    val progressForwardSeconds: Int = 60,
    val buttonRewindSeconds: Int = 10,
    val buttonForwardSeconds: Int = 10,
) {
    fun validated(): PlaybackSeekSteps = PlaybackSeekSteps(
        progressRewindSeconds = progressRewindSeconds.takeIf(optionsSeconds::contains) ?: DEFAULT.progressRewindSeconds,
        progressForwardSeconds = progressForwardSeconds.takeIf(optionsSeconds::contains) ?: DEFAULT.progressForwardSeconds,
        buttonRewindSeconds = buttonRewindSeconds.takeIf(optionsSeconds::contains) ?: DEFAULT.buttonRewindSeconds,
        buttonForwardSeconds = buttonForwardSeconds.takeIf(optionsSeconds::contains) ?: DEFAULT.buttonForwardSeconds,
    )

    companion object {
        val DEFAULT = PlaybackSeekSteps()
        val optionsSeconds = listOf(5, 10, 15, 20, 30, 45, 60, 120, 180, 300, 600)
    }
}
