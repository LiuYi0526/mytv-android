package top.yogiczy.mytv.tv

import android.app.PictureInPictureParams
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.doOnPreDraw
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import top.yogiczy.mytv.tv.ui.App
import top.yogiczy.mytv.tv.ui.screen.timingclose.LocalTimingCloseViewModel
import top.yogiczy.mytv.tv.ui.screen.timingclose.TimingCloseViewModel
import top.yogiczy.mytv.tv.ui.theme.MyTvTheme
import top.yogiczy.mytv.tv.ui.utils.Configs
import top.yogiczy.mytv.tv.utlis.HttpServer
import kotlin.system.exitProcess

internal val LocalPictureInPictureHost = staticCompositionLocalOf<MainActivity?> { null }

class MainActivity : ComponentActivity() {
    private val timingCloseViewModel: TimingCloseViewModel by viewModels()
    internal var canEnterPlaybackPictureInPicture: (() -> Boolean)? = null
    internal var playbackInPictureInPicture by mutableStateOf(false)
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

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
        HttpServer.stopService(applicationContext)
        super.onDestroy()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            Configs.appPipEnable &&
            canEnterPlaybackPictureInPicture?.invoke() == true &&
            !isInPictureInPictureMode &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
        ) {
            val aspectRatio = Rational(16, 9)
            val params = PictureInPictureParams.Builder().setAspectRatio(aspectRatio).build()
            enterPictureInPictureMode(params)
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        playbackInPictureInPicture = isInPictureInPictureMode
    }
}
