package top.yogiczy.mytv.tv

import android.annotation.SuppressLint
import android.app.Notification
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.ServiceCompat

/** Keeps channel playback running while the app is listening in the background or locked. */
internal class PlaybackForegroundService : Service() {
    inner class LocalBinder : Binder() {
        val service get() = this@PlaybackForegroundService
    }

    private val wakeLock by lazy {
        getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:ChannelPlayback")
    }
    private var foreground = false

    override fun onBind(intent: Intent) = LocalBinder()

    @SuppressLint("WakelockTimeout") // Released when background playback pauses, returns to the app or stops.
    fun show(notificationId: Int, notification: Notification, keepCpuAwake: Boolean) {
        if (!foreground) {
            try {
                ServiceCompat.startForeground(
                    this, notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
                )
                foreground = true
            } catch (error: IllegalStateException) {
                Log.w("ChannelPlayback", "Unable to start foreground playback", error)
                return
            } catch (error: SecurityException) {
                Log.w("ChannelPlayback", "Foreground playback is unavailable", error)
                return
            }
        }
        if (keepCpuAwake && !wakeLock.isHeld) wakeLock.acquire()
        if (!keepCpuAwake && wakeLock.isHeld) wakeLock.release()
    }

    fun leaveForeground() {
        if (wakeLock.isHeld) wakeLock.release()
        if (foreground) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
            foreground = false
        }
    }

    override fun onDestroy() {
        leaveForeground()
        super.onDestroy()
    }
}
