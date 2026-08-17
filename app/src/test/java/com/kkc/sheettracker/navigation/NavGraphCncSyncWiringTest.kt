package com.kkc.sheettracker.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Regression guard for code-review finding #23: the CNC-completion -> hardwoods sync
 * listener must be registered once in the shared `AppNavigation` composable so both
 * `MultiBackStackNavigation` and `LegacySingleStackNavigation` inherit it.
 *
 * [CncToHardwoodsSyncTest] only proves the underlying `syncCncToHardwoods` function works;
 * it does not read `NavGraph.kt`, so it would not catch a regression that moves the listener
 * registration back into `MultiBackStackNavigation` only. This test inspects the actual
 * source structure of NavGraph.kt to close that gap.
 */
class NavGraphCncSyncWiringTest {

    @Test
    fun cncInvalidationDoesNotEmitASecondWatcherEpoch() {
        val source = navGraphSource().joinToString("\n")
        assertEquals(
            1,
            Regex("jobFolderNames\\.forEach \\{ scanCoordinator\\.unifiedEngine\\.invalidateJob\\(it\\) \\}")
                .findAll(source).count()
        )
        assertEquals(
            2,
            Regex("watcherRefreshSignal\\.value = System\\.currentTimeMillis\\(\\)")
                .findAll(source).count()
        )
    }

    @Test
    fun onSheetStatusChangedListenerIsRegisteredInSharedAppNavigationBeforeBothNavHosts() {
        val lines = navGraphSource()

        val appNavigationStart = lines.indexOfFirst { it.trim().startsWith("fun AppNavigation(") }
        val multiStackStart = lines.indexOfFirst { it.trim().startsWith("private fun MultiBackStackNavigation(") }
        val legacyStackStart = lines.indexOfFirst { it.trim().startsWith("private fun LegacySingleStackNavigation(") }
        assertTrue("AppNavigation function not found in NavGraph.kt", appNavigationStart >= 0)
        assertTrue("MultiBackStackNavigation function not found in NavGraph.kt", multiStackStart >= 0)
        assertTrue("LegacySingleStackNavigation function not found in NavGraph.kt", legacyStackStart >= 0)
        assertTrue(
            "MultiBackStackNavigation/LegacySingleStackNavigation must be declared after AppNavigation",
            multiStackStart > appNavigationStart && legacyStackStart > appNavigationStart
        )

        val listenerAssignmentIndex = lines.indexOfFirst {
            it.contains("progressStore.onSheetStatusChangedListener") && it.contains("=") && !it.contains("===")
        }
        assertTrue("progressStore.onSheetStatusChangedListener assignment not found", listenerAssignmentIndex >= 0)

        // The listener assignment must live inside AppNavigation's own body (before either nav
        // host function is declared), not solely inside MultiBackStackNavigation.
        assertTrue(
            "onSheetStatusChangedListener must be assigned inside the shared AppNavigation body " +
                "(found at line ${listenerAssignmentIndex + 1}, but AppNavigation body ends before " +
                "line ${minOf(multiStackStart, legacyStackStart) + 1})",
            listenerAssignmentIndex > appNavigationStart && listenerAssignmentIndex < minOf(multiStackStart, legacyStackStart)
        )

        // Both nav hosts must actually be invoked from within AppNavigation's body (after the
        // listener is wired), so both inherit the shared registration.
        val bodyBeforeNavHosts = lines.subList(appNavigationStart, minOf(multiStackStart, legacyStackStart))
        val callsMultiStack = bodyBeforeNavHosts.any { it.contains("MultiBackStackNavigation(") }
        val callsLegacyStack = bodyBeforeNavHosts.any { it.contains("LegacySingleStackNavigation(") }
        assertTrue("AppNavigation must call MultiBackStackNavigation(...)", callsMultiStack)
        assertTrue("AppNavigation must call LegacySingleStackNavigation(...)", callsLegacyStack)
    }

    private fun navGraphSource(): List<String> {
        var dir = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(6) {
            val candidate = File(dir, "app/src/main/java/com/kkc/sheettracker/navigation/NavGraph.kt")
            if (candidate.exists()) return candidate.readLines()
            val direct = File(dir, "src/main/java/com/kkc/sheettracker/navigation/NavGraph.kt")
            if (direct.exists()) return direct.readLines()
            dir = dir.parentFile ?: return@repeat
        }
        throw IllegalStateException("Could not locate NavGraph.kt from working dir ${System.getProperty("user.dir")}")
    }
}
