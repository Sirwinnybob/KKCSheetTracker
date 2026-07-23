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

    @Test
    fun shouldUseDarkPreview_trueOnlyWhenDarkThemeAndStandardSheetsDisabled() {
        assertEquals(true, MoldingLibraryScreenLogic.shouldUseDarkPreview(isDarkTheme = true, useStandardSheets = false))
        assertEquals(false, MoldingLibraryScreenLogic.shouldUseDarkPreview(isDarkTheme = true, useStandardSheets = true))
        assertEquals(false, MoldingLibraryScreenLogic.shouldUseDarkPreview(isDarkTheme = false, useStandardSheets = false))
        assertEquals(false, MoldingLibraryScreenLogic.shouldUseDarkPreview(isDarkTheme = false, useStandardSheets = true))
    }

    private val crownLibrary = MoldingLibrary(
        categories = listOf("Crown"),
        moldings = listOf(
            MoldingLibraryItem("Crown:1", "Crown", "1", "A Face Frame Crown", frameStyle = "face_frame"),
            MoldingLibraryItem("Crown:2", "Crown", "2", "A Frameless Crown", frameStyle = "frameless"),
            MoldingLibraryItem("Crown:3", "Crown", "3", "An Untagged Crown", frameStyle = null),
            MoldingLibraryItem("Crown:4", "Crown", "4", "Another Face Frame Crown", frameStyle = "face_frame")
        )
    )

    @Test
    fun crownFrameGroups_bucketsByFrameStyleInFixedOrder() {
        val groups = MoldingLibraryScreenLogic.crownFrameGroups(crownLibrary.moldings)

        assertEquals(3, groups.size)
        assertEquals(FrameStyleGroup.FACE_FRAME, groups[0].first)
        assertEquals(listOf("Crown:1", "Crown:4"), groups[0].second.map { it.id })
        assertEquals(FrameStyleGroup.FRAMELESS, groups[1].first)
        assertEquals(listOf("Crown:2"), groups[1].second.map { it.id })
        assertEquals(FrameStyleGroup.UNSET, groups[2].first)
        assertEquals(listOf("Crown:3"), groups[2].second.map { it.id })
    }

    @Test
    fun crownFrameGroups_omitsEmptyGroups() {
        val onlyFaceFrame = listOf(MoldingLibraryItem("Crown:1", "Crown", "1", "Face Frame Crown", frameStyle = "face_frame"))
        val groups = MoldingLibraryScreenLogic.crownFrameGroups(onlyFaceFrame)

        assertEquals(1, groups.size)
        assertEquals(FrameStyleGroup.FACE_FRAME, groups[0].first)
    }

    @Test
    fun crownFrameGroups_returnsEmptyListForNoMoldings() {
        assertEquals(0, MoldingLibraryScreenLogic.crownFrameGroups(emptyList()).size)
    }

    @Test
    fun isCrownCategory_matchesBothCrownSpellings() {
        assertEquals(true, MoldingLibraryScreenLogic.isCrownCategory("Crown"))
        assertEquals(true, MoldingLibraryScreenLogic.isCrownCategory("crown"))
        assertEquals(true, MoldingLibraryScreenLogic.isCrownCategory("Crown Molding"))
        assertEquals(false, MoldingLibraryScreenLogic.isCrownCategory("Base"))
        assertEquals(false, MoldingLibraryScreenLogic.isCrownCategory(null))
    }

    @Test
    fun isCrownBrowse_trueOnlyForBlankQueryOnACrownCategory() {
        assertEquals(true, MoldingLibraryScreenLogic.isCrownBrowse("Crown", ""))
        assertEquals(true, MoldingLibraryScreenLogic.isCrownBrowse("Crown Molding", "  "))
        assertEquals(false, MoldingLibraryScreenLogic.isCrownBrowse("Crown", "cove"))
        assertEquals(false, MoldingLibraryScreenLogic.isCrownBrowse("Base", ""))
        assertEquals(false, MoldingLibraryScreenLogic.isCrownBrowse(null, ""))
    }
}
