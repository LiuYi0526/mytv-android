package top.yogiczy.mytv.tv.ui.screensold.main.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import top.yogiczy.mytv.core.data.entities.channel.Channel
import top.yogiczy.mytv.core.data.entities.channel.ChannelGroupList
import top.yogiczy.mytv.core.data.entities.channel.ChannelGroupList.Companion.channelFirstOrNull
import top.yogiczy.mytv.core.data.entities.channel.ChannelGroupList.Companion.channelGroupIdx
import top.yogiczy.mytv.core.data.entities.channel.ChannelGroupList.Companion.channelIdx
import top.yogiczy.mytv.core.data.entities.channel.ChannelGroupList.Companion.channelLastOrNull
import top.yogiczy.mytv.core.data.entities.channel.ChannelGroupList.Companion.channelList
import top.yogiczy.mytv.core.data.entities.channel.ChannelLine
import top.yogiczy.mytv.core.data.entities.channel.ChannelLineList
import top.yogiczy.mytv.core.data.entities.channel.ChannelList
import top.yogiczy.mytv.core.data.entities.epg.EpgProgramme
import top.yogiczy.mytv.core.data.entities.epg.EpgList
import top.yogiczy.mytv.core.data.entities.epg.EpgList.Companion.match
import top.yogiczy.mytv.core.data.entities.epg.EpgProgrammeReserve
import top.yogiczy.mytv.core.data.entities.epg.EpgProgrammeReserveList
import top.yogiczy.mytv.core.data.utils.ChannelPlayback
import top.yogiczy.mytv.core.data.media.PlaybackRange
import top.yogiczy.mytv.core.data.media.PlaybackContinuation
import top.yogiczy.mytv.core.data.media.PlaybackProgrammeNavigation
import top.yogiczy.mytv.core.data.utils.Constants
import top.yogiczy.mytv.core.data.utils.Loggable
import top.yogiczy.mytv.core.util.utils.urlHost
import top.yogiczy.mytv.tv.ui.material.Snackbar
import top.yogiczy.mytv.tv.ui.screen.settings.SettingsViewModel
import top.yogiczy.mytv.tv.ui.screen.settings.settingsVM
import top.yogiczy.mytv.tv.ui.screensold.videoplayer.VideoPlayerState
import top.yogiczy.mytv.tv.ui.screensold.videoplayer.VideoPlayerDisplayMode
import top.yogiczy.mytv.tv.ui.screensold.videoplayer.player.VideoPlayer
import top.yogiczy.mytv.tv.ui.screensold.videoplayer.rememberVideoPlayerState
import kotlin.math.max
import kotlin.math.min

@Stable
class MainContentState(
    private val coroutineScope: CoroutineScope,
    private val videoPlayerState: VideoPlayerState,
    private val channelGroupListProvider: () -> ChannelGroupList = { ChannelGroupList() },
    private val favoriteChannelListProvider: () -> ChannelList = { ChannelList() },
    private val settingsViewModel: SettingsViewModel,
    private val epgListProvider: () -> EpgList = { EpgList() },
    private val nowProvider: () -> Long = { System.currentTimeMillis() },
) : Loggable("MainContentState") {
    // Channel equality describes stream content, while selection also has a group/index.
    private var _currentChannel by mutableStateOf(Channel(), referentialEqualityPolicy())
    val currentChannel get() = _currentChannel

    private var _currentChannelLineIdx by mutableIntStateOf(0)
    val currentChannelLineIdx get() = _currentChannelLineIdx

    val currentChannelLine get() = _currentChannel.lineList[_currentChannelLineIdx]

    private var _currentPlaybackEpgProgramme by mutableStateOf<EpgProgramme?>(null)
    val currentPlaybackEpgProgramme get() = _currentPlaybackEpgProgramme
    private var playbackSessionStartAt: Long? = null
    private var continuationJob: Job? = null
    private var playbackGeneration = 0L
    private var playbackRequestId = 0L
    private var evaluatedEndRequestId: Long? = null
    private val continuation = PlaybackContinuation()

    private var _tempChannelScreenHideJob: Job? = null

    private var _isTempChannelScreenVisible by mutableStateOf(false)
    var isTempChannelScreenVisible
        get() = _isTempChannelScreenVisible
        set(value) {
            _isTempChannelScreenVisible = value
        }

    private var _isChannelScreenVisible by mutableStateOf(false)
    var isChannelScreenVisible
        get() = _isChannelScreenVisible
        set(value) {
            _isChannelScreenVisible = value
        }

    private var _isVideoPlayerControllerScreenVisible by mutableStateOf(false)
    var isVideoPlayerControllerScreenVisible
        get() = _isVideoPlayerControllerScreenVisible
        set(value) {
            _isVideoPlayerControllerScreenVisible = value
        }

    private var _isQuickOpScreenVisible by mutableStateOf(false)
    var isQuickOpScreenVisible
        get() = _isQuickOpScreenVisible
        set(value) {
            _isQuickOpScreenVisible = value
        }

    private var _isEpgScreenVisible by mutableStateOf(false)
    var isEpgScreenVisible
        get() = _isEpgScreenVisible
        set(value) {
            _isEpgScreenVisible = value
        }

    private var _isChannelLineScreenVisible by mutableStateOf(false)
    var isChannelLineScreenVisible
        get() = _isChannelLineScreenVisible
        set(value) {
            _isChannelLineScreenVisible = value
        }

    private var _isVideoPlayerDisplayModeScreenVisible by mutableStateOf(false)
    var isVideoPlayerDisplayModeScreenVisible
        get() = _isVideoPlayerDisplayModeScreenVisible
        set(value) {
            _isVideoPlayerDisplayModeScreenVisible = value
        }

    private var _isVideoTracksScreenVisible by mutableStateOf(false)
    var isVideoTracksScreenVisible
        get() = _isVideoTracksScreenVisible
        set(value) {
            _isVideoTracksScreenVisible = value
        }

    private var _isAudioTracksScreenVisible by mutableStateOf(false)
    var isAudioTracksScreenVisible
        get() = _isAudioTracksScreenVisible
        set(value) {
            _isAudioTracksScreenVisible = value
        }

    private var _isSubtitleTracksScreenVisible by mutableStateOf(false)
    var isSubtitleTracksScreenVisible
        get() = _isSubtitleTracksScreenVisible
        set(value) {
            _isSubtitleTracksScreenVisible = value
        }

    init {
        val channelGroupList = channelGroupListProvider()

        val lastPlayed = settingsViewModel.iptvChannelLastPlay
        // Resume with current playlist metadata, including a changed subscription UA.
        val currentVersion = channelGroupList.channelList.resolveChannel(lastPlayed)
        changeCurrentChannel(currentVersion ?: lastPlayed.isEmptyOrElse {
            channelGroupList.channelFirstOrNull() ?: Channel.EMPTY
        })

        videoPlayerState.onReady {
            settingsViewModel.recordChannelWatch(currentChannel)
            settingsViewModel.iptvChannelLinePlayableUrlList += currentChannelLine.url
            settingsViewModel.iptvChannelLinePlayableHostList += currentChannelLine.url.urlHost()
        }

        videoPlayerState.onError {
            if (_currentPlaybackEpgProgramme != null) return@onError

            settingsViewModel.iptvChannelLinePlayableUrlList -= currentChannelLine.url
            settingsViewModel.iptvChannelLinePlayableHostList -= currentChannelLine.url.urlHost()

            if (_currentChannelLineIdx < _currentChannel.lineList.size - 1) {
                changeCurrentChannel(_currentChannel, _currentChannelLineIdx + 1)
            }
        }

        videoPlayerState.onInterrupt {
            if (_currentPlaybackEpgProgramme != null) {
                reloadPlaybackAt(videoPlayerState.currentPosition, videoPlayerState.playRequested)
                return@onInterrupt
            }
            changeCurrentChannel(
                _currentChannel,
                _currentChannelLineIdx,
                _currentPlaybackEpgProgramme
            )
        }

        videoPlayerState.onPosition { position ->
            if (_currentPlaybackEpgProgramme != null)
                _currentPlaybackEpgProgramme = programmeAt(position, _currentPlaybackEpgProgramme)
        }
        videoPlayerState.onPlaybackWindowEnded { position -> scheduleContinuation(position) }

        videoPlayerState.onIsBuffering { isBuffering ->
            if (isBuffering) {
                _isTempChannelScreenVisible = true
            } else {
                _tempChannelScreenHideJob?.cancel()
                _tempChannelScreenHideJob = coroutineScope.launch {
                    val name = _currentChannel.name
                    val lineIdx = _currentChannelLineIdx
                    delay(Constants.UI_TEMP_CHANNEL_SCREEN_SHOW_DURATION)
                    if (name == _currentChannel.name && lineIdx == _currentChannelLineIdx) {
                        _isTempChannelScreenVisible = false
                    }
                }
            }
        }
    }

    private fun getPrevFavoriteChannel(): Channel? {
        if (!settingsViewModel.iptvChannelFavoriteListVisible) return null

        val channelGroupList = channelGroupListProvider()
        val favoriteChannelList = favoriteChannelListProvider()

        if (_currentChannel !in favoriteChannelList) return null

        val currentIdx = favoriteChannelList.indexOf(_currentChannel)

        return favoriteChannelList.getOrElse(currentIdx - 1) {
            if (settingsViewModel.iptvChannelChangeListLoop) favoriteChannelList.lastOrNull()
            else channelGroupList.channelLastOrNull()
        }
    }

    private fun getNextFavoriteChannel(): Channel? {
        if (!settingsViewModel.iptvChannelFavoriteListVisible) return null

        val channelGroupList = channelGroupListProvider()
        val favoriteChannelList = favoriteChannelListProvider()

        if (_currentChannel !in favoriteChannelList) return null

        val currentIdx = favoriteChannelList.indexOf(_currentChannel)

        return favoriteChannelList.getOrElse(currentIdx + 1) {
            if (settingsViewModel.iptvChannelChangeListLoop) favoriteChannelList.firstOrNull()
            else channelGroupList.channelFirstOrNull()
        }
    }

    private fun getPrevChannel(): Channel {
        return getPrevFavoriteChannel() ?: run {
            val channelGroupList = channelGroupListProvider()
            return if (settingsViewModel.iptvChannelChangeListLoop) {
                val group =
                    channelGroupList.getOrElse(channelGroupList.channelGroupIdx(_currentChannel)) { channelGroupList.first() }
                val currentIdx = group.channelList.indexOf(_currentChannel)
                group.channelList.getOrElse(currentIdx - 1) { group.channelList.last() }
            } else {
                val currentIdx = channelGroupList.channelIdx(_currentChannel)
                channelGroupList.channelList.getOrElse(currentIdx - 1) {
                    channelGroupList.channelLastOrNull() ?: Channel()
                }
            }
        }
    }

    private fun getNextChannel(): Channel {
        return getNextFavoriteChannel() ?: run {
            val channelGroupList = channelGroupListProvider()
            return if (settingsViewModel.iptvChannelChangeListLoop) {
                val group =
                    channelGroupList.getOrElse(channelGroupList.channelGroupIdx(_currentChannel)) { channelGroupList.first() }
                val currentIdx = group.channelList.indexOf(_currentChannel)
                group.channelList.getOrElse(currentIdx + 1) { group.channelList.first() }
            } else {
                val currentIdx = channelGroupList.channelIdx(_currentChannel)
                channelGroupList.channelList.getOrElse(currentIdx + 1) {
                    channelGroupList.channelFirstOrNull() ?: Channel()
                }
            }
        }
    }

    private fun getLineIdx(lineList: ChannelLineList, lineIdx: Int? = null): Int {
        if (lineList.isEmpty()) return 0
        val idx = if (lineIdx == null) {
            val idx = lineList.indexOfFirst { line ->
                settingsViewModel.iptvChannelLinePlayableUrlList.contains(line.url)
            }

            if (idx < 0) {
                lineList.indexOfFirst { line ->
                    settingsViewModel.iptvChannelLinePlayableHostList.contains(line.url.urlHost())
                }
            } else idx
        } else (lineIdx + lineList.size) % lineList.size

        return max(0, min(idx, lineList.size - 1))
    }

    fun changeCurrentChannel(
        channel: Channel,
        lineIdx: Int? = null,
        playbackEpgProgramme: EpgProgramme? = null,
    ) {
        val selectedLineIdx = getLineIdx(channel.lineList, lineIdx)
        val selectedLine = channel.lineList.getOrNull(selectedLineIdx) ?: return
        val playbackRequest = if (playbackEpgProgramme != null) {
            ChannelPlayback.reloadRequest(selectedLine,
                PlaybackRange(playbackEpgProgramme.startAt, playbackEpgProgramme.endAt), playbackEpgProgramme.startAt,
                now = nowProvider(), continuous = true)
                ?: run {
                    Snackbar.show("无法生成回看地址，请检查直播源的回看配置")
                    return
                }
        } else null
        val url = playbackRequest?.line?.url ?: selectedLine.playableUrl

        settingsViewModel.iptvChannelLastPlay = channel

        val positionChanged = channel.groupName != _currentChannel.groupName || channel.index != _currentChannel.index
        // Reuse the stream for another occurrence, but retain the existing
        // explicit reselect/reload behavior when the selected position is unchanged.
        if (channel == _currentChannel && selectedLineIdx == _currentChannelLineIdx &&
            playbackEpgProgramme == _currentPlaybackEpgProgramme &&
            (lineIdx == _currentChannelLineIdx || positionChanged)) {
            _currentChannel = channel
            restoreDisplayMode()
            if (positionChanged) settingsViewModel.recordChannelWatch(channel)
            return
        }

        if (channel == _currentChannel && lineIdx != _currentChannelLineIdx) {
            settingsViewModel.iptvChannelLinePlayableUrlList -= currentChannelLine.url
            settingsViewModel.iptvChannelLinePlayableHostList -= currentChannelLine.url.urlHost()
        }

        cancelContinuation()
        playbackSessionStartAt = playbackEpgProgramme?.startAt
        _isTempChannelScreenVisible = true

        _currentChannel = channel
        _currentChannelLineIdx = selectedLineIdx

        _currentPlaybackEpgProgramme = playbackEpgProgramme

        val line = currentChannelLine.copy(url = url)

        log.d("播放${_currentChannel.name}（${_currentChannelLineIdx + 1}/${_currentChannel.lineList.size}）: $line")

        if (line.url.startsWith("webview://")) {
            videoPlayerState.metadata = VideoPlayer.Metadata()
            videoPlayerState.stop()
        } else {
            playbackRequestId++
            videoPlayerState.prepare(line, playbackRequest?.range)
        }
        restoreDisplayMode()
    }

    private fun restoreDisplayMode() {
        videoPlayerState.displayMode = settingsViewModel.channelDisplayMode(
            currentChannel, channelGroupListProvider().channelList)
    }

    fun playProgrammeFromStart(programme: EpgProgramme) {
        if (!PlaybackProgrammeNavigation.canStart(programme, currentChannelLine, nowProvider())) return
        val current = currentPlaybackEpgProgramme
        if (current != null && current.startAt == programme.startAt && current.endAt == programme.endAt) {
            // Reselecting the same programme normally reuses playback; restart must really seek.
            seekTo(programme.startAt, programme)
        } else {
            changeCurrentChannel(currentChannel, currentChannelLineIdx, programme)
        }
    }

    fun seekTo(position: Long, liveProgramme: EpgProgramme? = null, absoluteTarget: Boolean = videoPlayerState.positionIsAbsolute) {
        cancelContinuation()
        // An EPG clock is a wall-clock target, even when the live engine exposes no timeline.
        // Never pass that timestamp to a relative seekTo (including when URL reload fails).
        if (absoluteTarget && !videoPlayerState.positionIsAbsolute) {
            if (!reloadPlaybackAt(position, videoPlayerState.playRequested, currentPlaybackEpgProgramme ?: liveProgramme))
                Snackbar.show("当前播放窗口无法定位，且频道未提供可用的回看地址")
            return
        }
        val startingTimeshift = currentPlaybackEpgProgramme == null && videoPlayerState.positionIsAbsolute &&
            position < nowProvider() - 1_000 && ChannelPlayback.isSupported(currentChannelLine)
        val canSeek = videoPlayerState.instance.canSeekTo(position)
        if (!videoPlayerState.positionIsAbsolute ||
            (!settingsViewModel.videoPlayerPlaybackReload && canSeek && !startingTimeshift)) {
            videoPlayerState.seekTo(position)
            return
        }
        if (!reloadPlaybackAt(position, videoPlayerState.playRequested,
                currentPlaybackEpgProgramme ?: liveProgramme)) {
            if (canSeek) videoPlayerState.seekTo(position)
            else Snackbar.show("当前播放窗口无法定位，且频道未提供可用的回看地址")
        }
    }

    private fun programmeAt(position: Long, fallback: EpgProgramme?): EpgProgramme {
        if (fallback != null && fallback.title != "时移播放" && position >= fallback.startAt && position < fallback.endAt)
            return fallback
        epgListProvider().match(currentChannel)?.programmeList?.firstOrNull {
            position >= it.startAt && position < it.endAt
        }?.let { return it }
        val start = playbackSessionStartAt ?: minOf(fallback?.startAt ?: position, position)
        return EpgProgramme(start, maxOf(start + 1, position + 1, nowProvider()), "时移播放")
    }

    private fun reloadPlaybackAt(position: Long, shouldPlay: Boolean, fallback: EpgProgramme? = currentPlaybackEpgProgramme): Boolean {
        val programme = programmeAt(position, fallback)
        val request = ChannelPlayback.reloadRequest(currentChannelLine,
            PlaybackRange(programme.startAt, programme.endAt), position, now = nowProvider(), continuous = true) ?: return false
        val displayMode = videoPlayerState.displayMode
        if (playbackSessionStartAt == null) playbackSessionStartAt = programme.startAt
        _currentPlaybackEpgProgramme = programme
        playbackRequestId++
        videoPlayerState.prepare(request.line, request.range)
        videoPlayerState.displayMode = displayMode
        if (!shouldPlay) videoPlayerState.pause()
        return true
    }

    private fun cancelContinuation() {
        playbackGeneration++
        continuationJob?.cancel()
        continuationJob = null
        continuation.reset()
        evaluatedEndRequestId = null
    }

    private fun scheduleContinuation(position: Long) {
        if (_currentPlaybackEpgProgramme == null || !videoPlayerState.playRequested) return
        continuationJob?.cancel()
        if (evaluatedEndRequestId != playbackRequestId && !continuation.accept(position)) {
            continuation.reset()
            videoPlayerState.pause()
            Snackbar.show("暂时没有新的录像内容，请稍后继续播放")
            return
        }
        evaluatedEndRequestId = playbackRequestId
        val generation = playbackGeneration
        continuationJob = coroutineScope.launch {
            // Do not replace the media source inside a native completion callback.
            yield()
            while (generation == playbackGeneration && videoPlayerState.playRequested) {
                val wait = continuation.waitMs(position, nowProvider())
                if (wait > 0) { delay(minOf(wait, 1_000)); continue }
                if (!reloadPlaybackAt(position, true)) {
                    videoPlayerState.pause()
                    Snackbar.show("无法续接录像，请检查频道的回看配置")
                }
                return@launch
            }
        }
    }

    fun changeDisplayMode(mode: VideoPlayerDisplayMode) {
        settingsViewModel.rememberChannelDisplayMode(currentChannel, channelGroupListProvider().channelList, mode)
        videoPlayerState.displayMode = mode
    }

    fun followGlobalDisplayMode() {
        settingsViewModel.rememberChannelDisplayMode(currentChannel, channelGroupListProvider().channelList, null)
        restoreDisplayMode()
    }

    fun changeCurrentChannelToPrev() {
        changeCurrentChannel(getPrevChannel())
    }

    fun changeCurrentChannelToNext() {
        changeCurrentChannel(getNextChannel())
    }

    fun reverseEpgProgrammeOrNot(channel: Channel, programme: EpgProgramme) {
        val reverse = settingsViewModel.epgChannelReserveList.firstOrNull {
            it.test(channel, programme)
        }

        if (reverse != null) {
            settingsViewModel.epgChannelReserveList =
                EpgProgrammeReserveList(settingsViewModel.epgChannelReserveList - reverse)
            Snackbar.show("取消预约：${reverse.channel} - ${reverse.programme}")
        } else {
            val newReserve = EpgProgrammeReserve(
                channel = channel.name,
                programme = programme.title,
                startAt = programme.startAt,
                endAt = programme.endAt,
            )

            settingsViewModel.epgChannelReserveList =
                EpgProgrammeReserveList(settingsViewModel.epgChannelReserveList + newReserve)
            Snackbar.show("已预约：${channel.name} - ${programme.title}")
        }
    }

    fun supportPlayback(
        channel: Channel = _currentChannel,
        lineIdx: Int? = _currentChannelLineIdx,
    ): Boolean {
        val currentLineIdx = getLineIdx(channel.lineList, lineIdx)
        return channel.lineList.getOrNull(currentLineIdx)?.let(ChannelPlayback::isSupported) == true
    }
}

@Composable
fun rememberMainContentState(
    coroutineScope: CoroutineScope = rememberCoroutineScope(),
    videoPlayerState: VideoPlayerState = rememberVideoPlayerState(),
    channelGroupListProvider: () -> ChannelGroupList = { ChannelGroupList() },
    favoriteChannelListProvider: () -> ChannelList = { ChannelList() },
    settingsViewModel: SettingsViewModel = settingsVM,
    epgListProvider: () -> EpgList = { EpgList() },
): MainContentState {
    val favoriteChannelListProviderUpdated by rememberUpdatedState(favoriteChannelListProvider)
    val epgListProviderUpdated by rememberUpdatedState(epgListProvider)

    return remember(settingsVM.videoPlayerCore) {
        MainContentState(
            coroutineScope = coroutineScope,
            videoPlayerState = videoPlayerState,
            channelGroupListProvider = channelGroupListProvider,
            favoriteChannelListProvider = favoriteChannelListProviderUpdated,
            settingsViewModel = settingsViewModel,
            epgListProvider = { epgListProviderUpdated() },
        )
    }
}
