package top.yogiczy.mytv.tv

import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.doOnPreDraw
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yogiczy.mytv.core.data.entities.epg.EpgProgramme
import top.yogiczy.mytv.core.data.media.PlaybackControllerTimeline
import top.yogiczy.mytv.tv.ui.App
import top.yogiczy.mytv.tv.ui.screen.timingclose.LocalTimingCloseViewModel
import top.yogiczy.mytv.tv.ui.screen.timingclose.TimingCloseViewModel
import top.yogiczy.mytv.tv.ui.screensold.videoplayer.VideoPlayerState
import top.yogiczy.mytv.tv.ui.theme.MyTvTheme
import top.yogiczy.mytv.tv.ui.utils.Configs
import top.yogiczy.mytv.tv.utlis.HttpServer
import top.yogiczy.mytv.tv.utlis.MediaPlaybackAction
import top.yogiczy.mytv.tv.utlis.SystemMediaControls
import kotlin.system.exitProcess

internal val LocalPictureInPictureHost = staticCompositionLocalOf<MainActivity?> { null }

internal class ChannelPlaybackControls(
    val player: VideoPlayerState,
    val isCurrentDestination: () -> Boolean,
    val channelName: () -> String,
    val programmeTimelineAt: (Long) -> Pair<EpgProgramme?, PlaybackControllerTimeline>,
    val logoUrls: () -> List<String>,
)

class MainActivity : ComponentActivity() {
    private val timingCloseViewModel: TimingCloseViewModel by viewModels()
    private val systemMediaControls by lazy(LazyThreadSafetyMode.NONE) {
        SystemMediaControls(this, lifecycleScope, ::handleMediaAction)
    }
    internal var channelPlaybackControls by mutableStateOf<ChannelPlaybackControls?>(null)
    internal var playbackInPictureInPicture by mutableStateOf(false)
        private set

    private val mediaActionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            MediaPlaybackAction.entries.firstOrNull { it.broadcastAction == intent.action }
                ?.let(::handleMediaAction)
        }
    }

    private fun handleMediaAction(action: MediaPlaybackAction) {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        val playback = channelPlaybackControls ?: return
        val player = playback.player
        if (!playback.isCurrentDestination() || (!player.hasControllableMedia && !playbackInPictureInPicture)) return
        when (action) {
            MediaPlaybackAction.PLAY -> if (!player.playRequested) player.play()
            MediaPlaybackAction.PAUSE -> if (player.playRequested) player.pause()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        ContextCompat.registerReceiver(
            this,
            mediaActionReceiver,
            IntentFilter().apply { MediaPlaybackAction.entries.forEach { addAction(it.broadcastAction) } },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        observeSystemMediaControls()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            lifecycleScope.launch {
                snapshotFlow {
                    if (playbackInPictureInPicture) channelPlaybackControls?.player?.playRequested else null
                }.collect { playRequested ->
                    if (playRequested != null && isInPictureInPictureMode) {
                        setPictureInPictureParams(pictureInPictureParams(playRequested))
                    }
                }
            }
        }

        lifecycleScope.launch {
            timingCloseViewModel.status.collect { status ->
                if (status.hasExpired) closeApp()
            }
        }

        setContent {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowCompat.getInsetsController(window, window.decorView).let { insetsController ->
                insetsController.hide(WindowInsetsCompat.Type.statusBars())
                insetsController.hide(WindowInsetsCompat.Type.navigationBars())
                insetsController.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }

            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

            CompositionLocalProvider(
                LocalTimingCloseViewModel provides timingCloseViewModel,
                LocalPictureInPictureHost provides this,
            ) {
                MyTvTheme {
                    App(onBackPressed = ::closeApp)
                }
            }
        }

        // Use the themed background only for startup. An opaque window clears areas that
        // playback does not cover, so a previous page cannot remain in the letterbox area.
        window.decorView.doOnPreDraw {
            window.setBackgroundDrawableResource(android.R.color.black)
        }

        HttpServer.startService(applicationContext)
    }

    private fun observeSystemMediaControls() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                var now by mutableLongStateOf(System.currentTimeMillis())
                val clock = launch {
                    while (true) {
                        // Guide-based playback still advances when there is no native position.
                        delay(1_000 - now % 1_000)
                        now = System.currentTimeMillis()
                    }
                }
                try {
                    snapshotFlow {
                        val playback = channelPlaybackControls ?: return@snapshotFlow null
                        val player = playback.player
                        if (!playback.isCurrentDestination() || !player.hasControllableMedia) {
                            return@snapshotFlow null
                        }
                        val state = when {
                            !player.playRequested -> PlaybackState.STATE_PAUSED
                            player.isPlaying -> PlaybackState.STATE_PLAYING
                            else -> PlaybackState.STATE_BUFFERING
                        }
                        val (programme, timeline) = playback.programmeTimelineAt(now)
                        val progress = timeline.progressAt(now)
                        SystemMediaControls.State(
                            channelName = playback.channelName(),
                            programme = programme,
                            logoUrls = playback.logoUrls(),
                            playbackState = state,
                            progress = progress?.copy(elapsedMs = progress.elapsedMs / 1_000 * 1_000),
                        )
                    }.collect { state ->
                        if (state == null) systemMediaControls.clear() else systemMediaControls.publish(state)
                    }
                } finally {
                    clock.cancel()
                    systemMediaControls.clear()
                }
            }
        }
    }

    private fun closeApp() {
        systemMediaControls.clear()
        finish()
        exitProcess(0)
    }

    override fun onDestroy() {
        unregisterReceiver(mediaActionReceiver)
        HttpServer.stopService(applicationContext)
        super.onDestroy()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()

        val playback = channelPlaybackControls ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            Configs.appPipEnable &&
            playback.isCurrentDestination() &&
            playback.player.canEnterPictureInPicture &&
            !isInPictureInPictureMode &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
        ) {
            enterPictureInPictureMode(pictureInPictureParams(playback.player.playRequested))
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun pictureInPictureParams(playRequested: Boolean): PictureInPictureParams {
        val action = MediaPlaybackAction.forPlayback(playRequested)
        return PictureInPictureParams.Builder()
            .setAspectRatio(Rational(16, 9))
            .setActions(listOf(RemoteAction(action.icon, action.label, action.label, action.pendingIntent(this))))
            .build()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        playbackInPictureInPicture = isInPictureInPictureMode
    }
}
