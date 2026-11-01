package top.yogiczy.mytv.tv.ui.screensold.classicchannel.components

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.dp
import kotlin.math.absoluteValue

/** YYKM maps a right swipe to left navigation; leave vertical scrolling to the list. */
internal fun Modifier.classicPanelSwipes(
    onSwipeRight: () -> Unit = {},
    onSwipeLeft: () -> Unit = {},
): Modifier = composed {
    val currentRight by rememberUpdatedState(onSwipeRight)
    val currentLeft by rememberUpdatedState(onSwipeLeft)
    pointerInput(Unit) {
        val tracker = VelocityTracker()
        var distance = 0f
        detectHorizontalDragGestures(
            onDragStart = {
                distance = 0f
                tracker.resetTracking()
            },
            onDragCancel = {
                distance = 0f
                tracker.resetTracking()
            },
            onDragEnd = {
                // Same 10dp / 100dp per second thresholds as YYKM 3.8.4.
                val velocity = tracker.calculateVelocity().x
                if (distance.absoluteValue > 10.dp.toPx()) {
                    if (distance > 0 && velocity > 100.dp.toPx()) currentRight()
                    else if (distance < 0 && velocity < -100.dp.toPx()) currentLeft()
                }
            },
        ) { change, amount ->
            distance += amount
            tracker.addPosition(change.uptimeMillis, change.position)
        }
    }
}
