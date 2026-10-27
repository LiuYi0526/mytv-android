package top.yogiczy.mytv.tv.ui.screen.main

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.navigation.NavBackStackEntry
import top.yogiczy.mytv.tv.ui.screen.Screens

// The returning page gets a brief fade; the outgoing video surface is hidden
// when the Live destination starts exiting (see MainScreen).
internal fun AnimatedContentTransitionScope<NavBackStackEntry>.livePopEnterTransition(): EnterTransition =
    if (initialState.destination.route == Screens.Live()) fadeIn(tween(160))
    else fadeIn(tween(700)) // Preserve Navigation Compose's default for other routes.

internal fun AnimatedContentTransitionScope<NavBackStackEntry>.livePopExitTransition(): ExitTransition =
    if (initialState.destination.route == Screens.Live()) ExitTransition.None
    else fadeOut(tween(700))
