package com.kkc.sheettracker.navigation

import com.kkc.sheettracker.testutil.SourceFiles
import org.junit.Assert.assertEquals
import org.junit.Test

class UpdateBadgeWiringTest {

    @Test
    fun hasPendingUpdatesReachesBothAppBottomNavBarCalls() {
        val source = SourceFiles.mainSource("com/kkc/sheettracker/navigation/NavGraph.kt").readText()

        val paramDeclarations = Regex("hasPendingUpdates: Boolean = false").findAll(source).count()
        // AppNavigation, MultiBackStackNavigation, LegacySingleStackNavigation
        assertEquals("hasPendingUpdates must be declared on all three navigation functions", 3, paramDeclarations)

        val hasPendingUpdatesForwards = Regex("hasPendingUpdates = hasPendingUpdates").findAll(source).count()
        // AppNavigation forwards it to both MultiBackStackNavigation and LegacySingleStackNavigation (2),
        // and each of those forwards it again into its own AppBottomNavBar call (2 more).
        assertEquals("hasPendingUpdates must be forwarded at every hop in both chains", 4, hasPendingUpdatesForwards)
    }
}
