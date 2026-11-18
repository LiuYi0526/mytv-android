package top.yogiczy.mytv.tv.ui.screensold.quickop

import androidx.compose.foundation.lazy.LazyListState

/** One toolbar child at a time. Direct shortcuts never inherit a previous return destination. */
internal class QuickOpNavigationState {
    // Outlive the toolbar composition while a child menu is open.
    var listState: LazyListState = LazyListState()
        private set
    var focusKey: String = "epg"
        private set
    var pendingChild: String? = null
        private set

    fun openToolbar() {
        pendingChild = null
        focusKey = "epg"
        listState = LazyListState()
    }

    fun openChild(key: String) {
        focusKey = key
        pendingChild = key
    }

    fun returnFromChild(key: String): Boolean {
        if (pendingChild != key) return false
        pendingChild = null
        return true
    }

    fun leaveToolbar() { pendingChild = null }
}
