package com.kkc.sheettracker.data

import com.kkc.sheettracker.data.models.HardwoodCutlistRow
import com.kkc.sheettracker.data.models.Part
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DoorCutSheetFilterTest {

    @Test
    fun testParseDimension() {
        assertEquals(15.375, parseDimension("15 3/8")!!, 0.0001)
        assertEquals(0.375, parseDimension("3/8")!!, 0.0001)
        assertEquals(15.375, parseDimension("15.375")!!, 0.0001)
        assertEquals(15.0, parseDimension("15")!!, 0.0001)
        assertEquals(null, parseDimension(""))
        assertEquals(null, parseDimension("   "))
        assertEquals(null, parseDimension("3 / 0")) // denominator zero
    }

    @Test
    fun testPreciseMatches() {
        val row = HardwoodCutlistRow(
            cabinets = listOf("34", "35"),
            description = " shaker  Door ",
            width = "15 3/8",
            length = "30 1/2"
        )

        // Exact match
        val partExact = Part(
            cabNumber = 34,
            name = "SHAKER DOOR",
            width = 15.375,
            length = 30.5
        )
        assertTrue(preciseMatches(row, partExact))

        // Rotated match
        val partRotated = Part(
            cabNumber = 35,
            name = "Shaker Door",
            width = 30.5,
            length = 15.375
        )
        assertTrue(preciseMatches(row, partRotated))

        // Tolerance match (0.015 deviation)
        val partWithinTolerance = Part(
            cabNumber = 34,
            name = "Shaker Door",
            width = 15.36,
            length = 30.51
        )
        assertTrue(preciseMatches(row, partWithinTolerance))

        // Out of tolerance match (0.03 deviation)
        val partOutOfTolerance = Part(
            cabNumber = 34,
            name = "Shaker Door",
            width = 15.34,
            length = 30.5
        )
        assertFalse(preciseMatches(row, partOutOfTolerance))

        // Cabinet mismatch
        val partWrongCabinet = Part(
            cabNumber = 36,
            name = "SHAKER DOOR",
            width = 15.375,
            length = 30.5
        )
        assertFalse(preciseMatches(row, partWrongCabinet))

        // Name mismatch
        val partWrongName = Part(
            cabNumber = 34,
            name = "SLAB DRAWER FRONT",
            width = 15.375,
            length = 30.5
        )
        assertFalse(preciseMatches(row, partWrongName))
    }
}
