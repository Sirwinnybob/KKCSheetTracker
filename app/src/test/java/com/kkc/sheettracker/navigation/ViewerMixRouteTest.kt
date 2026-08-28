package com.kkc.sheettracker.navigation

import com.kkc.sheettracker.data.mixservice.ViewerMixSelection
import org.junit.Assert.assertEquals
import org.junit.Test

class ViewerMixRouteTest {
    @Test
    fun `viewer route remains unchanged without a selected mix`() {
        assertEquals(
            "viewer/648+-+Kitchen/648+-+Maple.pdf/1",
            viewerRoute("648 - Kitchen", "648 - Maple.pdf", 1)
        )
    }

    @Test
    fun `viewer route carries selected mix identity and physical page order`() {
        assertEquals(
            "viewer/648+-+Kitchen/648+-+Maple.pdf/2?mixName=MIX+A&mixPages=2,5,1",
            viewerRoute(
                jobFolderName = "648 - Kitchen",
                pdfFilename = "648 - Maple.pdf",
                page = 2,
                mixSelection = ViewerMixSelection("MIX A", listOf(2, 5, 1))
            )
        )
    }

    @Test
    fun `mix page argument parser rejects invalid pages and removes duplicates`() {
        assertEquals(listOf(2, 5, 1), parseViewerMixPages("2,5,nope,-1,2,1"))
        assertEquals(null, parseViewerMixPages(null))
    }
}
