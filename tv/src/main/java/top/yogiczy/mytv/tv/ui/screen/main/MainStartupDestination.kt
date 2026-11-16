package top.yogiczy.mytv.tv.ui.screen.main

import top.yogiczy.mytv.tv.ui.screen.Screens

internal fun initialMainDestination(
    agreementAgreed: Boolean,
    hasSources: Boolean,
    startupScreen: String,
    cloudSyncAutoPull: Boolean = false,
): String = when {
    !agreementAgreed -> Screens.Agreement()
    // Restored settings may change both the subscription and the requested startup page.
    cloudSyncAutoPull -> Screens.Loading()
    hasSources && startupScreen == Screens.Dashboard() -> Screens.Dashboard()
    else -> Screens.Loading()
}
