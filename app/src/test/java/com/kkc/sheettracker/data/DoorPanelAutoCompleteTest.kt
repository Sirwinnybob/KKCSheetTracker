package com.kkc.sheettracker.data

import com.kkc.sheettracker.data.models.HardwoodCutlistRow
import com.kkc.sheettracker.data.models.SpecialtyItem
import org.junit.Assert.assertEquals
import org.junit.Test

class DoorPanelAutoCompleteTest {

    @Test
    fun matchesSameMaterialAndExcludesOthers() {
        val item = SpecialtyItem(
            automationKey = "door_panels_auto|oak",
            material = "White Oak"
        )
        val rows = listOf(
            HardwoodCutlistRow(rowId = "row-1", qty = 2, material = "white oak"),
            HardwoodCutlistRow(rowId = "row-2", qty = 5, material = "Maple"),
            HardwoodCutlistRow(rowId = "row-3", qty = 1, material = " WHITE OAK ")
        )

        assertEquals(
            listOf(
                DoorPanelTarget(rowId = "row-1", qty = 2),
                DoorPanelTarget(rowId = "row-3", qty = 1)
            ),
            matchingDoorPanelRows(item, rows, MaterialMappings.of(emptyMap()))
        )
    }

    @Test
    fun matchesRealItemMaterialToSanitizedRowMaterial() {
        val mappings = MaterialMappings.of(
            mapOf("1/4 2s Hickory Rustic" to "1_4 2s Rustic Hickory")
        )
        val item = SpecialtyItem(
            automationKey = "door_panels_auto|hickory",
            material = "1/4 2s Hickory Rustic"
        )
        val rows = listOf(
            HardwoodCutlistRow(rowId = "hickory", qty = 4, material = "1_4 2s Rustic Hickory"),
            HardwoodCutlistRow(rowId = "maple", qty = 3, material = "1_4 2s Maple")
        )

        assertEquals(
            listOf(DoorPanelTarget(rowId = "hickory", qty = 4)),
            matchingDoorPanelRows(item, rows, mappings)
        )
    }

    @Test
    fun nonAutoOrNoAutomationKeyMatchNothing() {
        val rows = listOf(HardwoodCutlistRow(rowId = "row-1", qty = 2, material = "White Oak"))

        assertEquals(
            emptyList<DoorPanelTarget>(),
            matchingDoorPanelRows(
                SpecialtyItem(automationKey = "other_auto|oak", material = "White Oak"),
                rows,
                MaterialMappings.of(emptyMap())
            )
        )
        assertEquals(
            emptyList<DoorPanelTarget>(),
            matchingDoorPanelRows(
                SpecialtyItem(automationKey = null, material = "White Oak"),
                rows,
                MaterialMappings.of(emptyMap())
            )
        )
    }

    @Test
    fun itemWithoutMaterialMatchesNothing() {
        val rows = listOf(HardwoodCutlistRow(rowId = "row-1", qty = 2, material = "White Oak"))

        assertEquals(
            emptyList<DoorPanelTarget>(),
            matchingDoorPanelRows(
                SpecialtyItem(automationKey = "door_panels_auto|oak", material = null),
                rows,
                MaterialMappings.of(emptyMap())
            )
        )
        assertEquals(
            emptyList<DoorPanelTarget>(),
            matchingDoorPanelRows(
                SpecialtyItem(automationKey = "door_panels_auto|oak", material = "   "),
                rows,
                MaterialMappings.of(emptyMap())
            )
        )
    }
}
