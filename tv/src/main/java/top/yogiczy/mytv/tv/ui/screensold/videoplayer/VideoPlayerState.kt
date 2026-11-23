package top.yogiczy.mytv.tv.ui.screensold.videoplayer

import android.view.SurfaceView
import android.view.TextureView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import top.yogiczy.mytv.core.data.entities.channel.ChannelLine
import top.yogiczy.mytv.core.data.media.PlaybackRange
import top.yogiczy.mytv.tv.ui.screen.settings.settingsVM
import top.yogiczy.mytv.tv.ui.screensold.videoplayer.player.IjkVideoPlayer
import top.yogiczy.mytv.tv.ui.screensold.videoplayer.player.Media3VideoPlayer
import top.yogiczy.mytv.tv.ui.screensold.videoplayer.player.VideoPlayer
import top.yogiczy.mytv.tv.ui.utils.Configs

@Stable
class VideoPlayerState(
    val instance: VideoPlayer,
    private var defaultDisplayModeProvider: () -> VideoPlayerDisplayMode = { VideoPlayerDisplayMode.ORIGINAL },
    private val softwareDecodePreference: MutableState<Boolean> = mutableStateOf(false),
) {
    var preferSoftwareDecode: Boolean
        get() = softwareDecodePreference.value
        set(value) {
            if (softwareDecodePreference.value == value) return
            instance.setSoftwareDecodePreferred(value)
            softwareDecodePreference.value = value
        }
    /** 显示模式 */
    var displayMode by mutableStateOf(defaultDisplayModeProvider())

    /** 视频宽高比 */
    var aspectRatio by mutableFloatStateOf(16f / 9f)

    /** 错误 */
    var error by mutableStateOf<String?>(null)

    /** 正在缓冲 */
    var isBuffering by mutableStateOf(false)

    /** 正在播放 */
    var isPlaying by mutableStateOf(false)

    /** 总时长 */
    var duration by mutableLongStateOf(0L)

    /** 当前播放位置 */
    var currentPosition by mutableLongStateOf(0L)

    val positionIsAbsolute: Boolean get() = instance.positionIsAbsolute
    var playRequested by mutableStateOf(false)
        private set
    private var hasRenderedFirstFrame by mutableStateOf(false)
    internal val canEnterPictureInPicture: Boolean
        get() = hasRenderedFirstFrame && playRequested && error == null && (isPlaying || isBuffering)

    private var endedWindowAt: Long? = null

    /** 当前音量 */
    private var _volume by mutableFloatStateOf(1f)
    var volume: Float
        get() = _volume
        set(value) {
            _volume = value
            instance.setVolume(value)
        }

    /** 元数据 */
    var metadata by mutableStateOf(VideoPlayer.Metadata())
    /** Initial track discovery has finished; later buffering must not turn known tracks into loading slots. */
    var tracksReady by mutableStateOf(false)
        private set

    fun prepare(line: ChannelLine, playbackRange: PlaybackRange? = null) {
        hasRenderedFirstFrame = false
        playRequested = true
        endedWindowAt = null
        displayMode = defaultDisplayModeProvider()
        error = null
        currentPosition = 0L
        duration = playbackRange?.durationMs ?: 0L
        metadata = VideoPlayer.Metadata()
        tracksReady = false
        instance.prepare(line, playbackRange)
        currentPosition = if (positionIsAbsolute) playbackRange?.sourceStartAt ?: 0L else 0L
    }

    fun play() {
        playRequested = true
        endedWindowAt?.let { position ->
            if (onPlaybackWindowEndedListeners.isNotEmpty()) {
                onPlaybackWindowEndedListeners.forEach { it(position) }
                return
            }
        }
        instance.play()
    }

    fun pause() {
        playRequested = false
        instance.pause()
    }

    fun seekTo(position: Long) {
        endedWindowAt = null
        instance.seekTo(position)
    }

    fun stop() {
        hasRenderedFirstFrame = false
        playRequested = false
        endedWindowAt = null
        instance.stop()
    }

    fun selectVideoTrack(track: VideoPlayer.Metadata.Video?) {
        instance.selectVideoTrack(track)
    }

    fun selectAudioTrack(track: VideoPlayer.Metadata.Audio?) {
        instance.selectAudioTrack(track)
    }

    fun selectSubtitleTrack(track: VideoPlayer.Metadata.Subtitle?) {
        instance.selectSubtitleTrack(track)
    }

    fun setVideoSurfaceView(surfaceView: SurfaceView) {
        instance.setVideoSurfaceView(surfaceView)
    }

    fun setVideoTextureView(textureView: TextureView) {
        instance.setVideoTextureView(textureView)
    }

    private val onReadyListeners = mutableListOf<() -> Unit>()
    private val onErrorListeners = mutableListOf<() -> Unit>()
    private val onInterruptListeners = mutableListOf<() -> Unit>()
    private val onIsBufferingListeners = mutableListOf<(Boolean) -> Unit>()
    private val onPlaybackWindowEndedListeners = mutableListOf<(Long) -> Unit>()
    private val onPositionListeners = mutableListOf<(Long) -> Unit>()

    fun onPlaybackWindowEnded(listener: (Long) -> Unit) { onPlaybackWindowEndedListeners.add(listener) }
    fun onPosition(listener: (Long) -> Unit) { onPositionListeners.add(listener) }

    fun onReady(listener: () -> Unit) {
        onReadyListeners.add(listener)
    }

    fun onError(listener: () -> Unit) {
        onErrorListeners.add(listener)
    }

    fun onInterrupt(listener: () -> Unit) {
        onInterruptListeners.add(listener)
    }

    fun onIsBuffering(listener: (Boolean) -> Unit) {
        onIsBufferingListeners.add(listener)
    }

    fun initialize() {
        instance.initialize()
        instance.onResolution { width, height ->
            if (width > 0 && height > 0) aspectRatio = width.toFloat() / height
        }
        instance.onError { ex ->
            if (ex != null) tracksReady = true
            error = ex?.displayMessage
                ?.apply { onErrorListeners.forEach { it.invoke() } }

        }
        instance.onReady {
            tracksReady = true
            onReadyListeners.forEach { it.invoke() }
            error = null
        }
        instance.onBuffering {
            isBuffering = it
            if (it) error = null
            onIsBufferingListeners.forEach { cb -> cb(isBuffering) }
        }
        instance.onPrepared {
            hasRenderedFirstFrame = false
            tracksReady = false
            metadata = VideoPlayer.Metadata()
        }
        instance.onFirstFrame { hasRenderedFirstFrame = true }
        instance.onIsPlayingChanged { isPlaying = it }
        instance.onDurationChanged { duration = it }
        instance.onCurrentPositionChanged { position ->
            currentPosition = position
            onPositionListeners.forEach { it(position) }
        }
        instance.onPlaybackWindowEnded { position ->
            currentPosition = position
            onPositionListeners.forEach { it(position) }
            endedWindowAt = position
            onPlaybackWindowEndedListeners.forEach { it(position) }
        }
        instance.onMetadata { metadata = it }
        instance.onInterrupt { onInterruptListeners.forEach { it.invoke() } }
    }

    fun release() {
        hasRenderedFirstFrame = false
        playRequested = false
        endedWindowAt = null
        onReadyListeners.clear()
        onErrorListeners.clear()
        onInterruptListeners.clear()
        onIsBufferingListeners.clear()
        onPlaybackWindowEndedListeners.clear()
        onPositionListeners.clear()
        instance.release()
    }
}

@Composable
fun rememberVideoPlayerState(
    defaultDisplayModeProvider: () -> VideoPlayerDisplayMode = { VideoPlayerDisplayMode.ORIGINAL },
): VideoPlayerState {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()

    val videoPlayerCore = settingsVM.videoPlayerCore
    // Keep the session preference when switching playback engines; do not alter global audio settings.
    val softwareDecodePreference = remember { mutableStateOf(false) }
    val state = remember(videoPlayerCore) {
        val player = when (videoPlayerCore) {
            Configs.VideoPlayerCore.MEDIA3 -> Media3VideoPlayer(context, coroutineScope,
                initialSoftwareDecodePreferred = softwareDecodePreference.value)
            Configs.VideoPlayerCore.IJK -> IjkVideoPlayer(context, coroutineScope,
                initialSoftwareDecodePreferred = softwareDecodePreference.value)
        }

        VideoPlayerState(player, defaultDisplayModeProvider, softwareDecodePreference)
    }

    DisposableEffect(videoPlayerCore) {
        state.initialize()
        onDispose { state.release() }
    }

    DisposableEffect(lifecycleOwner, videoPlayerCore) {
        var pausedForBackground = false
        val observer = LifecycleEventObserver { _, event ->
            // A visible picture-in-picture window keeps the lifecycle started.
            if (event == Lifecycle.Event.ON_STOP && state.playRequested) {
                pausedForBackground = true
                state.pause()
            } else if (event == Lifecycle.Event.ON_RESUME && pausedForBackground) {
                pausedForBackground = false
                state.play()
            }
        }

        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    return state
}

enum class VideoPlayerDisplayMode(val value: Int, val label: String) {
    /** 原始 */
    ORIGINAL(0, "原始"),

    /** 填充 */
    FILL(1, "填充"),

    /** 裁剪 */
    CROP(2, "裁剪"),

    /** 4:3 */
    FOUR_THREE(3, "4:3"),

    /** 16:9 */
    SIXTEEN_NINE(4, "16:9"),

    /** 2.35:1 */
    WIDE(5, "2.35:1");

    companion object {
        fun fromValue(value: Int): VideoPlayerDisplayMode {
            return entries.firstOrNull { it.value == value } ?: ORIGINAL
        }
    }
}
