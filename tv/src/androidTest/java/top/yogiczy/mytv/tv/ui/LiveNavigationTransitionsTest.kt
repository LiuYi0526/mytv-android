package top.yogiczy.mytv.tv.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import top.yogiczy.mytv.tv.ui.screen.Screens
import top.yogiczy.mytv.tv.ui.screen.main.livePopEnterTransition
import top.yogiczy.mytv.tv.ui.screen.main.livePopExitTransition

class LiveNavigationTransitionsTest {
    @get:Rule val compose = createComposeRule()

    private lateinit var navigation: NavHostController
    private var disposed = false

    private fun setContent(origin: String, destination: String) {
        compose.setContent {
            navigation = rememberNavController()
            NavHost(
                navController = navigation,
                startDestination = origin,
                popEnterTransition = { livePopEnterTransition() },
                popExitTransition = { livePopExitTransition() },
            ) {
                composable(origin) { Box(Modifier.fillMaxSize().testTag("origin")) }
                composable(destination) {
                    DisposableEffect(Unit) { onDispose { disposed = true } }
                    Box(Modifier.fillMaxSize().testTag("destination"))
                }
            }
        }
        compose.runOnIdle { navigation.navigate(destination) }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { assertTrue(navigation.popBackStack()) }
        compose.mainClock.advanceTimeBy(48)
        compose.waitForIdle()
    }

    @Test fun liveReturnToDashboardFinishesAfterBriefFade() {
        assertBriefLiveReturn(Screens.Dashboard())
    }

    @Test fun liveReturnToChannelsFinishesAfterBriefFade() {
        assertBriefLiveReturn(Screens.Channels())
    }

    private fun assertBriefLiveReturn(origin: String) {
        setContent(origin, Screens.Live())
        compose.runOnIdle { assertFalse("Return should still be animating", disposed) }
        compose.mainClock.advanceTimeBy(200)
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue("Live outlived the brief return animation", disposed)
            assertEquals(origin, navigation.currentBackStackEntry?.destination?.route)
            assertEquals(Lifecycle.State.RESUMED, navigation.currentBackStackEntry?.lifecycle?.currentState)
        }
        compose.onNodeWithTag("origin").assertIsDisplayed()
    }

    @Test fun ordinaryPageReturnKeepsItsTransition() {
        setContent(Screens.Dashboard(), Screens.Settings())
        compose.runOnIdle { assertFalse(disposed) }
        compose.mainClock.advanceTimeBy(800)
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(disposed) }
    }
}
