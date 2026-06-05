package com.kkc.sheettracker.ui.specialty

import com.kkc.sheettracker.data.models.SpecialtyCompletionState
import com.kkc.sheettracker.data.models.SpecialtyItem
import com.kkc.sheettracker.data.models.SpecialtyItemCategory
import com.kkc.sheettracker.data.models.SpecialtyResolvedItem
import com.kkc.sheettracker.data.models.SpecialtyStation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
