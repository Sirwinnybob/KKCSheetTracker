package com.kkc.sheettracker.navigation

import com.kkc.sheettracker.testutil.SourceFiles
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * hasPendingUpdates does NOT reach the bottom nav bar: NavDestination.SETTINGS is deliberately
 * filtered out of `visibleDestinations` in both navigation stacks (Settings is reached via the
 * top bar's gear icon, LocalOnOpenSettings/LocalHasPendingUpdates — see HeaderGradient.kt). An
 * earlier version of this wiring threaded hasPendingUpdates into AppBottomNavBar instead, which
 * was unreachable dead code caught by adversarial review. This test guards against that
 * regression and checks the actual live path: CompositionLocalProvider.
 */
class UpdateBadgeWiringTest {

    @Test
    fun hasPendingUpdatesReachesTheHeaderCompositionLocalAndNotTheDeadBottomNavPath() {
        val source = SourceFiles.mainSource("com/kkc/sheettracker/navigation/NavGraph.kt").readText()

        val paramDeclarations = Regex("hasPendingUpdates: Boolean = false").findAll(source).count()
        // AppNavigation, MultiBackStackNavigation, LegacySingleStackNavigation
        assertEquals("hasPendingUpdates must be declared on all three navigation functions", 3, paramDeclarations)

        val appNavigationForwards = Regex("hasPendingUpdates = hasPendingUpdates").findAll(source).count()
        // AppNavigation forwards it to both MultiBackStackNavigation and LegacySingleStackNavigation.
        assertEquals("hasPendingUpdates must be forwarded from AppNavigation to both nav stacks", 2, appNavigationForwards)

        val compositionLocalProvides = Regex("LocalHasPendingUpdates provides hasPendingUpdates").findAll(source).count()
        // Each nav stack provides it once, alongside LocalOnOpenSettings, for KKCTopAppBar to read.
        // If hasPendingUpdates were (re-)threaded into the dead AppBottomNavBar path instead,
        // appNavigationForwards above would be 4 instead of 2, failing that assertion first.
        assertEquals("LocalHasPendingUpdates must be provided in both nav stacks", 2, compositionLocalProvides)
    }
}
