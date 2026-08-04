package com.kkc.sheettracker.ui.search

import com.kkc.sheettracker.data.models.PartSearchEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class SearchScreenTest {

    @Test
    fun searchResultsFromIndex_preservesEngineSearchEntryFields() {
        val results = searchResultsFromIndex(
            listOf(
                PartSearchEntry(
                    jobFolderName = "123 - Kitchen",
                    jobNumber = "123",
                    materialName = "White Melamine",
                    pdfFilename = "Kitchen.pdf",
                    pageNumber = 4,
                    partNumber = 27,
                    partName = "Base End",
                    room = "Kitchen",
                    cabNumber = 9
                )
            )
        )

        assertEquals(
            SearchResult(
                jobFolderName = "123 - Kitchen",
                jobNumber = "123",
                materialName = "White Melamine",
                pdfFilename = "Kitchen.pdf",
                pageNumber = 4,
                partNumber = 27,
                partName = "Base End",
                room = "Kitchen",
                cabNumber = 9
            ),
            results.single()
        )
    }
}
