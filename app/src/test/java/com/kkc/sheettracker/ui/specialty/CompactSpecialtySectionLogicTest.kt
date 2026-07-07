package com.kkc.sheettracker.ui.specialty

import com.kkc.sheettracker.data.SpecialtyProgressStore
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

class CompactSpecialtySectionLogicTest {
    @Test
    fun isItemRelevantToMode_correctlyClassifiesRelevance() {
        // CNC Mode: only CNC is relevant
        assertTrue(isItemRelevantToMode(resolvedItem("1", listOf(SpecialtyStation.CNC)), SpecialtySurfaceMode.CNC))
        assertFalse(isItemRelevantToMode(resolvedItem("2", listOf(SpecialtyStation.DELIVERY)), SpecialtySurfaceMode.CNC))
        assertFalse(isItemRelevantToMode(resolvedItem("3", listOf(SpecialtyStation.HARDWOODS)), SpecialtySurfaceMode.CNC))

        // HARDWOODS Mode: HARDWOODS and DELIVERY are relevant
        assertTrue(isItemRelevantToMode(resolvedItem("4", listOf(SpecialtyStation.HARDWOODS)), SpecialtySurfaceMode.HARDWOODS))
        assertTrue(isItemRelevantToMode(resolvedItem("5", listOf(SpecialtyStation.DELIVERY)), SpecialtySurfaceMode.HARDWOODS))
        assertFalse(isItemRelevantToMode(resolvedItem("6", listOf(SpecialtyStation.CNC)), SpecialtySurfaceMode.HARDWOODS))
        assertFalse(isItemRelevantToMode(resolvedItem("7", listOf(SpecialtyStation.SAW)), SpecialtySurfaceMode.HARDWOODS))

        // ASSEMBLY Mode: ASSEMBLY, DELIVERY, and TO_ORDER category are relevant
        assertTrue(isItemRelevantToMode(resolvedItem("8", listOf(SpecialtyStation.ASSEMBLY)), SpecialtySurfaceMode.ASSEMBLY))
        assertTrue(isItemRelevantToMode(resolvedItem("9", listOf(SpecialtyStation.DELIVERY)), SpecialtySurfaceMode.ASSEMBLY))
        assertTrue(isItemRelevantToMode(
            SpecialtyResolvedItem(
                item = SpecialtyItem(id = "10", name = "To Order Item", stations = emptyList(), category = SpecialtyItemCategory.TO_ORDER),
                completionByKey = mapOf("ITEM" to SpecialtyCompletionState(completed = false)),
                isComplete = false
            ),
            SpecialtySurfaceMode.ASSEMBLY
        ))
        assertFalse(isItemRelevantToMode(resolvedItem("11", listOf(SpecialtyStation.CNC)), SpecialtySurfaceMode.ASSEMBLY))

        // SPECIALTY Mode: everything EXCEPT DELIVERY is relevant
        assertTrue(isItemRelevantToMode(resolvedItem("12", listOf(SpecialtyStation.CNC)), SpecialtySurfaceMode.SPECIALTY))
        assertTrue(isItemRelevantToMode(resolvedItem("13", listOf(SpecialtyStation.ASSEMBLY)), SpecialtySurfaceMode.SPECIALTY))
        assertFalse(isItemRelevantToMode(resolvedItem("14", listOf(SpecialtyStation.DELIVERY)), SpecialtySurfaceMode.SPECIALTY))
    }

    @Test
    fun buildSpecialtySectionRows_filtersNonRelevantItemsAndRetainsOrder() {
        val items = listOf(
            resolvedItem(id = "1", stations = listOf(SpecialtyStation.DELIVERY)),
            resolvedItem(id = "2", stations = listOf(SpecialtyStation.CNC)),
            resolvedItem(id = "3", stations = listOf(SpecialtyStation.ASSEMBLY)),
            resolvedItem(id = "4", stations = listOf(SpecialtyStation.HARDWOODS))
        )

        // CNC Mode: only CNC item (2) relevant
        val cncRows = buildSpecialtySectionRows(items, SpecialtySurfaceMode.CNC)
        assertEquals(listOf("2"), cncRows.map { it.resolved.item.id })

        // HARDWOODS Mode: DELIVERY (1) and HARDWOODS (4) relevant
        val hardwoodsRows = buildSpecialtySectionRows(items, SpecialtySurfaceMode.HARDWOODS)
        assertEquals(listOf("1", "4"), hardwoodsRows.map { it.resolved.item.id })

        // ASSEMBLY Mode: DELIVERY (1) and ASSEMBLY (3) relevant
        val assemblyRows = buildSpecialtySectionRows(items, SpecialtySurfaceMode.ASSEMBLY)
        assertEquals(listOf("1", "3"), assemblyRows.map { it.resolved.item.id })

        // SPECIALTY Mode: CNC (2), ASSEMBLY (3), and HARDWOODS (4) relevant (not DELIVERY)
        val specialtyRows = buildSpecialtySectionRows(items, SpecialtySurfaceMode.SPECIALTY)
        assertEquals(listOf("2", "3", "4"), specialtyRows.map { it.resolved.item.id })
    }

    @Test
    fun compactCompletionKeyForMode_singleStationItemAlwaysUsesTheSharedItemKey() {
        // Not a multi-station CUSTOM item (only one station) -> always the shared ITEM key,
        // regardless of mode.
        val item = SpecialtyItem(id = "1", name = "Single", stations = listOf(SpecialtyStation.CNC), category = SpecialtyItemCategory.CUSTOM)
        assertEquals(SpecialtyProgressStore.ITEM_COMPLETION_KEY, compactCompletionKeyForMode(item, SpecialtySurfaceMode.CNC))
        assertEquals(SpecialtyProgressStore.ITEM_COMPLETION_KEY, compactCompletionKeyForMode(item, SpecialtySurfaceMode.SPECIALTY))
    }

    @Test
    fun compactCompletionKeyForMode_toOrderItemAlwaysUsesTheSharedItemKey() {
        // TO_ORDER items never split by station even with multiple stations listed.
        val item = SpecialtyItem(
            id = "2",
            name = "Ordered",
            stations = listOf(SpecialtyStation.CNC, SpecialtyStation.ASSEMBLY),
            category = SpecialtyItemCategory.TO_ORDER
        )
        assertEquals(SpecialtyProgressStore.ITEM_COMPLETION_KEY, compactCompletionKeyForMode(item, SpecialtySurfaceMode.ASSEMBLY))
    }

    @Test
    fun compactCompletionKeyForMode_multiStationCustomItemResolvesToTheSingleMatchingStationKey() {
        // CUSTOM item split across CNC and ASSEMBLY: in CNC mode, only the CNC key is relevant.
        val item = SpecialtyItem(
            id = "3",
            name = "Split",
            stations = listOf(SpecialtyStation.CNC, SpecialtyStation.ASSEMBLY),
            category = SpecialtyItemCategory.CUSTOM
        )
        assertEquals(SpecialtyStation.CNC.name, compactCompletionKeyForMode(item, SpecialtySurfaceMode.CNC))
        assertEquals(SpecialtyStation.ASSEMBLY.name, compactCompletionKeyForMode(item, SpecialtySurfaceMode.ASSEMBLY))
    }

    @Test
    fun compactCompletionKeyForMode_returnsNullWhenModeMatchesMoreThanOneStationKey() {
        // CUSTOM item split across CNC and ASSEMBLY: SPECIALTY mode matches both (neither is
        // DELIVERY) -> ambiguous, must not resolve to a single writable key.
        val item = SpecialtyItem(
            id = "4",
            name = "Ambiguous",
            stations = listOf(SpecialtyStation.CNC, SpecialtyStation.ASSEMBLY),
            category = SpecialtyItemCategory.CUSTOM
        )
        assertNull(compactCompletionKeyForMode(item, SpecialtySurfaceMode.SPECIALTY))
    }

    @Test
    fun compactCompletionKeyForMode_returnsNullWhenModeMatchesNoStationKey() {
        val item = SpecialtyItem(
            id = "5",
            name = "NoMatch",
            stations = listOf(SpecialtyStation.CNC, SpecialtyStation.HARDWOODS),
            category = SpecialtyItemCategory.CUSTOM
        )
        assertNull(compactCompletionKeyForMode(item, SpecialtySurfaceMode.ASSEMBLY))
    }

    private fun resolvedItem(
        id: String,
        stations: List<SpecialtyStation>,
        category: SpecialtyItemCategory = SpecialtyItemCategory.CUSTOM
    ): SpecialtyResolvedItem {
        return SpecialtyResolvedItem(
            item = SpecialtyItem(id = id, name = "Item $id", stations = stations, category = category),
            completionByKey = mapOf("ITEM" to SpecialtyCompletionState(completed = false)),
            isComplete = false
        )
    }
}
