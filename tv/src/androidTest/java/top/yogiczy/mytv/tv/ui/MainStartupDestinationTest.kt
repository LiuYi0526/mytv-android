package top.yogiczy.mytv.tv.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import top.yogiczy.mytv.tv.ui.screen.Screens
import top.yogiczy.mytv.tv.ui.screen.main.initialMainDestination

class MainStartupDestinationTest {
    @Test fun configuredHomeDoesNotWaitForChannelPreparation() {
        assertEquals(Screens.Dashboard(), initialMainDestination(true, true, Screens.Dashboard()))
    }

    @Test fun agreementStillTakesPriorityOverSavedStartupPage() {
        for (startup in listOf(Screens.Dashboard(), Screens.Live())) {
            assertEquals(Screens.Agreement(), initialMainDestination(false, true, startup))
        }
    }

    @Test fun firstSetupAndDirectPlaybackStillWaitForChannelLoading() {
        assertEquals(Screens.Loading(), initialMainDestination(true, false, Screens.Dashboard()))
        assertEquals(Screens.Loading(), initialMainDestination(true, true, Screens.Live()))
    }

    @Test fun cloudRestoreResolvesTheStartupPageBeforeNavigation() {
        assertEquals(Screens.Loading(), initialMainDestination(true, true, Screens.Dashboard(), true))
        assertEquals(Screens.Agreement(), initialMainDestination(false, true, Screens.Dashboard(), true))
    }
}
