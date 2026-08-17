package com.kkc.sheettracker.ui.standards

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SafetyDocumentsScreenLogicTest {

    @Test
    fun listPdfs_returnsPdfFilesSortedByName() {
        val safetyDir = Files.createTempDirectory("safety-test").toFile()
        File(safetyDir, "SDS Book.pdf").writeText("x")
        File(safetyDir, "Heat Illness Prevention Plan.pdf").writeText("x")
        File(safetyDir, ".metadata").mkdirs()

        val result = SafetyDocumentsScreenLogic.listPdfs(safetyDir)

        assertEquals(listOf("Heat Illness Prevention Plan.pdf", "SDS Book.pdf"), result.map { it.name })
    }

    @Test
    fun listPdfs_returnsEmptyList_whenFolderMissing() {
        val missing = File(Files.createTempDirectory("safety-test-missing").toFile(), "nope")
        assertEquals(emptyList<File>(), SafetyDocumentsScreenLogic.listPdfs(missing))
    }

    @Test
    fun hasSafetyConcernsAccess_grantsWhenSubscriberOrAdmin() {
        assertEquals(true, SafetyDocumentsScreenLogic.hasSafetyConcernsAccess(safetySubscriber = true, adminMode = false))
        assertEquals(true, SafetyDocumentsScreenLogic.hasSafetyConcernsAccess(safetySubscriber = false, adminMode = true))
        assertEquals(true, SafetyDocumentsScreenLogic.hasSafetyConcernsAccess(safetySubscriber = true, adminMode = true))
        assertEquals(false, SafetyDocumentsScreenLogic.hasSafetyConcernsAccess(safetySubscriber = false, adminMode = false))
    }
}
