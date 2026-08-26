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
    }
}
