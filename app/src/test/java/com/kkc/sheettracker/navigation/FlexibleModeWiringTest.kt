package com.kkc.sheettracker.navigation

import java.io.File
import org.junit.Assert.assertFalse
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

    @Test
    fun jobsComposableBuildsAllFourSpecsWhenFlexible() {
        val source = navGraphSource()
        val occurrences = Regex("if \\(flexibleModeEnabled\\)").findAll(source).count()
        assertTrue("expected at least 2 flexibleModeEnabled branches in the jobs composables (one per duplicate)", occurrences >= 2)
        val rememberCalls = listOf(
            "rememberCncJobsSpec(",
            "rememberHardwoodsJobsSpec(",
            "rememberAssemblyJobsSpec(",
            "rememberSpecialtyJobsSpec("
        )
        rememberCalls.forEach { call ->
            val count = Regex(Regex.escape(call)).findAll(source).count()
            assertTrue("$call should appear at least 2 times (once per duplicated jobs composable — each spec is hoisted into a single val and built unconditionally instead of inside a workMode branch, so the raw occurrence count doesn't double)", count >= 2)
        }
    }

    @Test
    fun dashboardComposableOffersOnlyCncAndHardwoodsWhenFlexible() {
        val source = navGraphSource()
        val occurrences = Regex("flexible_dashboard_last_mode").findAll(source).count()
        assertTrue("expected the persisted dashboard-mode pref key to appear at least twice (once per duplicated dashboard composable)", occurrences >= 2)
        val assemblySpecInFlexibleBranch = Regex(
            "flexibleModeEnabled[\\s\\S]{0,400}UnifiedModeDashboardSpec\\.Assembly"
        ).containsMatchIn(source)
        assertFalse("Assembly must not be built inside a flexibleModeEnabled dashboard branch", assemblySpecInFlexibleBranch)
    }

    @Test
    fun dashboardTabStaysReachableInAssemblyOrSpecialtyWhenFlexible() {
        val source = navGraphSource()
        // Both nav-visibility gates that hide the Dashboard tab for Assembly/Specialty must be
        // qualified with `!flexibleModeEnabled` so Flexible Mode keeps Dashboard reachable.
        val guardedGateCount = Regex(
            "!flexibleModeEnabled\\s*&&\\s*\\(workMode == WorkMode\\.ASSEMBLY \\|\\| workMode == WorkMode\\.SPECIALTY\\)"
        ).findAll(source).count()
        assertTrue(
            "expected at least 3 nav-visibility gates (startRoute, and visibleDestinations x2) to check !flexibleModeEnabled before hiding Dashboard for Assembly/Specialty, found $guardedGateCount",
            guardedGateCount >= 3
        )
        // The old unguarded form must be gone everywhere.
        val unguardedGate = Regex(
            "(?<!!flexibleModeEnabled && )\\(?workMode == WorkMode\\.ASSEMBLY \\|\\| workMode == WorkMode\\.SPECIALTY\\)?\\s*->\\s*\"jobs\""
        ).containsMatchIn(source)
        assertFalse("startRoute must not hide dashboard for Assembly/Specialty without checking flexibleModeEnabled first", unguardedGate)
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
