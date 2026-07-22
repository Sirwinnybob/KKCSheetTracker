package com.kkc.sheettracker.ui.standards

import com.kkc.sheettracker.data.models.MoldingLibrary
import com.kkc.sheettracker.data.models.MoldingLibraryItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MoldingLibraryScreenLogicTest {

    private val library = MoldingLibrary(
        categories = listOf("Crown", "Base"),
        moldings = listOf(
            MoldingLibraryItem("Crown:105", "Crown", "105", "3 1/4\" Flat"),
            MoldingLibraryItem("Base:7", "Base", "7", "Standard Base")
        )
    )

    @Test
    fun moldingsForCategory_filtersByCategory() {
        val crownOnly = MoldingLibraryScreenLogic.moldingsForCategory(library, "Crown")
        assertEquals(1, crownOnly.size)
        assertEquals("Crown:105", crownOnly[0].id)
    }

    @Test
    fun moldingsForCategory_returnsEmptyForUnknownCategory() {
        assertEquals(0, MoldingLibraryScreenLogic.moldingsForCategory(library, "Scribe").size)
    }

    @Test
    fun defaultCategory_returnsFirstCategory() {
        assertEquals("Crown", MoldingLibraryScreenLogic.defaultCategory(library))
    }

    @Test
    fun defaultCategory_returnsNullWhenNoCategories() {
        assertNull(MoldingLibraryScreenLogic.defaultCategory(MoldingLibrary()))
    }

    @Test
    fun svgFileName_appendsDimSuffixWhenMeasurementsShown() {
        assertEquals("105_dim.svg", MoldingLibraryScreenLogic.svgFileName("105", showMeasurements = true))
        assertEquals("105.svg", MoldingLibraryScreenLogic.svgFileName("105", showMeasurements = false))
    }
}
