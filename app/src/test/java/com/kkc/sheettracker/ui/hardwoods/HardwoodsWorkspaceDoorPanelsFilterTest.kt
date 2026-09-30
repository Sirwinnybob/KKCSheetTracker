package com.kkc.sheettracker.ui.hardwoods

import com.kkc.sheettracker.data.models.HardwoodCutlistRow
import com.kkc.sheettracker.data.models.HardwoodDocType
import org.junit.Assert.assertEquals
import org.junit.Test

class HardwoodsWorkspaceDoorPanelsFilterTest {
    private val rows = listOf(
        HardwoodCutlistRow(rowId = "ROW-1", material = "Maple"),
        HardwoodCutlistRow(rowId = "ROW-2", material = "Birch")
    )

    private val sheetMetadataJson = """
        {
          "documents": [
            {
              "docType": "DOOR_CUT_LIST",
              "rows": [
                {"rowId":"row-1","unitType":"SHEETS"},
                {"rowId":"row-2","unitType":"FRAME"}
              ]
            }
          ]
        }
    """.trimIndent()

    @Test
    fun plywoodFilter_withUnitTypeMetadata_filtersToSheetRows() {
        val filtered = applyDoorCutMaterialFilter(
            rows = rows,
            selectedDocType = HardwoodDocType.DOOR_CUT_LIST,
            filter = DoorCutMaterialFilter.Plywood,
            rawCutlistIndexJson = sheetMetadataJson
        )

        assertEquals(listOf("ROW-1"), filtered.map { it.rowId })
    }

    @Test
    fun hardwoodFilter_withUnitTypeMetadata_excludesSheetRows() {
        val filtered = applyDoorCutMaterialFilter(
            rows = rows,
            selectedDocType = HardwoodDocType.DOOR_CUT_LIST,
            filter = DoorCutMaterialFilter.Hardwood,
            rawCutlistIndexJson = sheetMetadataJson
        )

        assertEquals(listOf("ROW-2"), filtered.map { it.rowId })
    }

    @Test
    fun eitherFilter_withoutUnitTypeMetadata_fallsBackToAllRows() {
        val rawJson = """{"documents":[{"docType":"DOOR_CUT_LIST"}]}"""

        DoorCutMaterialFilter.entries.forEach { filter ->
            val filtered = applyDoorCutMaterialFilter(
                rows = rows,
                selectedDocType = HardwoodDocType.DOOR_CUT_LIST,
                filter = filter,
                rawCutlistIndexJson = rawJson
            )
            assertEquals(rows, filtered)
        }
    }

    @Test
    fun otherDocTypes_ignoreFilter() {
        DoorCutMaterialFilter.entries.forEach { filter ->
            val filtered = applyDoorCutMaterialFilter(
                rows = rows,
                selectedDocType = HardwoodDocType.FACE_FRAME_CUT_LIST,
                filter = filter,
                rawCutlistIndexJson = sheetMetadataJson
            )
            assertEquals(rows, filtered)
        }
    }
}
