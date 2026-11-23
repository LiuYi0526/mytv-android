package top.yogiczy.mytv.tv

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.drawable.Icon
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.doOnPreDraw
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import top.yogiczy.mytv.tv.ui.App
import top.yogiczy.mytv.tv.ui.screen.timingclose.LocalTimingCloseViewModel
import top.yogiczy.mytv.tv.ui.screen.timingclose.TimingCloseViewModel
import top.yogiczy.mytv.tv.ui.screensold.videoplayer.VideoPlayerState
import top.yogiczy.mytv.tv.ui.theme.MyTvTheme
import top.yogiczy.mytv.tv.ui.utils.Configs
import top.yogiczy.mytv.tv.utlis.HttpServer
import kotlin.system.exitProcess

internal val LocalPictureInPictureHost = staticCompositionLocalOf<MainActivity?> { null }

internal class PictureInPicturePlayback(
    val player: VideoPlayerState,
    val isCurrentDestination: () -> Boolean,
)

private const val ACTION_PIP_PLAY = "top.yogiczy.mytv.tv.pip.PLAY"
private const val ACTION_PIP_PAUSE = "top.yogiczy.mytv.tv.pip.PAUSE"

class MainActivity : ComponentActivity() {
    private val timingCloseViewModel: TimingCloseViewModel by viewModels()
    internal var pictureInPicturePlayback by mutableStateOf<PictureInPicturePlayback?>(null)
    internal var playbackInPictureInPicture by mutableStateOf(false)
        private set

    private val pictureInPictureReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !isInPictureInPictureMode) return
            val playback = pictureInPicturePlayback ?: return
            if (!playback.isCurrentDestination()) return

            val player = playback.player
            when (intent.action) {
                ACTION_PIP_PLAY -> if (!player.playRequested) player.play()
                ACTION_PIP_PAUSE -> if (player.playRequested) player.pause()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.registerReceiver(
                this,
                pictureInPictureReceiver,
                IntentFilter().apply {
                    addAction(ACTION_PIP_PLAY)
                    addAction(ACTION_PIP_PAUSE)
                },
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            lifecycleScope.launch {
                snapshotFlow {
                    if (playbackInPictureInPicture) pictureInPicturePlayback?.player?.playRequested else null
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

    private fun closeApp() {
        finish()
        exitProcess(0)
    }

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) unregisterReceiver(pictureInPictureReceiver)
        HttpServer.stopService(applicationContext)
        super.onDestroy()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()

        val playback = pictureInPicturePlayback ?: return
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
        val action = if (playRequested) ACTION_PIP_PAUSE else ACTION_PIP_PLAY
        val icon = if (playRequested) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        val label = if (playRequested) "暂停" else "播放"
        val intent = PendingIntent.getBroadcast(
            this,
            0,
            Intent(action).setPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return PictureInPictureParams.Builder()
            .setAspectRatio(Rational(16, 9))
            .setActions(listOf(RemoteAction(Icon.createWithResource("android", icon), label, label, intent)))
            .build()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        playbackInPictureInPicture = isInPictureInPictureMode
    }
}
