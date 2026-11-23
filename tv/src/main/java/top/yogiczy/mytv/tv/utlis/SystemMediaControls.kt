package top.yogiczy.mytv.tv.utlis

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import androidx.core.graphics.drawable.toBitmap
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
import top.yogiczy.mytv.tv.ui.screen.channels.components.channelLogoRequest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal enum class MediaPlaybackAction(val label: String, private val iconId: Int) {
    PLAY("播放", android.R.drawable.ic_media_play),
    PAUSE("暂停", android.R.drawable.ic_media_pause);

    val broadcastAction = "top.yogiczy.mytv.tv.media.$name"
    val icon: Icon get() = Icon.createWithResource("android", iconId)

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

/** Publishes controls for the visible channel player, including while it is in picture-in-picture. */
internal class SystemMediaControls(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onAction: (MediaPlaybackAction) -> Unit,
) {
    data class State(
        val channelName: String,
        val programme: EpgProgramme?,
        val logoUrls: List<String>,
        val playbackState: Int,
        val progress: PlaybackControllerTimeline.Progress?,
    )

    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val openPlayer = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE,
    )
    private var session: MediaSession? = null
    private var state: State? = null
    private var artwork: Bitmap? = null
    private var artworkJob: Job? = null

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
        if (metadataChanged || previous?.playbackState != state.playbackState) publishNotification(session, state)
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
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE)
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
        val action = MediaPlaybackAction.forPlayback(state.playbackState != PlaybackState.STATE_PAUSED)
        notificationManager.notify(
            NOTIFICATION_ID,
            notificationBuilder()
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(state.channelName)
                .setContentText(programmeSubtitle(state.programme))
                .setLargeIcon(artwork)
                .setContentIntent(openPlayer)
                .setCategory(Notification.CATEGORY_TRANSPORT)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0))
                .addAction(Notification.Action.Builder(action.icon, action.label, action.pendingIntent(context)).build())
                .build(),
        )
    }

    fun clear() {
        artworkJob?.cancel()
        artworkJob = null
        artwork = null
        state = null
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
            })
        }
    }

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
