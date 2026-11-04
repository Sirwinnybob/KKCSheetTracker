package com.kkc.sheettracker.data.mixorder

import com.google.gson.Gson
import com.kkc.sheettracker.data.models.Part
import com.kkc.sheettracker.data.models.SheetPlacement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PartFootprintTest {
    private val gson = Gson()
    private val delta = 1e-3

    private fun assertFootprint(expected: Footprint, actual: Footprint?) {
        requireNotNull(actual) { "expected footprint but was null" }
        assertEquals(expected.minX, actual.minX, delta)
        assertEquals(expected.minY, actual.minY, delta)
        assertEquals(expected.maxX, actual.maxX, delta)
        assertEquals(expected.maxY, actual.maxY, delta)
    }

    @Test
    fun angle90_matchesJob720Page2Part1() {
        val part = Part(
            width = 14.768,
            length = 17.02,
            sheetPlacement = SheetPlacement(92.9152, 0.4036, 90.0)
        )

        assertFootprint(Footprint(75.8952, 0.4036, 92.9152, 15.1716), part.footprint())
    }

    @Test
    fun angle0_widthAlongX() {
        val part = Part(
            width = 4.0,
            length = 17.02,
            sheetPlacement = SheetPlacement(82.4956, 0.4036, 0.0)
        )

        assertFootprint(Footprint(82.4956, 0.4036, 86.4956, 17.4236), part.footprint())
    }

    @Test
    fun missingPlacement_null() {
        val part = Part(width = 4.0, length = 17.0)

        assertNull(part.footprint())
    }

    @Test
    fun isSmall_trueWhenEitherSideUnderSixInches() {
        assertTrue(Part(width = 5.9, length = 30.0).isSmall())
        assertTrue(Part(width = 30.0, length = 5.9).isSmall())
    }

    @Test
    fun isSmall_falseAtExactlySixInches() {
        assertFalse(Part(width = 6.0, length = 6.0).isSmall())
    }

    @Test
    fun onEdge_footprintWithinBandOfBottomEdgeIsTrue() {
        val footprint = Footprint(10.0, 2.9, 15.0, 8.0)

        assertTrue(footprint.onEdge(sheetLengthX = 96.0, sheetHeightY = 48.0))
    }

    @Test
    fun onEdge_footprintOutsideBandIsFalse() {
        val footprint = Footprint(10.0, 3.1, 15.0, 8.0)

        assertFalse(footprint.onEdge(sheetLengthX = 96.0, sheetHeightY = 48.0))
    }

    @Test
    fun onEdge_footprintAwayFromAllEdgesIsFalse() {
        val footprint = Footprint(90.0, 20.0, 92.9, 25.0)

        assertFalse(footprint.onEdge(sheetLengthX = 96.0, sheetHeightY = 48.0))
    }

    @Test
    fun onEdge_exactlyBandFromFarEdgeCounts() {
        val footprint = Footprint(90.0, 20.0, 93.0, 25.0)

        assertTrue(footprint.onEdge(sheetLengthX = 96.0, sheetHeightY = 48.0))
    }

    @Test
    fun gsonParsesSheetPlacement() {
        val part = gson.fromJson(
            """{"number":1,"width":4,"length":17,"sheetPlacement":{"x":1.5,"y":2.5,"angle":90.0}}""",
            Part::class.java
        )

        assertEquals(SheetPlacement(1.5, 2.5, 90.0), part.sheetPlacement)
    }
}
