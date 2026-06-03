package com.kkc.sheettracker.ui.hardwoods

import com.kkc.sheettracker.data.models.HardwoodCutlistRow
import com.kkc.sheettracker.data.models.HardwoodDocType
import org.junit.Assert.assertEquals
import org.junit.Test

class HardwoodsWorkspaceDoorPanelsFilterTest {
    @Test
    fun enabledDoorPanelsFilter_withUnitTypeMetadata_filtersToSheetRows() {
        val rows = listOf(
            HardwoodCutlistRow(rowId = "ROW-1", material = "Maple"),
            HardwoodCutlistRow(rowId = "ROW-2", material = "Birch")
        )
        val rawJson = """
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

        val filtered = applyDoorPanelsSheetFilter(
            rows = rows,
            selectedDocType = HardwoodDocType.DOOR_CUT_LIST,
            enabled = true,
            rawCutlistIndexJson = rawJson
        )

        assertEquals(listOf("ROW-1"), filtered.map { it.rowId })
    }

    @Test
    fun enabledDoorPanelsFilter_withoutUnitTypeMetadata_fallsBackToAllRows() {
        val rows = listOf(
            HardwoodCutlistRow(rowId = "ROW-1", material = "Maple"),
            HardwoodCutlistRow(rowId = "ROW-2", material = "Birch")
        )
        val rawJson = """{"documents":[{"docType":"DOOR_CUT_LIST"}]}"""

        val filtered = applyDoorPanelsSheetFilter(
            rows = rows,
            selectedDocType = HardwoodDocType.DOOR_CUT_LIST,
            enabled = true,
            rawCutlistIndexJson = rawJson
        )

        assertEquals(rows, filtered)
    }

    @Test
    fun disabledDoorPanelsFilter_returnsOriginalRows() {
        val rows = listOf(
            HardwoodCutlistRow(rowId = "ROW-1", material = "Maple"),
            HardwoodCutlistRow(rowId = "ROW-2", material = "Birch")
        )
        val rawJson = """
            {
              "documents": [
                {
                  "docType": "DOOR_CUT_LIST",
                  "rows": [{"rowId":"row-1","unitType":"SHEETS"}]
                }
              ]
            }
        """.trimIndent()

        val filtered = applyDoorPanelsSheetFilter(
            rows = rows,
            selectedDocType = HardwoodDocType.DOOR_CUT_LIST,
            enabled = false,
            rawCutlistIndexJson = rawJson
        )

        assertEquals(rows, filtered)
    }
}
