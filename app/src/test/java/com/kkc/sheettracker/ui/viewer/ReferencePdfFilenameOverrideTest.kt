package com.kkc.sheettracker.ui.viewer

import com.kkc.sheettracker.data.models.ReferenceDocType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReferencePdfFilenameOverrideTest {
    @Test
    fun directFilenameDataPreservesTheRequestedFileWithoutCatalogMappings() {
        val data = referenceViewerDataForPdfFilename("Delivery checklist.pdf")

        assertEquals("Delivery checklist.pdf", data.defaultPdfFilename)
        assertNull(data.virtualMapping)
        assertTrue(data.navigatorCabinetToPages.isEmpty())
        assertTrue(data.navigatorPageDetails.isEmpty())
        assertTrue(data.navigatorPlanViewLabels.isEmpty())
        assertNull(data.warningMessage)
        assertEquals("", referenceViewerDataForPdfFilename("").defaultPdfFilename)
    }

    @Test
    fun typedViewerResumeKeyStaysStableWhilePacketFilesHaveSeparateFolderScopedKeys() {
        val typedKey = referenceResumeKey("Job 17", ReferenceDocType.SHEET, null)
        val packetKey = referenceResumeKey("Job 17", ReferenceDocType.SHEET, "Handoff Sheet.pdf")

        assertEquals("reference_resume_v1_Job 17_SHEET", typedKey)
        assertNotEquals(typedKey, packetKey)
        assertNotEquals(
            packetKey,
            referenceResumeKey("Job 17", ReferenceDocType.SHEET, "Delivery checklist.pdf")
        )
        assertNotEquals(
            packetKey,
            referenceResumeKey("Job 18", ReferenceDocType.SHEET, "Handoff Sheet.pdf")
        )
    }
}
