package top.yogiczy.mytv.tv

import android.app.PictureInPictureParams
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import top.yogiczy.mytv.tv.ui.App
import top.yogiczy.mytv.tv.ui.screen.timingclose.LocalTimingCloseViewModel
import top.yogiczy.mytv.tv.ui.screen.timingclose.TimingCloseViewModel
import top.yogiczy.mytv.tv.ui.theme.MyTvTheme
import top.yogiczy.mytv.tv.ui.utils.Configs
import top.yogiczy.mytv.tv.utlis.HttpServer
import kotlin.system.exitProcess

class MainActivity : ComponentActivity() {
    private val timingCloseViewModel: TimingCloseViewModel by viewModels()

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

            CompositionLocalProvider(LocalTimingCloseViewModel provides timingCloseViewModel) {
                MyTvTheme {
                    App(onBackPressed = ::closeApp)
                }
            }
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && Configs.appPipEnable) {
            val aspectRatio = Rational(16, 9)
            val params = PictureInPictureParams.Builder().setAspectRatio(aspectRatio).build()
            enterPictureInPictureMode(params)
        }
    }
}
