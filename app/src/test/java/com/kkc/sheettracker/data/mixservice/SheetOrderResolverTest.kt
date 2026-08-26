package com.kkc.sheettracker.data.mixservice

import com.kkc.sheettracker.data.models.PageMetadata
import org.junit.Assert.assertEquals
import org.junit.Test

class SheetOrderResolverTest {
    private val pages = listOf(
        PageMetadata(pageNumber = 1, sheetFiles = listOf("R1")),
        PageMetadata(pageNumber = 2, sheetFiles = listOf("R2")),
        PageMetadata(pageNumber = 3, sheetFiles = listOf("R3"))
    )

    @Test
    fun `no mix falls back to natural visible page order`() {
        assertEquals(listOf(1, 2), reorderVisiblePages(pages, naturalOrder = listOf(1, 2), mixPrograms = emptyList()))
    }

    @Test
    fun `mix reorders mapped pages and keeps them within the natural visible set`() {
        val ordered = reorderVisiblePages(pages, naturalOrder = listOf(1, 2, 3), mixPrograms = listOf("R2.pgm", "R1.pgm", "R3.pgm"))
        assertEquals(listOf(2, 1, 3), ordered)
    }

    @Test
    fun `pages not covered by a partial mix are appended after mapped pages, in natural order`() {
        val ordered = reorderVisiblePages(pages, naturalOrder = listOf(1, 2, 3), mixPrograms = listOf("R2.pgm"))
        assertEquals(listOf(2, 1, 3), ordered)
    }

    @Test
    fun `a mix entry for a page excluded from natural order is ignored`() {
        val ordered = reorderVisiblePages(pages, naturalOrder = listOf(1, 3), mixPrograms = listOf("R2.pgm", "R3.pgm", "R1.pgm"))
        assertEquals(listOf(3, 1), ordered)
    }

    @Test
    fun `a page with no resolvable sheet file is appended rather than dropped`() {
        val pagesWithOneUnresolvable = pages + PageMetadata(pageNumber = 4, sheetFiles = emptyList(), sheetId = "")
        val ordered = reorderVisiblePages(pagesWithOneUnresolvable, naturalOrder = listOf(1, 2, 3, 4), mixPrograms = listOf("R2.pgm", "R1.pgm", "R3.pgm"))
        assertEquals(listOf(2, 1, 3, 4), ordered)
    }
}
