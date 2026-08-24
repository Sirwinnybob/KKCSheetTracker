package com.kkc.sheettracker.navigation

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyStandardsTransitionWiringTest {
    @Test
    fun standardsRoutesDoNotAnimateThroughTheSharedHazeSource() {
        val source = navGraphSource()
        val legacyStart = source.indexOf("private fun LegacySingleStackNavigation(")
        val legacyEnd = source.indexOf("private fun HoursTabHost(")
        assertTrue("LegacySingleStackNavigation not found", legacyStart >= 0)
        assertTrue("HoursTabHost boundary not found", legacyEnd > legacyStart)
        val legacy = source.substring(legacyStart, legacyEnd)

        listOf("standards", "standards/molding", "standards/safety").forEach { route ->
            val routeStart = Regex("composable\\(\\s*\"${Regex.escape(route)}\"")
                .find(legacy)
                ?.range
                ?.first
                ?: -1
            assertTrue("$route route not found", routeStart >= 0)
            val routeBlock = legacy.substring(routeStart, minOf(routeStart + 500, legacy.length))
            assertTrue("$route must suppress enter animation", routeBlock.contains("enterTransition = { EnterTransition.None }"))
            assertTrue("$route must suppress exit animation", routeBlock.contains("exitTransition = { ExitTransition.None }"))
            assertTrue("$route must suppress pop-enter animation", routeBlock.contains("popEnterTransition = { EnterTransition.None }"))
            assertTrue("$route must suppress pop-exit animation", routeBlock.contains("popExitTransition = { ExitTransition.None }"))
        }
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
