package com.kkc.sheettracker.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import com.kkc.sheettracker.logging.AppLog
import com.kkc.sheettracker.ui.components.NavDestination
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate

/** A navigation request that arrived from outside the app (Hours Tracker's mirrored navbar). */
sealed interface ExternalNavTarget {
    data class Destination(val destination: NavDestination) : ExternalNavTarget
    data object Calculator : ExternalNavTarget
}

/**
 * Process-wide, single-consumer holder for navbar taps made in Hours Tracker. MainActivity posts;
 * whichever nav host is composed takes it. A StateFlow (not a SharedFlow) so a request posted
 * during a cold start, before any nav host is composed, is still there when one is.
 */
object ExternalNavRequests {
    private val _pending = MutableStateFlow<ExternalNavTarget?>(null)
    val pending: StateFlow<ExternalNavTarget?> = _pending.asStateFlow()

    fun parse(route: String?): ExternalNavTarget? {
        if (route.isNullOrBlank()) return null
        if (route == KkcNavBarContract.DEST_CALCULATOR) return ExternalNavTarget.Calculator
        val destination = NavDestination.entries.firstOrNull { it.route == route } ?: return null
        // HOURS would relaunch Hours Tracker in a loop; it is never a valid return target.
        if (destination == NavDestination.HOURS) return null
        return ExternalNavTarget.Destination(destination)
    }

    fun postFromRoute(route: String?): Boolean {
        val target = parse(route) ?: return false
        _pending.value = target
        return true
    }

    fun take(): ExternalNavTarget? = _pending.getAndUpdate { null }
}

internal fun resolveExternalDestination(
    target: ExternalNavTarget.Destination,
    visible: List<NavDestination>
): NavDestination? = target.destination.takeIf { it in visible }

/**
 * Consumes [ExternalNavRequests] in a nav host. [navigate] must be the SAME function the host's
 * own AppBottomNavBar uses for onNavigate, so a tap in Hours Tracker behaves exactly like a tap
 * in KKC.
 */
@Composable
internal fun ExternalNavEffect(
    visibleDestinations: List<NavDestination>,
    navigate: (NavDestination) -> Unit,
    openCalculator: () -> Unit
) {
    val pending by ExternalNavRequests.pending.collectAsState()
    val latestVisible by rememberUpdatedState(visibleDestinations)
    val latestNavigate by rememberUpdatedState(navigate)
    val latestOpenCalculator by rememberUpdatedState(openCalculator)
    LaunchedEffect(pending) {
        if (pending == null) return@LaunchedEffect
        when (val target = ExternalNavRequests.take()) {
            is ExternalNavTarget.Destination -> {
                val destination = resolveExternalDestination(target, latestVisible)
                if (destination != null) {
                    AppLog.d("KKC_NAV", "external_nav target=${destination.route}")
                    latestNavigate(destination)
                } else {
                    AppLog.d("KKC_NAV", "external_nav_ignored reason=not_visible target=${target.destination.route}")
                }
            }
            ExternalNavTarget.Calculator -> {
                AppLog.d("KKC_NAV", "external_nav target=calculator")
                latestOpenCalculator()
            }
            null -> Unit
        }
    }
}
