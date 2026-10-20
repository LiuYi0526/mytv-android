package top.yogiczy.mytv.tv.ui.screen.timingclose

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import top.yogiczy.mytv.core.data.entities.timing.TimingCloseTimer

class TimingCloseViewModel : ViewModel() {
    private val timer = TimingCloseTimer(viewModelScope, SystemClock::elapsedRealtime)
    val status = timer.status
    var showPicker by mutableStateOf(false)

    fun selectMinutes(minutes: Int) {
        timer.setMinutes(minutes)
        showPicker = false
    }
}

val LocalTimingCloseViewModel = staticCompositionLocalOf { TimingCloseViewModel() }
