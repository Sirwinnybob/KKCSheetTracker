package com.kkc.sheettracker.data.mixorder

import com.kkc.sheettracker.data.models.PageMetadata
import com.kkc.sheettracker.data.models.Part
import com.kkc.sheettracker.data.models.SheetPlacement
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SheetFeaturesTest {
    private val delta = 1e-9

    // sheetDimensions is [height(y), length(x)]: a 96 x 48 sheet is [48.0, 96.0].
    private val dims = listOf(48.0, 96.0)

    private fun part(width: Double, length: Double, x: Double, y: Double, angle: Double = 0.0) =
        Part(width = width, length = length, sheetPlacement = SheetPlacement(x, y, angle))

    private fun page(
        number: Int,
        files: List<String> = listOf("R$number"),
        parts: List<Part> = listOf(part(20.0, 20.0, 30.0, 10.0)),
        dimensions: List<Double>? = dims,
        continuationOf: Int? = null
    ) = PageMetadata(
        pageNumber = number,
        sheetFiles = files,
        sheetDimensions = dimensions,
        isPartListContinuation = continuationOf != null,
        continuationHeadPage = continuationOf,
        parts = parts
    )

    @Test
    fun countsSmallEdgeAndInterior() {
        val edgeSmall = part(4.0, 20.0, 0.0, 10.0) // minX = 0 -> on edge, small
        val interiorSmall = part(5.0, 20.0, 40.0, 20.0) // x 40..45, y 20..40 -> interior, small
        val large = part(30.0, 30.0, 20.0, 10.0) // not small
        val sheets = smartSheets(listOf(page(1, parts = listOf(edgeSmall, interiorSmall, large))), setOf("r1.pgm"))

        val sheet = requireNotNull(sheets).single()
        assertEquals(1, sheet.headPage)
        assertEquals(listOf("R1.pgm"), sheet.pgms)
        assertEquals(1, sheet.features.smallEdge)
        assertEquals(1, sheet.features.smallInterior)
        assertEquals(3, sheet.features.partCount)
        assertEquals(4.0, sheet.features.minDim, delta)
        assertEquals(4.0 * 20.0 / (48.0 * 96.0), sheet.features.smallEdgeArea, delta)
        assertEquals("1|R1.pgm", sheet.key)
    }

    @Test
    fun vectorFollowsFieldOrder() {
        val features = SheetFeatures(smallEdge = 2, smallInterior = 3, smallEdgeArea = 0.5, minDim = 4.0, partCount = 9)

        assertEquals(FEATURE_COUNT, features.vector().size)
        assertArrayEquals(doubleArrayOf(2.0, 3.0, 0.5, 4.0, 9.0), features.vector(), delta)
    }

    @Test
    fun continuationPartsMergeIntoHead() {
        val head = page(1, parts = listOf(part(30.0, 30.0, 20.0, 10.0), part(4.0, 20.0, 0.0, 10.0)))
        val continuation = page(2, files = emptyList(), parts = listOf(part(30.0, 30.0, 50.0, 10.0)), continuationOf = 1)

        val sheets = requireNotNull(smartSheets(listOf(head, continuation), setOf("r1.pgm")))

        assertEquals(1, sheets.size)
        assertEquals(3, sheets.single().features.partCount)
        assertEquals(1, sheets.single().features.smallEdge)
    }

    @Test
    fun onePartMissingPlacement_null() {
        val missing = Part(width = 10.0, length = 10.0)
        val pages = listOf(page(1, parts = listOf(part(30.0, 30.0, 20.0, 10.0), missing)))

        assertNull(smartSheets(pages, setOf("r1.pgm")))
    }

    @Test
    fun pageNotInDraft_ignored() {
        val draftPage = page(1)
        val otherPage = page(2, parts = listOf(Part(width = 10.0, length = 10.0)))

        val sheets = smartSheets(listOf(draftPage, otherPage), setOf("R1.PGM"))

        assertNotNull(sheets)
        assertEquals(listOf(1), sheets!!.map { it.headPage })
    }

    @Test
    fun missingDims_null() {
        assertNull(smartSheets(listOf(page(1, dimensions = null)), setOf("r1.pgm")))
        assertNull(smartSheets(listOf(page(1, dimensions = listOf(48.0))), setOf("r1.pgm")))
    }

    @Test
    fun noParts_null() {
        assertNull(smartSheets(listOf(page(1, parts = emptyList())), setOf("r1.pgm")))
    }

    @Test
    fun doubleSidedPgmsKeptInOrder() {
        val sheets = requireNotNull(smartSheets(listOf(page(1, files = listOf("R1Z", "R1A"))), setOf("r1z.pgm")))

        assertEquals(listOf("R1A.pgm", "R1Z.pgm"), sheets.single().pgms)
        assertEquals("1|R1A.pgm+R1Z.pgm", sheets.single().key)
    }

    @Test
    fun sheetsReturnedInHeadPageOrder() {
        val pages = listOf(page(3), page(1))

        val sheets = requireNotNull(smartSheets(pages, setOf("r1.pgm", "r3.pgm")))

        assertEquals(listOf(1, 3), sheets.map { it.headPage })
    }
}
