package com.kkc.sheettracker.navigation

import com.kkc.sheettracker.data.mixservice.ViewerMixSelection
import org.junit.Assert.assertEquals
import org.junit.Test

class ViewerMixRouteTest {
    @Test
    fun `viewer route remains unchanged without a selected mix`() {
        assertEquals(
            "viewer/648%20-%20Kitchen/648%20-%20Maple.pdf/1",
            viewerRoute("648 - Kitchen", "648 - Maple.pdf", 1)
        )
    }

    @Test
    fun `viewer route carries selected mix identity and mix file`() {
        assertEquals(
            "viewer/648%20-%20Kitchen/648%20-%20Maple.pdf/2?mixName=MIX%20A&mixFile=MIX%20A.mix",
            viewerRoute(
                jobFolderName = "648 - Kitchen",
                pdfFilename = "648 - Maple.pdf",
                page = 2,
                mixSelection = ViewerMixSelection("MIX A", "MIX A.mix")
            )
        )
    }

}
