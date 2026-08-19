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
 *
 * `cncInvalidationDoesNotEmitASecondWatcherEpoch` specifically guards against the historical
 * bug where `onCncJobsChanged` independently re-emitted `watcherRefreshSignal` from within its
 * own callback body, even though `TrackerChangeMonitor`'s separate `onWatcherRefreshRequested`
 * callback already owns emitting that signal for this event -- causing a duplicate/redundant
 * refresh. That check used to be a blunt whole-file count of `watcherRefreshSignal.value =
 * System.currentTimeMillis()` occurrences (expected exactly 2), back when the file happened to
 * have only 2 legitimate call sites. NavGraph.kt has since grown legitimate additional call
 * sites (e.g. the live jobs-index client's onSnapshot/onDelta/onConnectionState callbacks) that
 * have nothing to do with `onCncJobsChanged`, so a whole-file count is no longer a reliable proxy
 * for the invariant and would break on any future unrelated feature that also needs to bump this
 * signal. The check below instead locates the `onCncJobsChanged` callback block specifically (by
 * finding its enclosing braces around the `jobFolderNames.forEach { ... }` line) and asserts that
 * block alone contains no `watcherRefreshSignal` write, which is the actual invariant this test
 * claims to guard. The closing brace is located by requiring matching indentation with the
 * `onCncJobsChanged` line itself, not just the first bare `}`/`},` encountered -- this correctly
 * skips past any nested blocks (`if`, `when`, another `forEach`, etc.) that might be added inside
 * the callback body in the future, since a nested block's own closing brace is always more
 * indented than the lambda's opening line.
 */
class NavGraphCncSyncWiringTest {

    @Test
    fun cncInvalidationDoesNotEmitASecondWatcherEpoch() {
        val lines = navGraphSource()
        val source = lines.joinToString("\n")
        assertEquals(
            1,
            Regex("jobFolderNames\\.forEach \\{ scanCoordinator\\.unifiedEngine\\.invalidateJob\\(it\\) \\}")
                .findAll(source).count()
        )

        // Locate the onCncJobsChanged callback block itself, rather than counting
        // watcherRefreshSignal writes across the whole file (see class doc comment above).
        val forEachLineIndex = lines.indexOfFirst {
            it.contains("jobFolderNames.forEach { scanCoordinator.unifiedEngine.invalidateJob(it) }")
        }
        assertTrue("Could not locate the onCncJobsChanged forEach line in NavGraph.kt", forEachLineIndex >= 0)

        // Walk backward to the line that opens the onCncJobsChanged lambda...
        val callbackStartIndex = (forEachLineIndex downTo 0).firstOrNull { lines[it].contains("onCncJobsChanged") }
        assertTrue(
            "Could not locate the enclosing 'onCncJobsChanged = { jobFolderNames ->' line above the forEach call",
            callbackStartIndex != null
        )

        // ...and forward to the line that closes it, so the checked window is exactly the
        // callback's body regardless of incidental formatting changes elsewhere in the file.
        //
        // The closing brace must match the indentation of the `onCncJobsChanged` line itself,
        // not just be the first bare "}" / "}," encountered. If the callback body ever grows a
        // nested block (an if, another forEach, a when, a try) between the forEach line and the
        // callback's real closing brace, a naive "first bare closing brace" scan would stop at
        // that nested block's own closing brace instead -- truncating callbackBlock before it
        // reaches the actual end of onCncJobsChanged, and silently failing to catch a
        // watcherRefreshSignal write added after that point. In this codebase's Kotlin
        // formatting convention, a "}" that closes a top-level lambda argument sits at the same
        // indentation as the line that opened it, regardless of how many nested blocks exist
        // inside the lambda body, so requiring matching indentation reliably finds the true
        // closing brace without needing full brace-depth counting.
        val expectedIndent = lines[callbackStartIndex!!].takeWhile { it == ' ' || it == '\t' }
        val callbackEndIndex = (forEachLineIndex until lines.size).firstOrNull {
            val line = lines[it]
            val trimmed = line.trim()
            (trimmed == "}" || trimmed == "},") &&
                line.takeWhile { c -> c == ' ' || c == '\t' } == expectedIndent
        }
        assertTrue(
            "Could not locate the closing brace of the onCncJobsChanged callback",
            callbackEndIndex != null
        )

        val callbackBlock = lines.subList(callbackStartIndex, callbackEndIndex!! + 1)
        assertTrue(
            "onCncJobsChanged must not independently write watcherRefreshSignal -- " +
                "TrackerChangeMonitor's onWatcherRefreshRequested callback already owns emitting " +
                "that signal for CNC-completion events (code-review finding #23). Offending block:\n" +
                callbackBlock.joinToString("\n"),
            callbackBlock.none { it.contains("watcherRefreshSignal") }
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
