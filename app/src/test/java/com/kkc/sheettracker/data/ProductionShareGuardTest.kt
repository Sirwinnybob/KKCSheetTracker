package com.kkc.sheettracker.data

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression guard: unit tests must never open a writable tracker store on the live Ready Jobs
 * share. The old BatchSyncTest hardcoded `Y:\Ready Jobs` with `readOnly = false`, so every
 * `gradlew test` on a PC with Y: mapped wrote `batch-sync-worker` tracker events into production
 * (found 2026-09-29; the test was removed and the events cleaned up on 2026-09-30).
 *
 * Path-only assertions that merely mention `Y:` (e.g. HiddenMaterialsRepositoryTest) are fine;
 * a file is flagged only when it both names the share and constructs a writable store.
 */
class ProductionShareGuardTest {

    @Test
    fun noTestSourceOpensAWritableStoreOnTheProductionShare() {
        val testRoot = listOf(File("src/test/java"), File("app/src/test/java")).firstOrNull { it.isDirectory }
            ?: error("unit test sources not found from ${File(".").absolutePath}")
        val productionLiteral = Regex("""["'](?:Y:[\\/]|\\\\\\\\)""", RegexOption.IGNORE_CASE)
        val writableStore = Regex("""readOnly\s*=\s*false""")

        val offenders = testRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name != "ProductionShareGuardTest.kt" }
            .filter { file ->
                val text = file.readText()
                productionLiteral.containsMatchIn(text) && writableStore.containsMatchIn(text)
            }
            .map { it.relativeTo(testRoot).path }
            .toList()

        assertTrue("Tests writing to the production share: $offenders", offenders.isEmpty())
    }
}
