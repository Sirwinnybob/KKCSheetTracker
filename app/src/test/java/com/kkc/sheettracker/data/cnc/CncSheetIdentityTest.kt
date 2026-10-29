package com.kkc.sheettracker.data.cnc

import com.kkc.sheettracker.data.models.*
import org.junit.Assert.*
import org.junit.Test

class CncSheetIdentityTest {
    @Test fun zaFallbackAndSidecarPrecedence() {
        assertEquals(listOf("R1Z", "R1A"), inferSheetFiles(PageMetadata(sheetId = "R1Z")))
        assertEquals(listOf("R1Z", "R1A"), inferSheetFiles(PageMetadata(sheetId = "R1A")))
        assertEquals(listOf("Explicit"), inferSheetFiles(PageMetadata(sheetId = "R1Z", sheetFiles = listOf("Explicit"))))
        assertTrue(inferSheetFiles(null).isEmpty())
    }
    @Test fun continuationUsesOnlyBoundedHead() {
        val material = Material("m.pdf", "m", 3, metadata = MaterialMetadata(pages = listOf(
            PageMetadata(pageNumber = 2, isPartListContinuation = true, continuationHeadPage = 1),
            PageMetadata(pageNumber = 3, isPartListContinuation = true, continuationHeadPage = 99))))
        assertEquals(1, material.resolveHeadPage(2))
        assertEquals(3, material.resolveHeadPage(3))
    }
}

