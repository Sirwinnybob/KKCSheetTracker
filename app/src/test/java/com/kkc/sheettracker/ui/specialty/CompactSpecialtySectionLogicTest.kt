package com.kkc.sheettracker.ui.specialty

import com.kkc.sheettracker.data.models.SpecialtyCompletionState
import com.kkc.sheettracker.data.models.SpecialtyItem
import com.kkc.sheettracker.data.models.SpecialtyResolvedItem
import com.kkc.sheettracker.data.models.SpecialtyStation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompactSpecialtySectionLogicTest {
    @Test
    fun specialtyPriorityStationsForMode_mapsStationsBySurface() {
        assertEquals(setOf(SpecialtyStation.CNC), specialtyPriorityStationsForMode(SpecialtySurfaceMode.CNC))
        assertEquals(
            setOf(SpecialtyStation.SAW, SpecialtyStation.EDGE_BANDER),
            specialtyPriorityStationsForMode(SpecialtySurfaceMode.HARDWOODS)
        )
        assertEquals(setOf(SpecialtyStation.ASSEMBLY), specialtyPriorityStationsForMode(SpecialtySurfaceMode.ASSEMBLY))
    }

    @Test
    fun buildSpecialtySectionRows_hardwoodsMode_relevantItemsFloatToTopAndAllRemainVisible() {
        val items = listOf(
            resolvedItem(id = "1", name = "Assembly Only", stations = listOf(SpecialtyStation.ASSEMBLY)),
            resolvedItem(id = "2", name = "Saw Item", stations = listOf(SpecialtyStation.SAW)),
            resolvedItem(id = "3", name = "Cnc Item", stations = listOf(SpecialtyStation.CNC)),
            resolvedItem(id = "4", name = "Edge Bander Item", stations = listOf(SpecialtyStation.EDGE_BANDER))
        )

        val rows = buildSpecialtySectionRows(items, SpecialtySurfaceMode.HARDWOODS)

        assertEquals(listOf("2", "4", "1", "3"), rows.map { it.resolved.item.id })
        assertEquals(4, rows.size)
        assertTrue(rows[0].isRelevantToMode)
        assertTrue(rows[1].isRelevantToMode)
        assertFalse(rows[2].isRelevantToMode)
        assertFalse(rows[3].isRelevantToMode)
    }

    @Test
    fun buildSpecialtySectionRows_assemblyMode_keepsSourceOrderWithinRelevantAndNonRelevantGroups() {
        val items = listOf(
            resolvedItem(id = "1", name = "A", stations = listOf(SpecialtyStation.CNC)),
            resolvedItem(id = "2", name = "B", stations = listOf(SpecialtyStation.ASSEMBLY)),
            resolvedItem(id = "3", name = "C", stations = listOf(SpecialtyStation.ASSEMBLY, SpecialtyStation.SAW)),
            resolvedItem(id = "4", name = "D", stations = emptyList())
        )

        val rows = buildSpecialtySectionRows(items, SpecialtySurfaceMode.ASSEMBLY)

        assertEquals(listOf("2", "3", "1", "4"), rows.map { it.resolved.item.id })
    }

    private fun resolvedItem(id: String, name: String, stations: List<SpecialtyStation>): SpecialtyResolvedItem {
        return SpecialtyResolvedItem(
            item = SpecialtyItem(id = id, name = name, stations = stations),
            completionByKey = mapOf("ITEM" to SpecialtyCompletionState(completed = false)),
            isComplete = false
        )
    }
}
