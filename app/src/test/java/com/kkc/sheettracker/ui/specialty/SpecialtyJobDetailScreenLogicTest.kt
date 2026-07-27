package com.kkc.sheettracker.ui.specialty

import com.kkc.sheettracker.data.SPECIALTY_VIEWER_SECTION_ID_OTHER
import com.kkc.sheettracker.data.SpecialtyProgressStore
import com.kkc.sheettracker.data.models.HardwoodCutlistIndex
import com.kkc.sheettracker.data.models.HardwoodCutlistRow
import com.kkc.sheettracker.data.models.HardwoodDocType
import com.kkc.sheettracker.data.models.HardwoodDocumentIndex
import com.kkc.sheettracker.data.models.AdminBoardStockItem
import com.kkc.sheettracker.data.models.SpecialtyCompletionState
import com.kkc.sheettracker.data.models.SpecialtyItem
import com.kkc.sheettracker.data.models.SpecialtyItemCategory
import com.kkc.sheettracker.data.models.SpecialtyResolvedItem
import com.kkc.sheettracker.data.models.SpecialtyStation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpecialtyJobDetailScreenLogicTest {
    @Test
    fun checklistTogglesForItem_customMultiStation_createsOneStationLabeledTogglePerStation() {
        val resolved = SpecialtyResolvedItem(
            item = SpecialtyItem(
                id = "custom-1",
                name = "Custom Item",
                category = SpecialtyItemCategory.CUSTOM,
                stations = listOf(SpecialtyStation.CNC, SpecialtyStation.EDGE_BANDER, SpecialtyStation.ASSEMBLY)
            ),
            completionByKey = mapOf(
                SpecialtyStation.CNC.name to SpecialtyCompletionState(completed = true),
                SpecialtyStation.EDGE_BANDER.name to SpecialtyCompletionState(completed = false),
                SpecialtyStation.ASSEMBLY.name to SpecialtyCompletionState(completed = false)
            )
        )

        val toggles = checklistTogglesForItem(resolved, completionOverrides = emptyMap())

        assertEquals(3, toggles.size)
        assertEquals(listOf("CNC", "EDGE_BANDER", "ASSEMBLY"), toggles.map { it.completionKey })
        assertEquals(listOf("CNC", "EDGE BANDER", "ASSEMBLY"), toggles.map { it.label })
    }

    @Test
    fun checklistTogglesForItem_toOrder_createsSingleItemCompletionToggle() {
        val resolved = SpecialtyResolvedItem(
            item = SpecialtyItem(
                id = "order-1",
                name = "Ordered Part",
                category = SpecialtyItemCategory.TO_ORDER,
                stations = listOf(SpecialtyStation.CNC, SpecialtyStation.SAW)
            ),
            completionByKey = mapOf(
                SpecialtyProgressStore.ITEM_COMPLETION_KEY to SpecialtyCompletionState(completed = true)
            )
        )

        val toggles = checklistTogglesForItem(resolved, completionOverrides = emptyMap())

        assertEquals(1, toggles.size)
        assertEquals(SpecialtyProgressStore.ITEM_COMPLETION_KEY, toggles.first().completionKey)
        assertNull(toggles.first().label)
        assertTrue(toggles.first().checked)
    }

    @Test
    fun isChecklistItemComplete_customMultiStation_requiresAllStationsChecked() {
        val resolved = SpecialtyResolvedItem(
            item = SpecialtyItem(
                id = "custom-2",
                name = "Custom Item",
                category = SpecialtyItemCategory.CUSTOM,
                stations = listOf(SpecialtyStation.CNC, SpecialtyStation.SAW)
            ),
            completionByKey = mapOf(
                SpecialtyStation.CNC.name to SpecialtyCompletionState(completed = true),
                SpecialtyStation.SAW.name to SpecialtyCompletionState(completed = false)
            )
        )

        assertFalse(isChecklistItemComplete(resolved, completionOverrides = emptyMap()))

        val overrides = mapOf("custom-2::SAW" to true)
        assertTrue(isChecklistItemComplete(resolved, completionOverrides = overrides))
    }

    @Test
    fun inFlightUpdates_concurrentToggles_remainDisabledUntilEachWriteCompletes() {
        val inFlight = mutableMapOf<String, Boolean>()
        val cncControl = "custom-2::CNC"
        val sawControl = "custom-2::SAW"

        startInFlightUpdate(inFlight, cncControl)
        startInFlightUpdate(inFlight, sawControl)
        assertFalse(isToggleEnabled(cncControl, inFlight))
        assertFalse(isToggleEnabled(sawControl, inFlight))

        finishInFlightUpdate(inFlight, cncControl)
        assertTrue(isToggleEnabled(cncControl, inFlight))
        assertFalse(isToggleEnabled(sawControl, inFlight))

        finishInFlightUpdate(inFlight, sawControl)
        assertTrue(isToggleEnabled(cncControl, inFlight))
        assertTrue(isToggleEnabled(sawControl, inFlight))
    }

    @Test
    fun buildSpecialtyDetailSections_usesSavedStationOrder_andKeepsOtherLast() {
        val sections = buildSpecialtyDetailSections(
            resolvedItems = listOf(
                resolvedItem("other", emptyList()),
                resolvedItem("saw", listOf(SpecialtyStation.SAW)),
                resolvedItem("cnc", listOf(SpecialtyStation.CNC)),
                resolvedItem("multi", listOf(SpecialtyStation.CNC, SpecialtyStation.SAW)),
            ),
            stationOrder = listOf(SpecialtyStation.SAW, SpecialtyStation.CNC)
        )

        assertEquals(
            listOf(SpecialtyStation.SAW.name, SpecialtyStation.CNC.name, SPECIALTY_VIEWER_SECTION_ID_OTHER),
            sections.map { it.id }
        )
        assertEquals(listOf("saw", "multi"), sections[0].items.map { it.item.id })
        assertEquals(listOf("cnc", "multi"), sections[1].items.map { it.item.id })
        assertEquals(listOf("other"), sections[2].items.map { it.item.id })
    }

    @Test
    fun specialtySheetRipLazyRowEntries_keepEveryLargeGroupRowAsItsOwnListEntry() {
        val sheetRips = (1..95).map { index ->
            AdminBoardStockItem(
                id = "sheet-rip-$index",
                material = "Material $index",
                name = "Rip $index",
                feet = 10.0,
                mode = "sheet"
            )
        }

        val entries = specialtySheetRipLazyRowEntries(sheetRips)

        assertEquals(95, entries.size)
        assertEquals(95, entries.map { it.key }.toSet().size)
        assertEquals(sheetRips, entries.map { it.item })
    }

    @Test
    fun specialtySheetRipItems_includesOnlySheetModeCrown() {
        val sheetCrown = AdminBoardStockItem("sheet", "Maple", "Crown", 18.0, mode = "sheet", type = "crown")
        val boardCrown = AdminBoardStockItem("board", "Maple", "Crown", 18.0, mode = "bd_ft", type = "crown")

        assertEquals(listOf(sheetCrown), specialtySheetRipItems(listOf(sheetCrown, boardCrown)))
    }

    @Test
    fun specialtyChecklistLazyRowEntries_keepEveryLargeStationRowAsItsOwnListEntry() {
        val checklistItems = (1..95).map { index ->
            resolvedItem("cnc-$index", listOf(SpecialtyStation.CNC))
        }

        val entries = specialtyChecklistLazyRowEntries(
            sectionId = SpecialtyStation.CNC.name,
            items = checklistItems
        )

        assertEquals(95, entries.size)
        assertEquals(95, entries.map { it.key }.toSet().size)
        assertEquals(checklistItems, entries.map { it.item })
    }

    @Test
    fun toggleSpecialtySection_onlyAffectsCurrentExpandedSet() {
        val initial = linkedSetOf(SpecialtyStation.CNC.name, SpecialtyStation.SAW.name)

        val collapsed = toggleSpecialtySection(initial, SpecialtyStation.SAW.name)
        assertEquals(setOf(SpecialtyStation.CNC.name), collapsed)

        val expanded = toggleSpecialtySection(collapsed, SpecialtyStation.ASSEMBLY.name)
        assertEquals(
            linkedSetOf(SpecialtyStation.CNC.name, SpecialtyStation.ASSEMBLY.name),
            expanded
        )
    }

    @Test
    fun orderSpecialtyStations_appliesSavedChipOrder_andDeduplicates() {
        val ordered = orderSpecialtyStations(
            stations = listOf(
                SpecialtyStation.CNC,
                SpecialtyStation.SAW,
                SpecialtyStation.CNC,
                SpecialtyStation.ASSEMBLY
            ),
            stationOrder = listOf(SpecialtyStation.SAW, SpecialtyStation.ASSEMBLY)
        )

        assertEquals(
            listOf(SpecialtyStation.SAW, SpecialtyStation.ASSEMBLY, SpecialtyStation.CNC),
            ordered
        )
    }

    @Test
    fun hasClosetRodCutList_requiresClosetRodRows() {
        val index = HardwoodCutlistIndex(
            documents = listOf(
                HardwoodDocumentIndex(
                    docType = HardwoodDocType.DOOR_CUT_LIST,
                    rows = listOf(HardwoodCutlistRow(rowId = "door-1"))
                ),
                HardwoodDocumentIndex(
                    docType = HardwoodDocType.CLOSET_ROD_CUT_LIST,
                    rows = listOf(HardwoodCutlistRow(rowId = "rod-1", length = "36", unitType = "PER_FT"))
                )
            )
        )

        assertTrue(hasClosetRodCutList(index))
        assertFalse(
            hasClosetRodCutList(
                HardwoodCutlistIndex(
                    documents = listOf(
                        HardwoodDocumentIndex(docType = HardwoodDocType.CLOSET_ROD_CUT_LIST)
                    )
                )
            )
        )
        assertFalse(hasClosetRodCutList(null))
    }

    private fun resolvedItem(
        id: String,
        stations: List<SpecialtyStation>,
        category: SpecialtyItemCategory = SpecialtyItemCategory.CUSTOM
    ): SpecialtyResolvedItem {
        return SpecialtyResolvedItem(
            item = SpecialtyItem(
                id = id,
                name = "Item $id",
                category = category,
                stations = stations
            ),
            completionByKey = mapOf(
                SpecialtyProgressStore.ITEM_COMPLETION_KEY to SpecialtyCompletionState(completed = false)
            ),
            isComplete = false
        )
    }
}
