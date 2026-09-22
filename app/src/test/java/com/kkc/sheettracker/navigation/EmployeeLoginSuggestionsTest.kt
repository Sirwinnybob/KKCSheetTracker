package com.kkc.sheettracker.navigation

import com.kkc.sheettracker.data.EmployeeDirectory
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Regression guard for the label/value split in [employeeLoginSuggestions]: the
 * HoursLoginDialog suggestion label may prefer an employee's displayName, but the
 * value actually committed on selection must always stay the canonical "$name ($pin)"
 * identity the login flow resolves against -- a swapped Pair here would silently log
 * tablets in using a display name instead of a PIN-resolvable identity.
 */
class EmployeeLoginSuggestionsTest {
    @get:Rule
    val tmpFolder = TemporaryFolder()

    private fun seedEmployees(json: String) = runBlocking {
        val baseDir = tmpFolder.newFolder("base-${System.nanoTime()}")
        val timeCardsDir = File(baseDir, ".time_cards")
        timeCardsDir.mkdirs()
        File(timeCardsDir, "employees.json").writeText(json)
        EmployeeDirectory.refresh(baseDir)
    }

    @Test
    fun `label prefers displayName, value stays canonical`() {
        seedEmployees(
            """[{"id":"389","name":"Winston Ferguson","displayName":"Fergy","excluded":false}]"""
        )

        val result = employeeLoginSuggestions("Fergy")

        assertEquals(1, result.size)
        val (label, value) = result[0]
        assertEquals("Fergy (389)", label)
        assertEquals("Winston Ferguson (389)", value)
    }

    @Test
    fun `falls back to canonical label when no displayName set`() {
        seedEmployees(
            """[{"id":"389","name":"Winston Ferguson","excluded":false}]"""
        )

        val result = employeeLoginSuggestions("Winston")

        assertEquals(1, result.size)
        val (label, value) = result[0]
        assertEquals("Winston Ferguson (389)", label)
        assertEquals("Winston Ferguson (389)", value)
    }
}
