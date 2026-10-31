package com.kkc.sheettracker.data.mixfiles

import com.kkc.sheettracker.data.models.PageMetadata
import com.kkc.sheettracker.data.models.SheetStatus
import org.junit.Assert.*
import org.junit.Test

class SheetMatchingTest {
    @Test fun matchesCaseInsensitivelyAndKeepsMixOrder() {
        val items = matchEntries(listOf(entry("b.PGM"), entry("a.pgm")), pages("A", "B"))
        assertEquals(listOf(2, 1), items.map { (it as MixItem.Sheet).page.pageNumber })
        assertEquals(listOf(listOf(0), listOf(1)), items.map { it.entryIndices })
        assertEquals(listOf("b.PGM"), (items[0] as MixItem.Sheet).pgms)
        assertEquals(listOf(2, 1), viewerPageOrder(items))
    }

    @Test fun unmatchedEntryStaysInPosition() {
        val items = matchEntries(listOf(entry("A.pgm"), entry("X.pgm"), entry("B.pgm")), pages("A", "B"))
        assertTrue(items[0] is MixItem.Sheet)
        assertEquals(MixItem.Unmatched(listOf(1), "X.pgm"), items[1])
        assertTrue(items[2] is MixItem.Sheet)
    }

    @Test fun adjacentAzSameQuantityCollapses() {
        val items = matchEntries(listOf(entry("R1A.pgm", 4, 2), entry("R1Z.pgm", 4, 2)), azPages())
        val item = items.single() as MixItem.Sheet
        assertEquals(listOf(0, 1), item.entryIndices)
        assertEquals(listOf("R1A.pgm", "R1Z.pgm"), item.pgms)
        assertEquals(4, item.quantity)
        assertEquals(2, item.remaining)
        assertEquals(false, item.aOnly)
        assertEquals(false, item.zOnly)
    }

    @Test fun splitAzShowsTwoRowsLabelled() {
        val items = matchEntries(listOf(entry("R1Z.pgm"), entry("R1A.pgm")), azPages()).map { it as MixItem.Sheet }
        assertEquals(2, items.size)
        assertEquals(true, items[0].zOnly)
        assertEquals(false, items[0].aOnly)
        assertEquals(true, items[1].aOnly)
        assertEquals(false, items[1].zOnly)
    }

    @Test fun unequalQuantityOrRemainingDoesNotCollapse() {
        for (entries in listOf(
            listOf(entry("R1A.pgm", 2), entry("R1Z.pgm", 3)),
            listOf(entry("R1A.pgm", 3, 2), entry("R1Z.pgm", 3, 1))
        )) assertEquals(2, matchEntries(entries, azPages()).size)
    }

    @Test fun hiddenPageMatchesWithHiddenFlag() {
        val pages = sheetPages(listOf(PageMetadata(9, sheetFiles = listOf("R9"), hiddenInApp = true)))
        val item = matchEntries(listOf(entry("r9.pgm")), pages).single() as MixItem.Sheet
        assertTrue(item.page.hidden)
        assertEquals(9, item.page.pageNumber)
    }

    @Test fun excludedMetadataIsHiddenAndVisibleHeadStillWinsMatching() {
        val pages = sheetPages(listOf(
            PageMetadata(1, sheetFiles = listOf("R1"), trackingExcluded = true),
            PageMetadata(2, sheetFiles = listOf("R1")),
        ))
        assertTrue(pages.first().hidden)
        assertFalse(pages.first().continuation)
        val preferred = matchEntries(listOf(entry("R1.pgm")), pages).single() as MixItem.Sheet
        assertEquals(2, preferred.page.pageNumber)
        val fallback = matchEntries(listOf(entry("R1.pgm")), pages.take(1)).single() as MixItem.Sheet
        assertEquals(1, fallback.page.pageNumber)
        assertTrue(fallback.page.hidden)
        assertTrue(completedPgms(listOf(fallback)) { SheetStatus.NOT_STARTED }.isEmpty())
    }

    @Test fun continuationLosesToHeadPage() {
        val pages = sheetPages(listOf(
            PageMetadata(1, sheetFiles = listOf("R1"), isPartListContinuation = true),
            PageMetadata(2, sheetFiles = listOf("R1"), hiddenInApp = true),
            PageMetadata(3, sheetFiles = listOf("R1"))
        ))
        assertEquals(3, (matchEntries(listOf(entry("R1.pgm")), pages).single() as MixItem.Sheet).page.pageNumber)
        assertEquals(2, (matchEntries(listOf(entry("R1.pgm")), pages.take(2)).single() as MixItem.Sheet).page.pageNumber)
        assertEquals(1, (matchEntries(listOf(entry("R1.pgm")), pages.take(1)).single() as MixItem.Sheet).page.pageNumber)
    }

    @Test fun repeatedPgmKeepsEveryOccurrence() {
        val items = matchEntries(listOf(entry("A.pgm"), entry("B.pgm"), entry("a.PGM")), pages("A", "B"))
        assertEquals(listOf(listOf(0), listOf(1), listOf(2)), items.map { it.entryIndices })
        assertEquals(listOf("A.pgm"), completedPgms(items) { if (it == 1) SheetStatus.COMPLETE else SheetStatus.NOT_STARTED })
        assertEquals(listOf(1, 2), viewerPageOrder(items))
    }

    @Test fun lockedAzPageListsBothPgms() {
        val items = matchEntries(listOf(entry("R1Z.pgm")), azPages())
        assertEquals(listOf("R1A.pgm", "R1Z.pgm"), completedPgms(items) { SheetStatus.HAS_BAD_PARTS })
    }

    @Test fun isLockedStatusTable() {
        assertEquals(listOf(false, false, true, false, true, true), SheetStatus.entries.map(::isLockedStatus))
    }

    @Test fun sheetPagesUsesRealMetadataFallbackAndRetainsEverySheetPage() {
        val pages = sheetPages(listOf(
            PageMetadata(4, sheetId = "R4Z", thumbnailPath = "thumb.png", trackingExcluded = true),
            PageMetadata(2, sheetFiles = listOf("R2Z", "R2A"), isPartListContinuation = true),
            PageMetadata(1)
        ))
        assertEquals(listOf(2, 4), pages.map { it.pageNumber })
        assertEquals(listOf("R2A.pgm", "R2Z.pgm"), pages[0].pgmFiles)
        assertEquals(listOf("R4A.pgm", "R4Z.pgm"), pages[1].pgmFiles)
        assertTrue(pages[0].continuation)
        assertEquals("thumb.png", pages[1].thumbnailPath)
    }

    private fun pages(vararg stems: String) = sheetPages(stems.mapIndexed { i, stem -> PageMetadata(i + 1, sheetFiles = listOf(stem)) })
    private fun azPages() = sheetPages(listOf(PageMetadata(1, sheetFiles = listOf("R1A", "R1Z"))))
    private fun entry(pgm: String, quantity: Int = 1, remaining: Int? = null) = MixFileEntry(
        "C:\\PGM\\$pgm", pgm, linkedMapOf(), null, quantity, remaining, true, false
    )
}
