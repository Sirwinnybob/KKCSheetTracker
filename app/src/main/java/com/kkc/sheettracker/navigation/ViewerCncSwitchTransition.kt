package com.kkc.sheettracker.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.navigation.NavBackStackEntry

internal const val CNC_SWITCH_SLIDE_MS = 300

private fun AnimatedContentTransitionScope<NavBackStackEntry>.cncSwitchDirection(): Int? =
    cncSwitchSlideDirection(
        fromRoute = initialState.destination.route,
        toRoute = targetState.destination.route,
        toMachineId = targetState.arguments?.getString("machine")
    )

/**
 * Switch-CNC jump between two sheet viewers: the incoming machine's sheet slides in from its side
 * while the outgoing one slides out the other way. The viewer's top bar is pinned as a shared
 * element, so only the content moves. Null (NavHost default) for every other route change.
 */
internal val viewerCncSwitchEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition? = {
    cncSwitchDirection()?.let { direction ->
        slideInHorizontally(tween(CNC_SWITCH_SLIDE_MS, easing = FastOutSlowInEasing)) { width -> width * direction }
    }
}

internal val viewerCncSwitchExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition? = {
    cncSwitchDirection()?.let { direction ->
        slideOutHorizontally(tween(CNC_SWITCH_SLIDE_MS, easing = FastOutSlowInEasing)) { width -> -width * direction }
    }
}
