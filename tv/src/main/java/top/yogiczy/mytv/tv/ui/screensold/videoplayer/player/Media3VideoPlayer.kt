package top.yogiczy.mytv.tv.ui.screensold.videoplayer.player

import android.content.Context
import android.net.Uri
import android.view.SurfaceView
import android.view.TextureView
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.HttpMediaDrmCallback
import androidx.media3.exoplayer.drm.MediaDrmCallback
import androidx.media3.exoplayer.drm.ExoMediaDrm
import java.util.UUID
import androidx.media3.exoplayer.drm.LocalMediaDrmCallback
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.util.EventLogger
import com.google.common.collect.ImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import top.yogiczy.mytv.core.data.entities.channel.ChannelLine
import top.yogiczy.mytv.core.data.media.ClearKeyLicense
import top.yogiczy.mytv.core.data.media.PlaybackRange
import top.yogiczy.mytv.core.data.media.Av3aHeader
import top.yogiczy.mytv.core.data.network.ClearKeyLicenseFetcher
import top.yogiczy.mytv.core.data.network.HttpBasicAuth
import top.yogiczy.mytv.core.data.network.HttpBasicAuthInterceptor
import top.yogiczy.mytv.core.data.network.PlaybackCookieJar
import top.yogiczy.mytv.core.data.network.PlaybackCookieInterceptor
import top.yogiczy.mytv.core.data.network.TrustAllSSLSocketFactory
import top.yogiczy.mytv.core.util.utils.toHeaders
import top.yogiczy.mytv.tv.ui.utils.Configs
import top.yogiczy.mytv.tv.ui.screensold.videoplayer.player.mmt.MmtExtractorsFactory
import top.yogiczy.mytv.tv.ui.screensold.videoplayer.player.av3a.Av3aHlsExtractorFactory
import top.yogiczy.mytv.tv.ui.screensold.videoplayer.player.rtsp.RtspSourceFactory
import java.util.concurrent.TimeUnit

@OptIn(UnstableApi::class)
class Media3VideoPlayer(
    private val context: Context,
    private val coroutineScope: CoroutineScope,
    private val mediaSourceFactory: MediaSource.Factory? = null,
) : VideoPlayer(coroutineScope) {

    private var videoPlayer = getPlayer()

    private var softDecode: Boolean? = null
    private var surfaceView: SurfaceView? = null
    private var textureView: TextureView? = null

    private var currentChannelLine = ChannelLine()
    private var playbackCookies = PlaybackCookieJar()
    private val contentTypeAttempts = mutableMapOf<Int, Boolean>()
    private var updatePositionJob: Job? = null
    private var playbackRange: PlaybackRange? = null
    private var playbackPositionAt: Long? = null
    private var pendingPlaybackPositionAt: Long? = null
    private var playbackShouldPlay = true
    private var playbackEnded = false
    private var playbackUnavailable = false
    private var playbackRetryCount = 0

    private val onCuesListeners = mutableListOf<(ImmutableList<Cue>) -> Unit>()

    private fun triggerCues(cues: ImmutableList<Cue>) {
        onCuesListeners.forEach { it(cues) }
    }

    fun onCues(listener: (ImmutableList<Cue>) -> Unit) {
        onCuesListeners.add(listener)
    }

    private fun getPlayer(): ExoPlayer {
        MimeTypes.registerCustomMimeType(Av3aHeader.MIME_TYPE, "av3a", C.TRACK_TYPE_AUDIO)
        val renderersFactory =
            DefaultRenderersFactory(context)
                .setExtensionRendererMode(
                    if (softDecode ?: Configs.videoPlayerForceAudioSoftDecode)
                        DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
                    else DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
                )
                .setEnableDecoderFallback(true)

        val trackSelector = DefaultTrackSelector(context).apply {
            parameters = buildUponParameters()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false)
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setMaxVideoSize(Integer.MAX_VALUE, Integer.MAX_VALUE)
                .setForceHighestSupportedBitrate(true)
                .setPreferredTextLanguages("zh")
                .build()
        }

        // MediaCodecVideoRenderer.skipMultipleFramesOnSameVsync =
        //     Configs.videoPlayerSkipMultipleFramesOnSameVSync
        return ExoPlayer.Builder(context)
            .setRenderersFactory(renderersFactory)
            .setTrackSelector(trackSelector)
            .build()
            .apply { playWhenReady = true }
    }

    private fun reInitPlayer() {
        rememberPlaybackPosition()
        onCuesListeners.clear()
        videoPlayer.removeListener(playerListener)
        videoPlayer.removeAnalyticsListener(metadataListener)
        videoPlayer.removeAnalyticsListener(eventLogger)
        videoPlayer.stop()
        videoPlayer.release()

        videoPlayer = getPlayer()

        videoPlayer.addListener(playerListener)
        videoPlayer.addAnalyticsListener(metadataListener)
        videoPlayer.addAnalyticsListener(eventLogger)

        surfaceView?.let { setVideoSurfaceView(it) }
        textureView?.let { setVideoTextureView(it) }
        prepare()
    }

    private fun getDataSourceFactory(usePlaybackCookies: Boolean = true): DefaultDataSource.Factory {
        val authentication = HttpBasicAuth.parse(currentChannelLine.playableUrl)
        val userAgent = currentChannelLine.httpUserAgent ?: Configs.videoPlayerUserAgent
        val httpFactory: HttpDataSource.Factory = if (usePlaybackCookies || authentication != null) {
            // OkHttp removes Authorization on redirects to another origin. The interceptor also
            // limits inherited playlist credentials to segment/key URLs from the same origin.
            val clientBuilder = OkHttpClient.Builder()
                .addInterceptor(HttpBasicAuthInterceptor(authentication))
                .connectTimeout(Configs.videoPlayerLoadTimeout, TimeUnit.MILLISECONDS)
                .readTimeout(Configs.videoPlayerLoadTimeout, TimeUnit.MILLISECONDS)
            if (usePlaybackCookies) {
                clientBuilder.addNetworkInterceptor(PlaybackCookieInterceptor(playbackCookies))
            }
            if (authentication != null) clientBuilder.sslSocketFactory(
                    TrustAllSSLSocketFactory.sslSocketFactory,
                    TrustAllSSLSocketFactory.trustManager,
                )
                .hostnameVerifier { _, _ -> true }
            OkHttpDataSource.Factory(clientBuilder.build()).setUserAgent(userAgent)
        } else {
            DefaultHttpDataSource.Factory().apply {
                setUserAgent(userAgent)
                setConnectTimeoutMs(Configs.videoPlayerLoadTimeout.toInt())
                setReadTimeoutMs(Configs.videoPlayerLoadTimeout.toInt())
                setKeepPostFor302Redirects(true)
                setAllowCrossProtocolRedirects(true)
            }
        }
        httpFactory.setDefaultRequestProperties(Configs.videoPlayerHeaders.toHeaders())
        return DefaultDataSource.Factory(
            context,
            httpFactory,
        )
    }

    private fun getMediaSource(contentType: Int? = null): MediaSource? {
        val playableUrl = currentChannelLine.playableUrl
        val uri = Uri.parse(HttpBasicAuth.parse(playableUrl)?.url?.toString() ?: playableUrl)
        val mediaItem = MediaItem.fromUri(uri)
        mediaSourceFactory?.let { return it.createMediaSource(mediaItem) }

        var contentTypeForce = contentType

        if (uri.toString().startsWith("rtp://")) {
            contentTypeForce = C.CONTENT_TYPE_RTSP
        }
        if (uri.scheme.equals("rtsp", true)) {
            contentTypeForce = C.CONTENT_TYPE_RTSP
        }

        val manifestType = currentChannelLine.manifestType?.trim()?.removeSurrounding("\"")?.lowercase()
        val forceMmt = manifestType == "mmt" || manifestType == "custom"
        if (manifestType == "mpd") {
            contentTypeForce = C.CONTENT_TYPE_DASH
        } else if (forceMmt) {
            contentTypeForce = C.CONTENT_TYPE_OTHER
        }

        val dataSourceFactory = getDataSourceFactory()
        return when (contentTypeForce ?: Util.inferContentType(uri)) {
            C.CONTENT_TYPE_HLS -> {
                HlsMediaSource.Factory(dataSourceFactory)
                    .setExtractorFactory(Av3aHlsExtractorFactory())
                    // Some playlists omit AV3A from CODECS; discover all tracks from the TS PMT.
                    .setAllowChunklessPreparation(false)
                    .createMediaSource(mediaItem)
            }

            C.CONTENT_TYPE_DASH -> {
                val factory = DashMediaSource.Factory(dataSourceFactory)
                if (ClearKeyLicense.isSupported(currentChannelLine.licenseType)) {
                    lateinit var manifestKeyIds: () -> List<UUID>
                    val licenseKey = currentChannelLine.licenseKey
                    val drmCallback = try {
                        val licenseUrl = ClearKeyLicense.remoteUrl(licenseKey)
                        if (licenseUrl == null) {
                            manifestKeyIds = { ClearKeyLicense.keyIds(ClearKeyLicense.response(licenseKey)) }
                            LocalMediaDrmCallback(ClearKeyLicense.response(licenseKey))
                        } else {
                            val headers = Configs.videoPlayerHeaders.toHeaders().toMutableMap()
                            if (headers.keys.none { it.equals("User-Agent", true) }) {
                                headers["User-Agent"] = currentChannelLine.httpUserAgent ?: Configs.videoPlayerUserAgent
                            }
                            val client = OkHttpClient.Builder()
                                .addInterceptor(HttpBasicAuthInterceptor())
                                .connectTimeout(Configs.videoPlayerLoadTimeout, TimeUnit.MILLISECONDS)
                                .readTimeout(Configs.videoPlayerLoadTimeout, TimeUnit.MILLISECONDS)
                                .build()
                            val fetcher = ClearKeyLicenseFetcher(client, headers)
                            manifestKeyIds = {
                                ClearKeyLicense.keyIds(fetcher.load(licenseUrl,
                                    """{"kids":[],"type":"temporary"}""".toByteArray(Charsets.UTF_8)))
                            }
                            // Keep the existing provisioning POST/redirect behavior independent of media cookies.
                            val provisionCallback = HttpMediaDrmCallback(licenseUrl, getDataSourceFactory(false))
                            object : MediaDrmCallback {
                                override fun executeProvisionRequest(uuid: UUID, request: ExoMediaDrm.ProvisionRequest) =
                                    provisionCallback.executeProvisionRequest(uuid, request)

                                override fun executeKeyRequest(uuid: UUID, request: ExoMediaDrm.KeyRequest) =
                                    MediaDrmCallback.Response(fetcher.load(licenseUrl, request.data))
                            }
                        }
                    } catch (_: IllegalArgumentException) {
                        triggerError(PlaybackException(
                            "Invalid ClearKey license configuration",
                            androidx.media3.common.PlaybackException.ERROR_CODE_DRM_CONTENT_ERROR,
                        ))
                        return null
                    }
                    val drmSessionManager = DefaultDrmSessionManager.Builder()
                        .setMultiSession(true)
                        .setUuidAndExoMediaDrmProvider(C.CLEARKEY_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
                        .build(drmCallback)
                    factory.setManifestParser(ClearKeyDashManifestParser(manifestKeyIds))
                    factory.setDrmSessionManagerProvider { drmSessionManager }
                } else if (WidevineLicenseCallback.isSupported(currentChannelLine.licenseType)) {
                    val client = OkHttpClient.Builder()
                        .addInterceptor(HttpBasicAuthInterceptor())
                        .connectTimeout(Configs.videoPlayerLoadTimeout, TimeUnit.MILLISECONDS)
                        .readTimeout(Configs.videoPlayerLoadTimeout, TimeUnit.MILLISECONDS)
                        .build()
                    val licenseDataSource = OkHttpDataSource.Factory(client)
                        .setUserAgent(currentChannelLine.httpUserAgent ?: Configs.videoPlayerUserAgent)
                        .setDefaultRequestProperties(Configs.videoPlayerHeaders.toHeaders())
                    val callback = try {
                        WidevineLicenseCallback.create(currentChannelLine.licenseKey, licenseDataSource,
                            Configs.videoPlayerHeaders.toHeaders())
                    } catch (_: IllegalArgumentException) {
                        triggerError(PlaybackException("Invalid Widevine license URL",
                            androidx.media3.common.PlaybackException.ERROR_CODE_DRM_CONTENT_ERROR))
                        return null
                    }
                    val manager = DefaultDrmSessionManager.Builder()
                        .setMultiSession(true)
                        .setUuidAndExoMediaDrmProvider(C.WIDEVINE_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
                        .build(callback)
                    factory.setDrmSessionManagerProvider { manager }
                }
                factory.createMediaSource(mediaItem)
            }

            C.CONTENT_TYPE_RTSP -> {
                RtspSourceFactory.create(uri,
                    currentChannelLine.httpUserAgent ?: Configs.videoPlayerUserAgent,
                    Configs.videoPlayerLoadTimeout, Configs.videoPlayerRtspTcp)
            }

            C.CONTENT_TYPE_OTHER -> {
                ProgressiveMediaSource.Factory(dataSourceFactory, MmtExtractorsFactory(forceMmt))
                    .createMediaSource(mediaItem)
            }

            else -> {
                triggerError(PlaybackException.UNSUPPORTED_TYPE)
                null
            }
        }
    }

    private fun prepare(contentType: Int? = null) {
        updatePositionJob?.cancel()
        updatePositionJob = null
        val uri = Uri.parse(currentChannelLine.playableUrl)
        val mediaSource = getMediaSource(contentType)

        if (mediaSource != null) {
            contentTypeAttempts[contentType ?: Util.inferContentType(uri)] = true
            triggerPrepared()
            if (playbackRange != null) {
                pendingPlaybackPositionAt = playbackPositionAt ?: playbackRange!!.startAt
                videoPlayer.playWhenReady = false
                videoPlayer.setMediaSource(mediaSource, 0L)
            } else {
                videoPlayer.setMediaSource(mediaSource)
            }
            videoPlayer.prepare()
            if (playbackRange == null) videoPlayer.play()
        }
    }

    private fun playbackWindow(): Timeline.Window? {
        val timeline = videoPlayer.currentTimeline
        if (timeline.isEmpty) return null
        return timeline.getWindow(videoPlayer.currentMediaItemIndex, Timeline.Window())
            .takeUnless { it.isPlaceholder }
    }

    private fun Timeline.Window.startAtOrNull(): Long? =
        windowStartTimeMs.takeUnless { it == C.TIME_UNSET }

    private fun applyPendingPlaybackPosition() {
        val range = playbackRange ?: return
        val target = pendingPlaybackPositionAt ?: return
        val window = playbackWindow() ?: return
        val position = range.windowPosition(target, window.startAtOrNull(),
            window.durationMs.takeIf { it != C.TIME_UNSET })
        if (position == null || (!window.isSeekable && position > 0)) {
            playbackUnavailable = true
            pendingPlaybackPositionAt = null
            videoPlayer.pause()
            cancelPlaybackMonitoring()
            triggerBuffering(false)
            triggerError(PlaybackException.PLAYBACK_UNAVAILABLE)
            return
        }
        pendingPlaybackPositionAt = null
        playbackPositionAt = range.absolutePosition(position, window.startAtOrNull())
        videoPlayer.seekTo(position)
        videoPlayer.playWhenReady = playbackShouldPlay
    }

    private fun rememberPlaybackPosition() {
        val range = playbackRange ?: return
        if (pendingPlaybackPositionAt != null || playbackEnded || playbackUnavailable) return
        playbackWindow()?.let {
            playbackPositionAt = range.absolutePosition(videoPlayer.currentPosition, it.startAtOrNull())
        }
    }

    private fun retryPlayback(error: androidx.media3.common.PlaybackException) {
        if (playbackUnavailable || playbackEnded) return
        if (++playbackRetryCount > 2) {
            videoPlayer.pause()
            cancelPlaybackMonitoring()
            triggerBuffering(false)
            triggerError(PlaybackException(error.errorCodeName, error.errorCode))
            return
        }
        // Reload the stable source URL (including playseek), then resume its saved UTC position.
        // This also allows an authenticated redirect to obtain a fresh destination URL.
        prepare()
    }

    private fun finishPlayback() {
        val range = playbackRange ?: return
        playbackEnded = true
        playbackShouldPlay = false
        playbackPositionAt = range.endAt
        videoPlayer.pause()
        triggerCurrentPosition(range.endAt)
        triggerBuffering(false)
        cancelPlaybackMonitoring()
    }

    private val playerListener = object : Player.Listener {
        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            applyPendingPlaybackPosition()
        }
        override fun onVideoSizeChanged(videoSize: VideoSize) {
            triggerResolution(videoSize.width, videoSize.height)
        }

        override fun onPlayerError(ex: androidx.media3.common.PlaybackException) {
            if (playbackRange != null && ex.errorCode in setOf(
                    androidx.media3.common.PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW,
                    androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FAILED,
                    androidx.media3.common.PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
                    androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                    androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
                    androidx.media3.common.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
                )) {
                retryPlayback(ex)
                return
            }
            when (ex.errorCode) {
                // 如果是直播加载位置错误，尝试重新播放
                androidx.media3.common.PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW,
                androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FAILED,
                androidx.media3.common.PlaybackException.ERROR_CODE_IO_UNSPECIFIED -> {
                    videoPlayer.seekToDefaultPosition()
                    videoPlayer.prepare()
                }

                // 当解析容器不支持时，尝试使用其他解析容器
                androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED -> {
                    videoPlayer.currentMediaItem?.localConfiguration?.uri?.let {
                        if (contentTypeAttempts[C.CONTENT_TYPE_HLS] != true) {
                            prepare(C.CONTENT_TYPE_HLS)
                        } else if (contentTypeAttempts[C.CONTENT_TYPE_DASH] != true) {
                            prepare(C.CONTENT_TYPE_DASH)
                        } else if (contentTypeAttempts[C.CONTENT_TYPE_RTSP] != true) {
                            prepare(C.CONTENT_TYPE_RTSP)
                        } else if (contentTypeAttempts[C.CONTENT_TYPE_OTHER] != true) {
                            prepare(C.CONTENT_TYPE_OTHER)
                        } else {
                            triggerError(PlaybackException.UNSUPPORTED_TYPE)
                        }
                    }
                }

                androidx.media3.common.PlaybackException.ERROR_CODE_DECODER_INIT_FAILED -> {
                    if (softDecode == true) {
                        triggerError(
                            PlaybackException(
                                ex.errorCodeName.replace("ERROR_CODE", "MEDIA3_ERROR"),
                                ex.errorCode
                            )
                        )
                    } else {
                        softDecode = true
                        reInitPlayer()
                    }
                }

                else -> {
                    triggerError(
                        PlaybackException(
                            ex.errorCodeName.replace("ERROR_CODE", "MEDIA3_ERROR"),
                            ex.errorCode
                        )
                    )
                }
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackUnavailable) return
            if (playbackState == Player.STATE_BUFFERING) {
                triggerError(null)
                triggerBuffering(true)
            } else if (playbackState == Player.STATE_READY) {
                applyPendingPlaybackPosition()
                if (playbackUnavailable || pendingPlaybackPositionAt != null) return
                triggerReady()
                triggerDuration(playbackRange?.durationMs ?: videoPlayer.duration)

                updatePositionJob?.cancel()
                updatePositionJob = coroutineScope.launch {
                    while (true) {
                        val range = playbackRange
                        if (range != null && !playbackEnded && pendingPlaybackPositionAt == null) {
                            rememberPlaybackPosition()
                            val position = playbackPositionAt ?: range.startAt
                            triggerCurrentPosition(position)
                            if (position >= range.endAt) finishPlayback()
                        } else if (range == null) {
                            val liveOffset = videoPlayer.currentLiveOffset
                            triggerCurrentPosition(if (liveOffset != C.TIME_UNSET)
                                System.currentTimeMillis() - liveOffset else videoPlayer.currentPosition)
                        }
                        delay(500)
                    }
                }
            }

            if (playbackState != Player.STATE_BUFFERING) {
                triggerBuffering(false)
            }

            if (playbackState == Player.STATE_ENDED) {
                if (playbackRange != null) finishPlayback()
                else {
                    videoPlayer.seekToDefaultPosition()
                    videoPlayer.prepare()
                }
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            triggerIsPlayingChanged(isPlaying)
        }

        override fun onTracksChanged(tracks: Tracks) {
            metadata = metadata.copy(
                videoTracks = emptyList(),
                audioTracks = emptyList(),
                subtitleTracks = emptyList()
            )
            triggerMetadata(metadata)

            val videoFormats = videoPlayer.currentTracks.groups
                .filter { it.mediaTrackGroup.type == C.TRACK_TYPE_VIDEO }
                .flatMap { group ->
                    List(group.mediaTrackGroup.length) { trackIndex ->
                        group.mediaTrackGroup
                            .getFormat(trackIndex)
                            .toVideoMetadata()
                            .copy(isSelected = group.isTrackSelected(trackIndex))
                    }
                }
                .mapIndexed { index, metadata ->
                    metadata.copy(index = index)
                }

            metadata = metadata.copy(videoTracks = videoFormats)

            val audioFormats = videoPlayer.currentTracks.groups
                .filter { it.mediaTrackGroup.type == C.TRACK_TYPE_AUDIO }
                .flatMap { group ->
                    List(group.mediaTrackGroup.length) { trackIndex ->
                        group.mediaTrackGroup
                            .getFormat(trackIndex)
                            .toAudioMetadata()
                            .copy(isSelected = group.isTrackSelected(trackIndex))
                    }
                }
                .mapIndexed { index, metadata ->
                    metadata.copy(index = index)
                }

            metadata = metadata.copy(audioTracks = audioFormats)

            val subtitleFormats = videoPlayer.currentTracks.groups
                .filter { it.mediaTrackGroup.type == C.TRACK_TYPE_TEXT }
                .flatMap { group ->
                    List(group.mediaTrackGroup.length) { trackIndex ->
                        group.mediaTrackGroup
                            .getFormat(trackIndex)
                            // Text tracks may be subtitles, captions, combined roles, or untagged.
                            // Keep the same flattened list used by fromIndexFindTrack when selecting.
                            .toSubtitleMetadata()
                            .copy(isSelected = group.isTrackSelected(trackIndex))
                    }
                }
                .mapIndexed { index, metadata ->
                    metadata.copy(index = index)
                }

            metadata = metadata.copy(
                subtitleTracks = subtitleFormats,
                subtitle = subtitleFormats.firstOrNull { it.isSelected == true },
            )

            triggerMetadata(metadata)
        }

        override fun onCues(cueGroup: CueGroup) {
            triggerCues(cueGroup.cues)
        }
    }

    private fun Int.fromIndexFindTrack(type: @C.TrackType Int): Pair<TrackGroup, Int> {
        val groups = videoPlayer.currentTracks.groups
            .filter { group -> group.mediaTrackGroup.type == type }
            .map { it.mediaTrackGroup }

        var trackCount = 0
        val group = groups.first { group ->
            trackCount += group.length
            this < trackCount
        }

        val trackIndex = this - (trackCount - group.length)

        return Pair(group, trackIndex)
    }

    private fun Format.toVideoMetadata(video: Metadata.Video? = null): Metadata.Video {
        return (video ?: Metadata.Video()).copy(
            width = width,
            height = height,
            color = colorInfo?.toLogString(),
            frameRate = frameRate,
            // TODO 帧率、比特率目前是从tag中获取，有的返回空，后续需要实时计算
            bitrate = bitrate,
            mimeType = sampleMimeType,
        )
    }

    private fun Format.toAudioMetadata(audio: Metadata.Audio? = null): Metadata.Audio {
        return (audio ?: Metadata.Audio()).copy(
            channels = channelCount,
            channelsLabel = if (sampleMimeType == Av3aHeader.MIME_TYPE) "菁彩声" else null,
            sampleRate = sampleRate,
            bitrate = bitrate,
            mimeType = sampleMimeType,
            language = language,
        )
    }

    private fun Format.toSubtitleMetadata(subtitle: Metadata.Subtitle? = null): Metadata.Subtitle {
        return (subtitle ?: Metadata.Subtitle()).copy(
            bitrate = bitrate,
            mimeType = sampleMimeType,
            language = language,
            label = label,
        )
    }

    private val metadataListener = object : AnalyticsListener {
        override fun onVideoInputFormatChanged(
            eventTime: AnalyticsListener.EventTime,
            format: Format,
            decoderReuseEvaluation: DecoderReuseEvaluation?,
        ) {
            metadata = metadata.copy(video = format.toVideoMetadata(metadata.video))
            triggerMetadata(metadata)
        }

        override fun onVideoDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long,
        ) {
            metadata = metadata.copy(
                video = (metadata.video ?: Metadata.Video()).copy(decoder = decoderName)
            )

            triggerMetadata(metadata)
        }

        override fun onAudioInputFormatChanged(
            eventTime: AnalyticsListener.EventTime,
            format: Format,
            decoderReuseEvaluation: DecoderReuseEvaluation?,
        ) {
            metadata = metadata.copy(audio = format.toAudioMetadata(metadata.audio))
            triggerMetadata(metadata)
        }

        override fun onAudioDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long,
        ) {
            metadata = metadata.copy(
                audio = (metadata.audio ?: Metadata.Audio()).copy(decoder = decoderName)
            )

            triggerMetadata(metadata)
        }
    }

    private val eventLogger = EventLogger()

    override fun initialize() {
        super.initialize()
        videoPlayer.addListener(playerListener)
        videoPlayer.addAnalyticsListener(metadataListener)
        videoPlayer.addAnalyticsListener(eventLogger)
    }

    override fun release() {
        playbackCookies.clear()
        onCuesListeners.clear()
        videoPlayer.removeListener(playerListener)
        videoPlayer.removeAnalyticsListener(metadataListener)
        videoPlayer.removeAnalyticsListener(eventLogger)
        videoPlayer.stop()
        videoPlayer.release()
        super.release()
    }

    override fun prepare(line: ChannelLine) {
        prepare(line, null)
    }

    override fun prepare(line: ChannelLine, playbackRange: PlaybackRange?) {
        if (Configs.videoPlayerStopPreviousMediaItem)
            videoPlayer.stop()

        contentTypeAttempts.clear()
        currentChannelLine = line
        // A new channel/programme must not inherit another playback's authenticated session.
        // Internal reconnects and decoder fallback reuse the current jar.
        playbackCookies = PlaybackCookieJar()
        this.playbackRange = playbackRange
        playbackPositionAt = playbackRange?.startAt
        pendingPlaybackPositionAt = playbackRange?.startAt
        playbackShouldPlay = true
        playbackEnded = false
        playbackUnavailable = false
        playbackRetryCount = 0
        prepare(null)
    }

    override fun play() {
        playbackShouldPlay = true
        if (playbackUnavailable) return
        if (playbackEnded || videoPlayer.playerError != null) {
            if (playbackEnded) playbackPositionAt = playbackRange?.startAt
            playbackEnded = false
            playbackRetryCount = 0
            prepare()
        } else if (pendingPlaybackPositionAt == null) videoPlayer.play()
    }

    override fun pause() {
        playbackShouldPlay = false
        rememberPlaybackPosition()
        videoPlayer.pause()
        cancelPlaybackMonitoring()
    }

    override fun seekTo(position: Long) {
        val range = playbackRange
        if (range == null) videoPlayer.seekTo(position)
        else {
            playbackPositionAt = position.coerceIn(range.startAt, range.endAt)
            playbackEnded = false
            pendingPlaybackPositionAt = playbackPositionAt
            if (playbackPositionAt == range.endAt) finishPlayback()
            else applyPendingPlaybackPosition()
        }
    }

    override fun setVolume(volume: Float) {
        videoPlayer.volume = volume
    }

    override fun getVolume(): Float {
        return videoPlayer.volume
    }

    override fun stop() {
        videoPlayer.stop()
        updatePositionJob?.cancel()
        super.stop()
    }

    override fun selectVideoTrack(track: Metadata.Video?) {
        if (track?.index == null) {
            videoPlayer.trackSelectionParameters = videoPlayer.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
                .build()

            return
        }

        val (group, trackIndex) = track.index.fromIndexFindTrack(C.TRACK_TYPE_VIDEO)

        videoPlayer.trackSelectionParameters = videoPlayer.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false)
            .setOverrideForType(TrackSelectionOverride(group, trackIndex))
            .build()
    }

    override fun selectAudioTrack(track: Metadata.Audio?) {
        if (track?.index == null) {
            videoPlayer.trackSelectionParameters = videoPlayer.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                .build()

            return
        }

        val (group, trackIndex) = track.index.fromIndexFindTrack(C.TRACK_TYPE_AUDIO)

        videoPlayer.trackSelectionParameters = videoPlayer.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
            .setOverrideForType(TrackSelectionOverride(group, trackIndex))
            .build()
    }

    override fun selectSubtitleTrack(track: Metadata.Subtitle?) {
        if (track == null) {
            videoPlayer.trackSelectionParameters = videoPlayer.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()

            return
        }

        val (group, trackIndex) = (track.index ?: return).fromIndexFindTrack(C.TRACK_TYPE_TEXT)
        videoPlayer.trackSelectionParameters = videoPlayer.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .setOverrideForType(TrackSelectionOverride(group, trackIndex))
            .build()
    }

    override fun setVideoSurfaceView(surfaceView: SurfaceView) {
        this.surfaceView = surfaceView
        videoPlayer.setVideoSurfaceView(surfaceView)
    }

    override fun setVideoTextureView(textureView: TextureView) {
        this.textureView = textureView
        videoPlayer.setVideoTextureView(textureView)
    }
}
