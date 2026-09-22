package com.kkc.sheettracker.navigation

import com.kkc.sheettracker.testutil.SourceFiles
import org.junit.Assert.assertEquals
import org.junit.Test

class PendingUpdatesSettingsWiringTest {

    @Test
    fun pendingUpdateParamsReachBothSettingsScreenCalls() {
        val source = SourceFiles.mainSource("com/kkc/sheettracker/navigation/NavGraph.kt").readText()

        val settingsScreenCallSites = Regex("SettingsScreen\\(").findAll(source).count()
        assertEquals("Expected exactly two SettingsScreen call sites", 2, settingsScreenCallSites)

        val onInstallAllForwards = Regex("onInstallAll = onInstallAll").findAll(source).count()
        // Chain A has 3 hops: AppNavigation -> MultiBackStackNavigation -> SettingsTabHost -> SettingsScreen.
        // Chain B has 2 hops: AppNavigation -> LegacySingleStackNavigation -> SettingsScreen.
        // Total forwarding call sites across both chains: 3 + 2 = 5.
        assertEquals("onInstallAll must be forwarded at every hop in both chains", 5, onInstallAllForwards)

        val pendingSelfUpdateDeclarations = Regex("pendingSelfUpdate: File\\? = null").findAll(source).count()
        // AppNavigation, MultiBackStackNavigation, SettingsTabHost, LegacySingleStackNavigation
        assertEquals("pendingSelfUpdate must be declared on all four functions in both chains", 4, pendingSelfUpdateDeclarations)
    }
}
