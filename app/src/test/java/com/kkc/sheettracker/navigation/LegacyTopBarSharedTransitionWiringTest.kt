package com.kkc.sheettracker.navigation

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the pinned/morphing header: top-level tab routes must opt into the shared top-bar
 * transition, and the root NavHost must sit inside the shared-scope provider. Without either,
 * KKCTopAppBar silently falls back to sliding with the screen.
 */
class LegacyTopBarSharedTransitionWiringTest {
    @Test
    fun topLevelTabRoutesProvideTheSharedTopBarScope() {
        val legacy = legacySource()

        assertTrue(
            "NavHost must be wrapped in LocalKKCTopBarSharedScope provider",
            Regex("LocalKKCTopBarSharedScope provides this@SharedTransitionLayout\\)\\s*\\{\\s*NavHost\\(")
                .containsMatchIn(legacy)
        )

        listOf("dashboard", "jobs", "search", "supply", "settings").forEach { route ->
            assertTrue(
                "$route route must wrap its body in ProvideKKCTopBarRoute",
                Regex("composable\\(\"${Regex.escape(route)}\"\\)\\s*\\{\\s*ProvideKKCTopBarRoute\\s*\\{")
                    .containsMatchIn(legacy)
            )
        }
    }

    private fun legacySource(): String {
        val source = navGraphSource()
        val start = source.indexOf("private fun LegacySingleStackNavigation(")
        val end = source.indexOf("private fun HoursTabHost(")
        assertTrue("LegacySingleStackNavigation not found", start >= 0)
        assertTrue("HoursTabHost boundary not found", end > start)
        return source.substring(start, end)
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
