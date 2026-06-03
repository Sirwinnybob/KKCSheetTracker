package com.kkc.sheettracker.ui.specialty

import com.kkc.sheettracker.data.models.HardwoodCutlistIndex
import com.kkc.sheettracker.data.models.HardwoodCutlistRow
import com.kkc.sheettracker.data.models.HardwoodDocType
import com.kkc.sheettracker.data.models.HardwoodDocumentIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpecialtyDoorPanelsScreenLogicTest {
    @Test
    fun parseDoorCutUnitTypeMetadata_rowAndMaterialMetadata_detectsSheets() {
        val rawJson = """
            {
              "documents": [
                {
                  "docType": "DOOR_CUT_LIST",
                  "rows": [
                    { "rowId": "row-a", "material": "Maple", "unitType": "SHEETS" },
                    { "rowId": "row-b", "material": "Oak", "unitType": "LINEAR_FEET" }
                  ],
                  "unitTypeByMaterial": {
                    "Walnut": "SHEETS",
                    "Cherry": "PIECES"
                  }
                }
              ]
            }
        """.trimIndent()

        val parsed = parseDoorCutUnitTypeMetadata(rawJson)

        assertTrue(parsed.hasUnitTypeMetadata)
        assertEquals(setOf("ROW-A"), parsed.sheetRowIds)
        assertEquals(setOf("MAPLE", "WALNUT"), parsed.sheetMaterials)
    }

    @Test
    fun parseDoorCutUnitTypeMetadata_invalidOrMissingMetadata_isSafe() {
        val invalid = parseDoorCutUnitTypeMetadata("{not-json")
        assertFalse(invalid.hasUnitTypeMetadata)
        assertTrue(invalid.sheetRowIds.isEmpty())
        assertTrue(invalid.sheetMaterials.isEmpty())

        val missing = parseDoorCutUnitTypeMetadata("""{"documents":[{"docType":"DOOR_CUT_LIST"}]}""")
        assertFalse(missing.hasUnitTypeMetadata)
    }

    @Test
    fun buildDoorPanelsViewModel_hidesDoorCutOption_whenUnitTypeMetadataMissing() {
        val index = HardwoodCutlistIndex(
            documents = listOf(
                HardwoodDocumentIndex(
                    docType = HardwoodDocType.DOOR_LIST,
                    rows = listOf(HardwoodCutlistRow(rowId = "list-1", description = "Door A"))
                ),
                HardwoodDocumentIndex(
                    docType = HardwoodDocType.DOOR_CUT_LIST,
                    rows = listOf(HardwoodCutlistRow(rowId = "cut-1", material = "Maple", description = "Door B"))
                ),
                HardwoodDocumentIndex(
                    docType = HardwoodDocType.FACE_FRAME_CUT_LIST,
                    rows = listOf(HardwoodCutlistRow(rowId = "ff-1", description = "Ignore me"))
                )
            )
        )

        val model = buildDoorPanelsViewModel(
            hardwoodIndex = index,
            rawCutlistIndexJson = """{"documents":[{"docType":"DOOR_CUT_LIST","rows":[{"rowId":"cut-1"}]}]}"""
        )

        assertEquals(listOf(DoorPanelsDocOption.DOOR_LIST), model.options)
        assertTrue(model.hiddenDoorCutListBecauseMissingUnitType)
        assertEquals(1, model.rowsByOption[DoorPanelsDocOption.DOOR_LIST]?.size)
    }

    @Test
    fun buildDoorPanelsViewModel_filtersDoorCutRowsToSheetsOnly() {
        val doorCutRows = listOf(
            HardwoodCutlistRow(rowId = "cut-1", material = "Maple", description = "Keep by row"),
            HardwoodCutlistRow(rowId = "cut-2", material = "Walnut", description = "Keep by material"),
            HardwoodCutlistRow(rowId = "cut-3", material = "Oak", description = "Drop")
        )
        val index = HardwoodCutlistIndex(
            documents = listOf(
                HardwoodDocumentIndex(docType = HardwoodDocType.DOOR_LIST),
                HardwoodDocumentIndex(docType = HardwoodDocType.DOOR_CUT_LIST, rows = doorCutRows)
            )
        )
        val rawJson = """
            {
              "documents": [
                {
                  "docType": "DOOR_CUT_LIST",
                  "rows": [
                    { "rowId": "cut-1", "material": "Maple", "unitType": "SHEETS" },
                    { "rowId": "cut-3", "material": "Oak", "unitType": "PIECES" }
                  ],
                  "unitTypeByMaterial": {
                    "Walnut": "SHEETS"
                  }
                }
              ]
            }
        """.trimIndent()

        val model = buildDoorPanelsViewModel(
            hardwoodIndex = index,
            rawCutlistIndexJson = rawJson
        )

        assertEquals(
            listOf(DoorPanelsDocOption.DOOR_LIST, DoorPanelsDocOption.DOOR_CUT_LIST),
            model.options
        )
        assertFalse(model.hiddenDoorCutListBecauseMissingUnitType)
        assertEquals(
            listOf("cut-1", "cut-2"),
            model.rowsByOption[DoorPanelsDocOption.DOOR_CUT_LIST].orEmpty().map { it.rowId }
        )
    }

    @Test
    fun buildDoorPanelsViewModel_filtersDoorCutRows_withMixedCaseAndPaddedRowIds() {
        val doorCutRows = listOf(
            HardwoodCutlistRow(rowId = "  row-a  ", material = "Maple", description = "Keep by rowId"),
            HardwoodCutlistRow(rowId = "row-b", material = "  walnut  ", description = "Keep by material"),
            HardwoodCutlistRow(rowId = "row-c", material = "Cherry", description = "Drop")
        )
        val index = HardwoodCutlistIndex(
            documents = listOf(
                HardwoodDocumentIndex(docType = HardwoodDocType.DOOR_CUT_LIST, rows = doorCutRows)
            )
        )
        val rawJson = """
            {
              "documents": [
                {
                  "docType": "DOOR_CUT_LIST",
                  "rows": [
                    { "rowId": " Row-A ", "unitType": "sheets" },
                    { "rowId": "row-c", "unitType": "PIECES" }
                  ],
                  "unitTypeByMaterial": {
                    "   WALNUT   ": "SHEETS"
                  }
                }
              ]
            }
        """.trimIndent()

        val model = buildDoorPanelsViewModel(index, rawJson)

        assertEquals(
            listOf("  row-a  ", "row-b"),
            model.rowsByOption[DoorPanelsDocOption.DOOR_CUT_LIST].orEmpty().map { it.rowId }
        )
    }

    @Test
    fun parseDoorCutUnitTypeMetadata_ignoresBlankMaterialKeysInUnitTypeByMaterial() {
        val rawJson = """
            {
              "documents": [
                {
                  "docType": "DOOR_CUT_LIST",
                  "unitTypeByMaterial": {
                    "   ": "SHEETS",
                    "Maple": "SHEETS"
                  }
                }
              ]
            }
        """.trimIndent()

        val parsed = parseDoorCutUnitTypeMetadata(rawJson)

        assertTrue(parsed.hasUnitTypeMetadata)
        assertEquals(setOf("MAPLE"), parsed.sheetMaterials)
    }
}
