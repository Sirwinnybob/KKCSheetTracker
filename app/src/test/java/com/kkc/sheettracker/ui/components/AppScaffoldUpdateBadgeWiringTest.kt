package com.kkc.sheettracker.ui.components

import com.kkc.sheettracker.testutil.SourceFiles
import org.junit.Assert.assertTrue
import org.junit.Test

class AppScaffoldUpdateBadgeWiringTest {

    @Test
    fun settingsIconRendersUpdateDot() {
        val source = SourceFiles.mainSource("com/kkc/sheettracker/ui/components/AppScaffold.kt").readText()

        assertTrue("AppBottomNavBar must accept hasPendingUpdates", source.contains("hasPendingUpdates: Boolean = false"))
        assertTrue("MorphingNavIconRow must branch on NavDestination.SETTINGS with an empty dot badge", source.contains("dest == NavDestination.SETTINGS && hasPendingUpdates"))
    }
}
