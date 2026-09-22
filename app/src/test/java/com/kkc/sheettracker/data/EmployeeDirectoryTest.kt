package com.kkc.sheettracker.data

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EmployeeDirectoryTest {
    @get:Rule
    val tmpFolder = TemporaryFolder()

    private fun writeEmployeesJson(baseDir: File, json: String) {
        val timeCardsDir = File(baseDir, ".time_cards")
        timeCardsDir.mkdirs()
        File(timeCardsDir, "employees.json").writeText(json)
    }

    @Test
    fun `reads new fields when present`() = runBlocking {
        val baseDir = tmpFolder.newFolder("base")
        writeEmployeesJson(baseDir, """
            [{"id":"389","name":"Winston Ferguson","displayName":"Fergy","rtcId":12345,"addedBy":"rtc","excluded":false}]
        """.trimIndent())

        EmployeeDirectory.refresh(baseDir)

        val record = EmployeeDirectory.records.first { it.pin == "389" }
        assertEquals("Fergy", record.displayName)
        assertEquals("rtc", record.addedBy)
    }

    @Test
    fun `defaults new fields safely for legacy entries`() = runBlocking {
        val baseDir = tmpFolder.newFolder("base")
        writeEmployeesJson(baseDir, """
            [{"id":"389","name":"Winston Ferguson","excluded":false}]
        """.trimIndent())

        EmployeeDirectory.refresh(baseDir)

        val record = EmployeeDirectory.records.first { it.pin == "389" }
        assertEquals("", record.displayName)
        assertEquals("rtc", record.addedBy)
    }

    @Test
    fun `excludes inactive employees when active is false`() = runBlocking {
        val baseDir = tmpFolder.newFolder("base")
        writeEmployeesJson(baseDir, """
            [
              {"id":"389","name":"Winston Ferguson","excluded":false,"timeclockInactive":false},
              {"id":"901","name":"Kevin Olsen","excluded":false,"timeclockInactive":true}
            ]
        """.trimIndent())

        EmployeeDirectory.refresh(baseDir)

        val pins = EmployeeDirectory.records.map { it.pin }
        assert("389" in pins)
        assert("901" !in pins)
    }
}
