package com.kkc.sheettracker.data.mixservice

import com.kkc.sheettracker.data.models.PageMetadata
import com.kkc.sheettracker.data.models.Material
import com.kkc.sheettracker.data.models.MaterialMetadata
import com.kkc.sheettracker.data.models.SheetStatus
import com.kkc.sheettracker.data.models.StatusCounts
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

    @Test
    fun `service mixes produce separate named entries containing only their ordered pages`() {
        val material = Material(
            pdfFilename = "job - Maple.pdf",
            materialName = "Maple",
            pageCount = 3,
            metadata = MaterialMetadata(pages = pages)
        )
        val mixes = listOf(
            MixDefinition(name = "MIX A", material = "Maple", programs = listOf("R2.pgm", "R1.pgm")),
            MixDefinition(name = "MIX B", material = "Maple", programs = listOf("R3.pgm", "R2.pgm"))
        )

        val entries = resolveMaterialMixEntries(listOf(material), mixes)

        assertEquals(
            listOf(
                MaterialMixEntry(material, "MIX A - Maple", ViewerMixSelection("MIX A", listOf(2, 1))),
                MaterialMixEntry(material, "MIX B - Maple", ViewerMixSelection("MIX B", listOf(3, 2)))
            ),
            entries
        )
    }

    @Test
    fun `unavailable mix service keeps the normal material entry and natural order`() {
        val material = Material(
            pdfFilename = "job - Maple.pdf",
            materialName = "Maple",
            pageCount = 3,
            metadata = MaterialMetadata(pages = pages)
        )

        assertEquals(
            listOf(MaterialMixEntry(material, "Maple", mixSelection = null)),
            resolveMaterialMixEntries(listOf(material), mixes = null)
        )
    }

    @Test
    fun `unresolvable mixes fall back to the normal material entry`() {
        val material = Material(
            pdfFilename = "job - Maple.pdf",
            materialName = "Maple",
            pageCount = 3,
            metadata = MaterialMetadata(pages = pages)
        )

        assertEquals(
            listOf(MaterialMixEntry(material, "Maple", mixSelection = null)),
            resolveMaterialMixEntries(
                listOf(material),
                listOf(MixDefinition(name = "STALE", material = "Maple", programs = listOf("MISSING.pgm")))
            )
        )
    }

    @Test
    fun `unresolvable mix is omitted when another mix resolves`() {
        val material = Material(
            pdfFilename = "job - Maple.pdf",
            materialName = "Maple",
            pageCount = 3,
            metadata = MaterialMetadata(pages = pages)
        )

        assertEquals(
            listOf(MaterialMixEntry(material, "GOOD - Maple", ViewerMixSelection("GOOD", listOf(2, 1)))),
            resolveMaterialMixEntries(
                listOf(material),
                listOf(
                    MixDefinition(name = "STALE", material = "Maple", programs = listOf("MISSING.pgm")),
                    MixDefinition(name = "GOOD", material = "Maple", programs = listOf("R2.pgm", "R1.pgm"))
                )
            )
        )
    }

    @Test
    fun `pending bad part action is material wide and hidden on mix cards`() {
        assertEquals(true, shouldShowPendingBadPartAction(mixSelection = null, pendingCount = 2))
        assertEquals(false, shouldShowPendingBadPartAction(ViewerMixSelection("MIX A", listOf(2, 1)), pendingCount = 2))
        assertEquals(false, shouldShowPendingBadPartAction(mixSelection = null, pendingCount = 0))
    }

    @Test
    fun `mix status counts include only selected pages and treat a shared page independently`() {
        val statuses = mapOf(
            1 to SheetStatus.NOT_STARTED,
            2 to SheetStatus.COMPLETE,
            3 to SheetStatus.HAS_BAD_PARTS
        )

        assertEquals(
            StatusCounts(total = 2, complete = 1, notStarted = 1),
            statusCountsForPages(listOf(2, 1), statuses)
        )
        assertEquals(
            StatusCounts(total = 2, complete = 2, bad = 1),
            statusCountsForPages(listOf(3, 2), statuses)
        )
    }

    @Test
    fun `viewer page order is natural without a selection and restricted with one`() {
        assertEquals(listOf(1, 2, 3), resolveViewerPageOrder(listOf(1, 2, 3), selectedPages = null))
        assertEquals(listOf(3, 1), resolveViewerPageOrder(listOf(1, 2, 3), selectedPages = listOf(3, 9, 1, 3)))
    }
}
