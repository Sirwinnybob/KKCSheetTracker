package com.kkc.sheettracker.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class ViewerRouteTest {
    @Test
    fun `viewer route encodes a selected mix name`() {
        assertEquals(
            "viewer/648+-+WIECHERT/19mm+Pre_Finished.pdf/2?mixName=Kitchen+Island+Mix",
            viewerRoute(
                jobFolderName = "648 - WIECHERT",
                pdfFilename = "19mm Pre_Finished.pdf",
                page = 2,
                mixName = "Kitchen Island Mix"
            )
        )
    }

    @Test
    fun `viewer route remains unchanged when no mix is selected`() {
        assertEquals(
            "viewer/648+-+WIECHERT/19mm+Pre_Finished.pdf/2",
            viewerRoute(
                jobFolderName = "648 - WIECHERT",
                pdfFilename = "19mm Pre_Finished.pdf",
                page = 2,
                mixName = null
            )
        )
        assertEquals(
            "viewer/648+-+WIECHERT/19mm+Pre_Finished.pdf/2",
            viewerRoute(
                jobFolderName = "648 - WIECHERT",
                pdfFilename = "19mm Pre_Finished.pdf",
                page = 2,
                mixName = "   "
            )
        )
    }

    @Test
    fun `coordinator treats a selected mix viewer and natural viewer as different targets`() {
        assertEquals(
            "viewer/648+-+WIECHERT/19mm+Pre_Finished.pdf/2",
            viewerRoute(
                jobFolderName = "648 - WIECHERT",
                pdfFilename = "19mm Pre_Finished.pdf",
                page = 2,
                mixName = null
            )
        )
        org.junit.Assert.assertTrue(
            viewerTargetMatches(
                currentFolder = "648 - WIECHERT",
                currentPdf = "19mm Pre_Finished.pdf",
                currentPage = 2,
                currentMixName = null,
                targetFolder = "648 - WIECHERT",
                targetPdf = "19mm Pre_Finished.pdf",
                targetPage = 2,
                targetMixName = null
            )
        )
        org.junit.Assert.assertFalse(
            viewerTargetMatches(
                currentFolder = "648 - WIECHERT",
                currentPdf = "19mm Pre_Finished.pdf",
                currentPage = 2,
                currentMixName = "Kitchen Island Mix",
                targetFolder = "648 - WIECHERT",
                targetPdf = "19mm Pre_Finished.pdf",
                targetPage = 2,
                targetMixName = null
            )
        )
        org.junit.Assert.assertTrue(
            viewerTargetMatches(
                currentFolder = "648 - WIECHERT",
                currentPdf = "19mm Pre_Finished.pdf",
                currentPage = 2,
                currentMixName = "Kitchen+Island+Mix",
                targetFolder = "648 - WIECHERT",
                targetPdf = "19mm Pre_Finished.pdf",
                targetPage = 2,
                targetMixName = "Kitchen Island Mix"
            )
        )
        org.junit.Assert.assertTrue(
            viewerTargetMatches(
                currentFolder = "648 - WIECHERT",
                currentPdf = "19mm Pre_Finished.pdf",
                currentPage = 2,
                currentMixName = null,
                targetFolder = "648 - WIECHERT",
                targetPdf = "19mm Pre_Finished.pdf",
                targetPage = 2,
                targetMixName = "   "
            )
        )
    }
}
