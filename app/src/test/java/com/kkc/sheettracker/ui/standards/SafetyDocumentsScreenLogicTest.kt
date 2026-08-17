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
    fun tabTitles_placesSafetyCommitteeMeetingsBetweenDocumentsAndConcerns() {
        assertEquals(
            listOf("Documents (PDFs)", "Safety Committee Meetings", "Safety Concerns"),
            SafetyDocumentsScreenLogic.tabTitles
        )
    }

    @Test
    fun meetingDocumentsDir_resolvesUnderSafetyFolder() {
        val basePath = Files.createTempDirectory("safety-meetings-path").toFile()

        val result = SafetyDocumentsScreenLogic.meetingDocumentsDir(basePath.absolutePath)

        assertEquals(
            File(File(basePath, ".safety"), "safety_meetings").absolutePath,
            result.absolutePath
        )
    }

    @Test
    fun listPdfs_readsOnlyMeetingPdfsSortedByName() {
        val basePath = Files.createTempDirectory("safety-meetings-list").toFile()
        val meetingsDir = File(File(basePath, ".safety"), "safety_meetings").apply { mkdirs() }
        File(meetingsDir, "2026-08 Meeting.PDF").writeText("x")
        File(meetingsDir, "2026-07 Meeting.pdf").writeText("x")
        File(meetingsDir, "agenda.txt").writeText("x")
        File(meetingsDir, "Archive.pdf").mkdirs()

        val result = SafetyDocumentsScreenLogic.listPdfs(
            SafetyDocumentsScreenLogic.meetingDocumentsDir(basePath.absolutePath)
        )

        assertEquals(
            listOf("2026-07 Meeting.pdf", "2026-08 Meeting.PDF"),
            result.map { it.name }
        )
    }

    @Test
    fun hasSafetyConcernsAccess_grantsWhenSubscriberOrAdmin() {
        assertEquals(true, SafetyDocumentsScreenLogic.hasSafetyConcernsAccess(safetySubscriber = true, adminMode = false))
        assertEquals(true, SafetyDocumentsScreenLogic.hasSafetyConcernsAccess(safetySubscriber = false, adminMode = true))
        assertEquals(true, SafetyDocumentsScreenLogic.hasSafetyConcernsAccess(safetySubscriber = true, adminMode = true))
        assertEquals(false, SafetyDocumentsScreenLogic.hasSafetyConcernsAccess(safetySubscriber = false, adminMode = false))
    }
}
