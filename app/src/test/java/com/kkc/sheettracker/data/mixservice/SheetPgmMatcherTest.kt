package com.kkc.sheettracker.data.mixservice

import com.kkc.sheettracker.data.models.PageMetadata
import org.junit.Assert.assertEquals
import org.junit.Test

class SheetPgmMatcherTest {
    @Test
    fun `buildManageCodeRows appends pgm extension per page in pdf order`() {
        val pages = listOf(
            PageMetadata(pageNumber = 2, sheetFiles = listOf("R2")),
            PageMetadata(pageNumber = 1, sheetFiles = listOf("R1"))
        )
        val rows = buildManageCodeRows(pages)
        assertEquals(listOf(1, 2), rows.map { it.pageNumber })
        assertEquals(listOf("R1.pgm"), rows[0].pgmFiles)
        assertEquals("R1.pgm", rows[0].editablePgm)
    }

    @Test
    fun `buildManageCodeRows keeps A before Z as one combined row and Z is editable`() {
        val pages = listOf(PageMetadata(pageNumber = 1, sheetFiles = listOf("R590402A", "R590402Z")))
        val rows = buildManageCodeRows(pages)
        assertEquals(listOf("R590402A.pgm", "R590402Z.pgm"), rows.single().pgmFiles)
        assertEquals("R590402Z.pgm", rows.single().editablePgm)
    }

    @Test
    fun `buildManageCodeRows skips excluded, hidden, and continuation pages`() {
        val pages = listOf(
            PageMetadata(pageNumber = 1, sheetFiles = listOf("R1"), trackingExcluded = true),
            PageMetadata(pageNumber = 2, sheetFiles = listOf("R2"), hiddenInApp = true),
            PageMetadata(pageNumber = 3, sheetFiles = listOf("R3"), isPartListContinuation = true),
            PageMetadata(pageNumber = 4, sheetFiles = listOf("R4"))
        )
        assertEquals(listOf(4), buildManageCodeRows(pages).map { it.pageNumber })
    }

    @Test
    fun `applyExistingOrder reorders matched rows and appends unmapped ones in original order`() {
        val rows = listOf(
            ManageCodeRow(pageNumber = 1, pgmFiles = listOf("R1.pgm"), editablePgm = "R1.pgm"),
            ManageCodeRow(pageNumber = 2, pgmFiles = listOf("R2.pgm"), editablePgm = "R2.pgm"),
            ManageCodeRow(pageNumber = 3, pgmFiles = listOf("R3.pgm"), editablePgm = "R3.pgm")
        )
        val ordered = applyExistingOrder(rows, listOf("R2.pgm", "R1.pgm"))
        assertEquals(listOf(2, 1, 3), ordered.map { it.pageNumber })
    }
}
