package com.kkc.sheettracker.navigation

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class FlexibleModeWiringTest {
    @Test
    fun flexibleModeEnabledReachesEveryFunctionWorkModeReaches() {
        val source = navGraphSource()

        val functionsRequiringParam = listOf(
            "fun AppNavigation(",
            "private fun MultiBackStackNavigation(",
            "private fun LegacySingleStackNavigation(",
            "private fun DashboardTabHost(",
            "private fun JobsTabHost(",
            "private fun SettingsTabHost("
        )
        functionsRequiringParam.forEach { signature ->
            val start = source.indexOf(signature)
            assertTrue("$signature not found", start >= 0)
            val paramBlockEnd = source.indexOf(") {", start).let { if (it < 0) source.indexOf("){", start) else it }
            assertTrue("closing paren for $signature not found", paramBlockEnd > start)
            val paramBlock = source.substring(start, paramBlockEnd)
            assertTrue(
                "$signature must declare flexibleModeEnabled: Boolean",
                paramBlock.contains("flexibleModeEnabled: Boolean")
            )
        }
    }

    @Test
    fun settingsScreenCallSitesForwardFlexibleModeEnabled() {
        val source = navGraphSource()
        val settingsScreenCallCount = Regex("SettingsScreen\\(").findAll(source).count()
        val forwardedCount = Regex("flexibleModeEnabled = flexibleModeEnabled").findAll(source).count()
        assertTrue(
            "expected at least as many flexibleModeEnabled forwards ($forwardedCount) as SettingsScreen( calls ($settingsScreenCallCount) plus the AppNavigation/MultiBackStack/Legacy chain",
            forwardedCount >= settingsScreenCallCount
        )
    }

    private fun navGraphSource(): String {
        var dir = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(6) {
            val candidate = File(dir, "app/src/main/java/com/kkc/sheettracker/navigation/NavGraph.kt")
            if (candidate.exists()) return candidate.readText()
            val direct = File(dir, "src/main/java/com/kkc/sheettracker/navigation/NavGraph.kt")
            if (direct.exists()) return direct.readText()
            dir = dir.parentFile ?: return@repeat
        }
        error("Unable to locate NavGraph.kt from ${System.getProperty("user.dir")}")
    }
}
