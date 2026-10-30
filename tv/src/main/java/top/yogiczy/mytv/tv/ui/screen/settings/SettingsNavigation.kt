package top.yogiczy.mytv.tv.ui.screen.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController

@Composable
internal fun SettingsNavigation(content: @Composable (NavHostController) -> Unit) {
    val uiContext = LocalContext.current
    // Only the outer controller owns the Activity's launch Intent. Using the
    // application context prevents this nested controller from replaying it.
    CompositionLocalProvider(LocalContext provides uiContext.applicationContext) {
        val controller = rememberNavController()
        // Editors, permissions and dialogs still need the original UI context.
        CompositionLocalProvider(LocalContext provides uiContext) {
            content(controller)
        }
    }
}

internal fun NavHostController.popSettingsOrExit(onExit: () -> Unit) {
    // navigateUp() can mutate the Activity's deep-link arguments or relaunch it.
    // Do not pop the last entry: the exit prompt must leave this page intact.
    if (previousBackStackEntry == null) onExit() else popBackStack()
}
