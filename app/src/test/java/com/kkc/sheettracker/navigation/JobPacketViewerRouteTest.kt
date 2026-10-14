package com.kkc.sheettracker.navigation

import com.kkc.sheettracker.data.models.JobPdfRef
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JobPacketViewerRouteTest {
    @Test
    fun jobPacketRouteEncodesFolderFilenameAndUserFacingLabel() {
        val route = jobPacketViewerRoute(
            jobFolderName = "Job 7/Island",
            document = JobPdfRef(
                pdfFilename = "Delivery checklist #2.pdf",
                label = "Delivery Checklist & Notes"
            )
        )

        assertEquals(
            "referenceViewer/Job+7%2FIsland/SHEET/1" +
                "?pdfFilenameOverride=Delivery%20checklist%20%232.pdf" +
                "&documentTitleOverride=Delivery%20Checklist%20%26%20Notes",
            route
        )
    }

    @Test
    fun jobPacketRoutePreservesLiteralPlusAndPercentCharacters() {
        val route = jobPacketViewerRoute(
            jobFolderName = "Job",
            document = JobPdfRef(
                pdfFilename = "assembly+sample%20draft.pdf",
                label = "Room + Plans 100%"
            )
        )

        assertEquals(
            "referenceViewer/Job/SHEET/1" +
                "?pdfFilenameOverride=assembly%2Bsample%2520draft.pdf" +
                "&documentTitleOverride=Room%20%2B%20Plans%20100%25",
            route
        )
    }

    @Test
    fun jobPacketRouteKeepsAnExplicitEmptyFilenameOverrideFromFallingBack() {
        assertEquals(
            "referenceViewer/Job/SHEET/1?pdfFilenameOverride=",
            jobPacketViewerRoute("Job", JobPdfRef(pdfFilename = ""))
        )
    }

    @Test
    fun bothLiveNavGraphsAcceptAndForwardOptionalDocumentOverrides() {
        val source = source("navigation/NavGraph.kt")
        val firstPattern = "referenceViewer/{folderName}/{docType}/{startPage}?"

        assertEquals(2, Regex(Regex.escape(firstPattern)).findAll(source).count())
        assertEquals(2, Regex("pdfFilenameOverride\\s*=\\s*pdfFilenameOverride").findAll(source).count())
        assertEquals(2, Regex("documentTitleOverride\\s*=\\s*documentTitleOverride").findAll(source).count())
    }

    @Test
    fun archiveViewerCarriesFilenameAndTitleOverridesWithoutDroppingReadOnlyMarkupStore() {
        val source = source("navigation/ArchiveJobDetailHost.kt")

        assertTrue(source.contains("referenceViewer/{docType}/{startPage}?"))
        assertTrue(source.contains("pdfFilenameOverride = pdfFilenameOverride"))
        assertTrue(source.contains("documentTitleOverride = documentTitleOverride"))
        assertTrue(source.contains("overridePdfMarkupStore = session.pdfMarkupStore"))
        assertTrue(source.contains("pdfMarkupReadOnly = true"))
    }

    private fun source(relativePath: String): String {
        var dir = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(8) {
            val candidate = File(dir, "app/src/main/java/com/kkc/sheettracker/$relativePath")
            if (candidate.exists()) return candidate.readText()
            val direct = File(dir, "src/main/java/com/kkc/sheettracker/$relativePath")
            if (direct.exists()) return direct.readText()
            dir = dir.parentFile ?: return@repeat
        }
        error("Unable to locate source file $relativePath")
    }
}
