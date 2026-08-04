package com.kkc.sheettracker.ui.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UnifiedReferenceViewerTest {

    @Test
    fun buildVirtualReverseIndex_buildsLookupIgnoringCase() {
        val mapping = UnifiedVirtualPageMapping(
            totalDisplayPages = 3,
            defaultPdfFilename = "fallback.pdf",
            sourceByDisplayPage = mapOf(
                1 to UnifiedVirtualPageSource(pdfFilename = "A.pdf", page = 2),
                2 to UnifiedVirtualPageSource(pdfFilename = "B.PDF", page = 7)
            )
        )

        val index = buildVirtualReverseIndex(mapping)

        assertEquals(1, index["a.pdf" to 2])
        assertEquals(2, index["b.pdf" to 7])
    }

    @Test
    fun resolveDisplayPageFromSource_returnsNullForInvalidInputs() {
        val index = mapOf("sample.pdf" to 4).mapKeys { it.key to 1 }

        assertNull(resolveDisplayPageFromSource(index, "", 1))
        assertNull(resolveDisplayPageFromSource(index, "sample.pdf", 0))
    }

    @Test
    fun resolveDisplayPageFromSource_returnsMappedDisplayPage() {
        val reverseIndex = mapOf(
            ("one.pdf" to 3) to 10,
            ("two.pdf" to 5) to 11
        )

        val page = resolveDisplayPageFromSource(reverseIndex, "TWO.PDF", 5)

        assertEquals(11, page)
    }

    @Test
    fun prefixCabinetMatches_returnsPrefixHitsSorted() {
        val results = prefixCabinetMatches(
            cabinetToPages = mapOf(
                "20" to listOf(4),
                "101" to listOf(3),
                "10" to listOf(1, 2)
            ),
            query = "10"
        )

        assertEquals(listOf("10", "101"), results.map { it.cabinet })
        assertEquals(listOf(1, 2), results.first().pages)
    }

    @Test
    fun prefixCabinetMatches_supportsMultiHitPickListGeneration() {
        val results = prefixCabinetMatches(
            cabinetToPages = mapOf(
                "1" to listOf(1),
                "10" to listOf(2),
                "100" to listOf(3),
                "2" to listOf(4)
            ),
            query = "1"
        )

        assertEquals(3, results.size)
        assertEquals(listOf("1", "10", "100"), results.map { it.cabinet })
    }

    @Test
    fun sanitizeVirtualAssemblyData_dropsBaseWhenFaceFrameAndFramelessPresent() {
        val rawSources = mapOf(
            1 to UnifiedVirtualPageSource("job_ff.pdf", 1, cabinet = "1", sourceVariant = "FACE_FRAME"),
            2 to UnifiedVirtualPageSource("job_base.pdf", 1, cabinet = "1", sourceVariant = "BASE"),
            3 to UnifiedVirtualPageSource("job_fl.pdf", 1, cabinet = "1", sourceVariant = "FRAMELESS"),
            4 to UnifiedVirtualPageSource("job_base.pdf", 2, cabinet = "2", sourceVariant = "BASE"),
            5 to UnifiedVirtualPageSource("job_fl.pdf", 2, cabinet = "2", sourceVariant = "FRAMELESS")
        )

        val result = sanitizeVirtualAssemblyData(
            totalVirtualPages = 5,
            defaultPdfFilename = "fallback.pdf",
            sourceByDisplayPage = rawSources,
            cabinetToPages = mapOf(
                "1" to listOf(1, 2, 3),
                "2" to listOf(4, 5)
            )
        )

        assertNotNull(result.mapping)
        assertEquals(3, result.mapping?.totalDisplayPages)
        assertEquals("FACE_FRAME", result.mapping?.sourceByDisplayPage?.get(1)?.sourceVariant)
        assertEquals("FRAMELESS", result.mapping?.sourceByDisplayPage?.get(2)?.sourceVariant)
        assertEquals("FRAMELESS", result.mapping?.sourceByDisplayPage?.get(3)?.sourceVariant)
        assertEquals(listOf(1, 2), result.cabinetToPages["1"])
        assertEquals(listOf(3), result.cabinetToPages["2"])
        assertTrue(result.warningMessage?.contains("ignored", ignoreCase = true) == true)
    }

    @Test
    fun resolveDisplayPageFromSource_returnsNullForUnmappedSourceWithoutFallback() {
        val reverseIndex = mapOf(
            ("ff.pdf".lowercase() to 1) to 1,
            ("fl.pdf".lowercase() to 1) to 2
        )

        val page = resolveDisplayPageFromSource(reverseIndex, "unknown.pdf", 1)

        assertNull(page)
    }

    @Test
    fun defaultNavigatorPrimaryLabel_formatsFullCabinetRanges() {
        val label = defaultNavigatorPrimaryLabel(
            page = 1,
            cabinets = listOf("1", "2", "3", "5", "6", "9", "10", "11", "12"),
            source = null
        )

        assertEquals("Cabinets 1-3, 5, 6, 9-12", label)
    }

    @Test
    fun buildPageToCabinets_sortsNumericCabinetsNaturally() {
        val byPage = buildPageToCabinets(
            mapOf(
                "10" to listOf(1),
                "2" to listOf(1),
                "1" to listOf(1)
            )
        )

        assertEquals(listOf("1", "2", "10"), byPage[1])
    }

    @Test
    fun extractRoomDisplayName_prefersParenthesizedRoomName() {
        val room = extractRoomDisplayName("Room #1 (Kitchen)")
        assertEquals("KITCHEN", room)
    }

    @Test
    fun buildPlanViewLabelsFromPageToRoom_mapsPageBeforeRoomStart() {
        val labels = buildPlanViewLabelsFromPageToRoom(
            mapOf(
                2 to "KITCHEN",
                3 to "KITCHEN",
                9 to "BATH"
            )
        )
        assertEquals("KITCHEN - PLAN VIEW", labels[1])
        assertEquals("BATH - PLAN VIEW", labels[8])
    }

    @Test
    fun findPrefixCabinetMatches_matchesPrefixValuesOnly() {
        val matches = findPrefixCabinetMatches(
            cabinets = listOf("4", "40", "400", "14"),
            query = "4"
        )

        assertEquals(listOf("4", "40", "400"), matches)
    }

    @Test
    fun buildPageToRoomKey_assignsRoomAcrossPlanAndElevationPages() {
        val pageToRoom = buildPageToRoomKey(
            totalPages = 12,
            navigatorPlanViewLabels = mapOf(
                1 to "KITCHEN - PLAN VIEW",
                8 to "BATH - PLAN VIEW"
            )
        )

        assertEquals("KITCHEN", pageToRoom[1])
        assertEquals("KITCHEN", pageToRoom[7])
        assertEquals("BATH", pageToRoom[8])
        assertEquals("BATH", pageToRoom[12])
    }

    @Test
    fun filterNavigatorRowsForSearch_includesMatchedRowsAndPlanViewForRoom() {
        val rows = listOf(
            NavigatorSearchRow(
                page = 1,
                cabinets = emptyList(),
                roomKey = "KITCHEN",
                isPlanView = true
            ),
            NavigatorSearchRow(
                page = 2,
                cabinets = listOf("1", "2", "3", "4", "5"),
                roomKey = "KITCHEN",
                isPlanView = false
            ),
            NavigatorSearchRow(
                page = 3,
                cabinets = listOf("40"),
                roomKey = "KITCHEN",
                isPlanView = false
            ),
            NavigatorSearchRow(
                page = 8,
                cabinets = emptyList(),
                roomKey = "BATH",
                isPlanView = true
            )
        )

        val filtered = filterNavigatorRowsForSearch(rows, "4")

        assertEquals(listOf(1, 2, 3), filtered.map { it.page })
        assertEquals(emptyList<String>(), filtered.first().matchedCabinets)
        assertEquals(listOf("4"), filtered[1].matchedCabinets)
        assertEquals(listOf("40"), filtered[2].matchedCabinets)
    }

    @Test
    fun filterNavigatorRowsForSearch_returnsNoRowsWhenNoMatches() {
        val rows = listOf(
            NavigatorSearchRow(
                page = 1,
                cabinets = emptyList(),
                roomKey = "KITCHEN",
                isPlanView = true
            ),
            NavigatorSearchRow(
                page = 2,
                cabinets = listOf("1", "2", "3"),
                roomKey = "KITCHEN",
                isPlanView = false
            )
        )

        val filtered = filterNavigatorRowsForSearch(rows, "9")

        assertTrue(filtered.isEmpty())
    }

    @Test
    fun resolvePageSource_usesVirtualMappingWhenPresent() {
        val mapping = UnifiedVirtualPageMapping(
            totalDisplayPages = 2,
            defaultPdfFilename = "fallback.pdf",
            sourceByDisplayPage = mapOf(
                1 to UnifiedVirtualPageSource(pdfFilename = "assembly.pdf", page = 5)
            )
        )

        val resolved = resolvePageSource(displayPage = 1, virtualMapping = mapping, defaultPdfFilename = "fallback.pdf")

        assertEquals("assembly.pdf", resolved.pdfFilename)
        assertEquals(5, resolved.sourcePage)
    }

    @Test
    fun resolvePageSource_fallsBackToDefaultFilenameAndDisplayPageWhenNoMapping() {
        val resolved = resolvePageSource(displayPage = 3, virtualMapping = null, defaultPdfFilename = "plans.pdf")

        assertEquals("plans.pdf", resolved.pdfFilename)
        assertEquals(3, resolved.sourcePage)
    }

    @Test
    fun resolvePageSource_fallsBackWhenDisplayPageMissingFromMapping() {
        val mapping = UnifiedVirtualPageMapping(
            totalDisplayPages = 1,
            defaultPdfFilename = "fallback.pdf",
            sourceByDisplayPage = emptyMap()
        )

        val resolved = resolvePageSource(displayPage = 1, virtualMapping = mapping, defaultPdfFilename = "fallback.pdf")

        assertEquals("fallback.pdf", resolved.pdfFilename)
        assertEquals(1, resolved.sourcePage)
    }

    @Test
    fun buildNavigatorRowModels_fallsBackToPlainPageNumberWithNoCabinetsOrMapping() {
        val rows = buildNavigatorRowModels(
            totalPages = 3,
            virtualMapping = null,
            navigatorCabinetToPages = emptyMap(),
            navigatorPlanViewLabels = emptyMap()
        )

        assertEquals(listOf(1, 2, 3), rows.map { it.page })
        assertEquals(listOf("Page 1", "Page 2", "Page 3"), rows.map { it.primaryLabel })
    }

    @Test
    fun buildNavigatorRowModels_usesCabinetLabelsWhenPresent() {
        val rows = buildNavigatorRowModels(
            totalPages = 2,
            virtualMapping = null,
            navigatorCabinetToPages = mapOf("12" to listOf(1)),
            navigatorPlanViewLabels = emptyMap()
        )

        assertEquals("Cabinet 12", rows.first { it.page == 1 }.primaryLabel)
        assertEquals("Page 2", rows.first { it.page == 2 }.primaryLabel)
    }

    @Test
    fun buildNavigatorRowModels_usesPlanViewLabelForPlanPages() {
        val rows = buildNavigatorRowModels(
            totalPages = 2,
            virtualMapping = null,
            navigatorCabinetToPages = emptyMap(),
            navigatorPlanViewLabels = mapOf(1 to "KITCHEN - PLAN VIEW")
        )

        assertEquals("KITCHEN - PLAN VIEW", rows.first { it.page == 1 }.primaryLabel)
        assertTrue(rows.first { it.page == 1 }.isPlanView)
    }
}

