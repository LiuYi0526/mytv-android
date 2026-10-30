package top.yogiczy.mytv.tv.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import top.yogiczy.mytv.tv.ui.screen.settings.SettingsNavigation
import top.yogiczy.mytv.tv.ui.screen.settings.popSettingsOrExit

class SettingsNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var navigation: NavHostController

    private fun showSettings() {
        compose.setContent {
            SettingsNavigation { controller ->
                navigation = controller
                assertSame(compose.activity, LocalContext.current)
                NavHost(controller, startDestination = "root") {
                    composable("root") { Box {} }
                    composable("child") { Box {} }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test fun ignoresOuterIntentWithEmptyDeepLinkArguments() {
        val original = compose.activity.intent
        // Navigation 2.8.5 indexes deepLinkArgs[0] for this Intent. A nested
        // settings graph must not consume it, even when the Activity has it.
        compose.activity.intent = Intent(original)
            .putExtra("android-support-nav:controller:deepLinkIds", intArrayOf(0))
            .putParcelableArrayListExtra("android-support-nav:controller:deepLinkArgs", arrayListOf<Bundle>())
        try {
            showSettings()
            compose.runOnIdle {
                assertEquals("root", navigation.currentDestination?.route)
                assertEquals(0, compose.activity.intent.getParcelableArrayListExtra<Bundle>(
                    "android-support-nav:controller:deepLinkArgs")?.size)
            }
        } finally {
            compose.activity.intent = original
        }
    }

    @Test fun rootBackKeepsPageForExitConfirmation() {
        showSettings()
        compose.runOnIdle {
            val entry = navigation.currentBackStackEntry
            var exits = 0
            repeat(2) { navigation.popSettingsOrExit { exits++ } }
            assertEquals(2, exits)
            assertSame(entry, navigation.currentBackStackEntry)
        }
    }

    @Test fun childBackReturnsToRootWithoutExiting() {
        showSettings()
        compose.runOnIdle { navigation.navigate("child") }
        compose.waitForIdle()
        compose.runOnIdle {
            var exits = 0
            navigation.popSettingsOrExit { exits++ }
            assertEquals(0, exits)
            assertEquals("root", navigation.currentDestination?.route)
        }
    }
}
