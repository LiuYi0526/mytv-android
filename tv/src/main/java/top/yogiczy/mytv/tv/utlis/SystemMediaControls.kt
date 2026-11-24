package top.yogiczy.mytv.tv.utlis

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.core.graphics.drawable.toBitmap
import androidx.media3.ui.R as Media3UiResources
import coil.imageLoader
import coil.request.SuccessResult
import coil.size.Scale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import top.yogiczy.mytv.core.data.entities.epg.EpgProgramme
import top.yogiczy.mytv.core.data.media.PlaybackControllerTimeline
import top.yogiczy.mytv.tv.MainActivity
import top.yogiczy.mytv.tv.PlaybackForegroundService
import top.yogiczy.mytv.tv.R
import top.yogiczy.mytv.tv.ui.screen.channels.components.channelLogoRequest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal enum class MediaPlaybackAction(
    val label: String,
    val iconId: Int,
    private val isFrameworkIcon: Boolean = true,
) {
    PLAY("播放", android.R.drawable.ic_media_play),
    PAUSE("暂停", android.R.drawable.ic_media_pause),
    PREVIOUS_CHANNEL("上一频道", android.R.drawable.ic_media_previous),
    NEXT_CHANNEL("下一频道", android.R.drawable.ic_media_next),
    ADD_FAVORITE("收藏频道", R.drawable.ic_media_favorite_outline, isFrameworkIcon = false),
    REMOVE_FAVORITE("取消收藏", R.drawable.ic_media_favorite, isFrameworkIcon = false),
    OPEN_PLAYER("打开播放页", R.drawable.ic_media_open_player, isFrameworkIcon = false),
    STOP("终止播放", Media3UiResources.drawable.exo_icon_stop, isFrameworkIcon = false);

    val broadcastAction = "top.yogiczy.mytv.tv.media.$name"

    fun icon(context: Context): Icon =
        Icon.createWithResource(if (isFrameworkIcon) "android" else context.packageName, iconId)

    fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(broadcastAction).setPackage(context.packageName),
        PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        fun forPlayback(playRequested: Boolean) = if (playRequested) PAUSE else PLAY
    }
}

/** Publishes controls for the channel player, including picture-in-picture and lock screen playback. */
internal class SystemMediaControls(
    private val context: Context,
    private val scope: CoroutineScope,
    private val supportsLockScreenPlayback: Boolean,
    private val onAction: (MediaPlaybackAction) -> Unit,
) {
    data class State(
        val channelName: String,
        val programme: EpgProgramme?,
        val logoUrls: List<String>,
        val playbackState: Int,
        val progress: PlaybackControllerTimeline.Progress?,
        val isFavorite: Boolean?,
        val onLockScreen: Boolean,
    ) {
        val leadingAction: MediaPlaybackAction
            get() = when (isFavorite) {
                true -> MediaPlaybackAction.REMOVE_FAVORITE
                false -> MediaPlaybackAction.ADD_FAVORITE
                null -> MediaPlaybackAction.OPEN_PLAYER
            }
    }

    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val openPlayerIntent = Intent(context, MainActivity::class.java)
        .setAction(Intent.ACTION_MAIN)
        .addCategory(Intent.CATEGORY_LAUNCHER)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    private val openPlayer = PendingIntent.getActivity(context, 0, openPlayerIntent, PendingIntent.FLAG_IMMUTABLE)
    private var session: MediaSession? = null
    private var state: State? = null
    private var artwork: Bitmap? = null
    private var artworkJob: Job? = null
    private var playbackService: PlaybackForegroundService? = null
    private var serviceBound = false
    private val playbackConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            if (!serviceBound) return
            playbackService = (binder as PlaybackForegroundService.LocalBinder).service
            val activeSession = session ?: return
            val activeState = state ?: return
            publishNotification(activeSession, activeState)
        }

        override fun onServiceDisconnected(name: ComponentName) {
            playbackService = null
        }
    }

    fun publish(state: State) {
        val previous = this.state
        val artworkChanged = previous?.logoUrls != state.logoUrls
        this.state = state
        if (artworkChanged) {
            artworkJob?.cancel()
            artwork = null
        }
        val session = session ?: createSession().also { session = it }
        val metadataChanged = previous == null || artworkChanged || previous.channelName != state.channelName ||
            previous.programme != state.programme || previous.progress?.durationMs != state.progress?.durationMs
        if (metadataChanged) updateMetadata(session, state)
        updatePlaybackState(session, state)
        if (!session.isActive) session.isActive = true
        if (metadataChanged || previous?.playbackState != state.playbackState ||
            previous?.isFavorite != state.isFavorite || previous?.onLockScreen != state.onLockScreen
        ) {
            publishNotification(session, state)
        }
        if (artworkChanged && state.logoUrls.isNotEmpty()) {
            artworkJob = scope.launch {
                for (url in state.logoUrls) {
                    val request = channelLogoRequest(context, url).newBuilder()
                        .size(ARTWORK_SIZE)
                        .scale(Scale.FIT)
                        .build()
                    val result = context.imageLoader.execute(request)
                    ensureActive()
                    if (result is SuccessResult) {
                        artwork = squareArtwork(result.drawable.toBitmap())
                        this@SystemMediaControls.state?.let {
                            updateMetadata(session, it)
                            publishNotification(session, it)
                        }
                        break
                    }
                }
            }
        }
    }

    private fun squareArtwork(logo: Bitmap): Bitmap {
        // System media cards often center-crop artwork into a rounded square.
        val scale = ARTWORK_SIZE * 0.84f / maxOf(logo.width, logo.height)
        val width = logo.width * scale
        val height = logo.height * scale
        val left = (ARTWORK_SIZE - width) / 2
        val top = (ARTWORK_SIZE - height) / 2
        return Bitmap.createBitmap(ARTWORK_SIZE, ARTWORK_SIZE, Bitmap.Config.ARGB_8888).also {
            Canvas(it).drawBitmap(logo, null, RectF(left, top, left + width, top + height), Paint(Paint.FILTER_BITMAP_FLAG))
        }
    }

    private fun updateMetadata(session: MediaSession, state: State) {
        val subtitle = programmeSubtitle(state.programme)
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, state.channelName)
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, state.channelName)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, subtitle)
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, subtitle)
                .apply {
                    state.progress?.let { putLong(MediaMetadata.METADATA_KEY_DURATION, it.durationMs) }
                    artwork?.let {
                        putBitmap(MediaMetadata.METADATA_KEY_ART, it)
                        putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it)
                    }
                }
                .build(),
        )
    }

    private fun updatePlaybackState(session: MediaSession, state: State) {
        val leading = state.leadingAction
        val stop = MediaPlaybackAction.STOP
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_STOP,
                )
                // HyperOS places the first custom action on the left and the second on the right.
                .addCustomAction(leading.broadcastAction, leading.label, leading.iconId)
                .addCustomAction(stop.broadcastAction, stop.label, stop.iconId)
                .setState(
                    state.playbackState,
                    state.progress?.elapsedMs ?: PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                    if (state.playbackState == PlaybackState.STATE_PLAYING) 1f else 0f,
                )
                .build(),
        )
    }

    private fun programmeSubtitle(programme: EpgProgramme?): String? = programme?.let {
        val format = SimpleDateFormat("HH:mm", Locale.getDefault())
        "${format.format(Date(it.startAt))} ~ ${format.format(Date(it.endAt))}  ${it.title}"
    }

    // Notifications attached to a media session are exempt from POST_NOTIFICATIONS.
    @SuppressLint("MissingPermission")
    private fun publishNotification(session: MediaSession, state: State) {
        val playRequested = state.playbackState != PlaybackState.STATE_PAUSED
        val action = MediaPlaybackAction.forPlayback(playRequested)
        val notification = notificationBuilder()
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(state.channelName)
            .setContentText(programmeSubtitle(state.programme))
            .setLargeIcon(artwork)
            .setContentIntent(openPlayer)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(1, 2, 3),
            )
            .apply {
                val actions = listOf(
                    state.leadingAction,
                    MediaPlaybackAction.PREVIOUS_CHANNEL,
                    action,
                    MediaPlaybackAction.NEXT_CHANNEL,
                    MediaPlaybackAction.STOP,
                )
                for (control in actions) {
                    val intent = if (control == MediaPlaybackAction.OPEN_PLAYER) openPlayer else control.pendingIntent(context)
                    addAction(Notification.Action.Builder(
                        control.icon(context), control.label, intent,
                    ).build())
                }
            }
            .build()
        if (supportsLockScreenPlayback && (playRequested || state.onLockScreen)) {
            if (!serviceBound) {
                serviceBound = context.bindService(
                    Intent(context, PlaybackForegroundService::class.java),
                    playbackConnection,
                    Context.BIND_AUTO_CREATE,
                )
            }
            playbackService?.show(NOTIFICATION_ID, notification, state.onLockScreen && playRequested)
        } else {
            disconnectPlaybackService()
        }
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun disconnectPlaybackService() {
        if (!serviceBound) return
        playbackService?.leaveForeground()
        playbackService = null
        serviceBound = false
        context.unbindService(playbackConnection)
    }

    fun clear() {
        artworkJob?.cancel()
        artworkJob = null
        artwork = null
        state = null
        disconnectPlaybackService()
        val session = session ?: return
        this.session = null
        notificationManager.cancel(NOTIFICATION_ID)
        session.release()
    }

    @Suppress("DEPRECATION") // Android 6 and 7 need the media button and transport flags.
    private fun createSession(): MediaSession {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "播放控制", NotificationManager.IMPORTANCE_LOW),
            )
        }
        return MediaSession(context, "ChannelPlayback").apply {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
            }
            setSessionActivity(openPlayer)
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = onAction(MediaPlaybackAction.PLAY)
                override fun onPause() = onAction(MediaPlaybackAction.PAUSE)
                override fun onSkipToPrevious() = onAction(MediaPlaybackAction.PREVIOUS_CHANNEL)
                override fun onSkipToNext() = onAction(MediaPlaybackAction.NEXT_CHANNEL)
                override fun onStop() = onAction(MediaPlaybackAction.STOP)

                override fun onCustomAction(action: String, extras: Bundle?) {
                    val leading = state?.leadingAction
                    when {
                        action == MediaPlaybackAction.STOP.broadcastAction -> onStop()
                        leading != null && action == leading.broadcastAction -> onAction(leading)
                    }
                }
            })
        }
    }

    fun openPlayer() = context.startActivity(openPlayerIntent)

    @Suppress("DEPRECATION") // Notification channels were added in Android 8.
    private fun notificationBuilder(): Notification.Builder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(context, CHANNEL_ID)
        else Notification.Builder(context).setPriority(Notification.PRIORITY_LOW)

    private companion object {
        const val CHANNEL_ID = "playback"
        const val NOTIFICATION_ID = 10482
        const val ARTWORK_SIZE = 256
    }
}
