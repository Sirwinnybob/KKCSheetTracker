package com.kkc.sheettracker.data

import com.kkc.sheettracker.data.models.HardwoodDocType
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DoorPanelAutoCompleteWiringTest {

    @Test
    fun setItemCompletion_autoDoorPanelItemUpdatesMatchingDoorCutSheetRows() = runBlocking {
        val baseDir = Files.createTempDirectory("door-panel-auto-complete-wiring-test").toFile()
        val jobFolderName = "1234 - Door Panel Test"
        writeMaterialMappings(
            baseDir = baseDir,
            body = """{"1/4 2s Hickory Rustic":"1_4 2s Rustic Hickory"}"""
        )
        writeCutlistIndex(baseDir, jobFolderName)
        writeSpecialtyItems(baseDir, jobFolderName)

        val specialtyProgressStore = SpecialtyProgressStore(baseDir = baseDir, tabletId = "tablet-local")
        val hardwoodsProgressStore = HardwoodsProgressStore(baseDir = baseDir, tabletId = "tablet-local")
        val store = SpecialtyStateStore(
            specialtyScanCoordinator = SpecialtyScanCoordinator(
                SpecialtyRepository(baseDir = baseDir, progressStore = specialtyProgressStore)
            ),
            specialtyProgressStore = specialtyProgressStore,
            hardwoodsProgressStore = hardwoodsProgressStore,
            sheetRipProgressStore = SheetRipProgressStore(baseDir = baseDir),
            tabletItemsStore = TabletSpecialtyItemsStore(baseDir, "test-tablet"),
            baseDir = baseDir
        )

        store.setItemCompletion(jobFolderName, "auto-door", true)
        hardwoodsProgressStore.awaitPendingWrites()

        assertEquals(3, doneCount(hardwoodsProgressStore, jobFolderName, "r1"))
        assertEquals(1, doneCount(hardwoodsProgressStore, jobFolderName, "r2"))
        assertEquals(0, doneCount(hardwoodsProgressStore, jobFolderName, "rail"))

        store.setItemCompletion(jobFolderName, "auto-door", false)
        hardwoodsProgressStore.awaitPendingWrites()

        assertEquals(0, doneCount(hardwoodsProgressStore, jobFolderName, "r1"))
        assertEquals(0, doneCount(hardwoodsProgressStore, jobFolderName, "r2"))
    }

    @Test
    fun setItemCompletion_malformedDoorCutIndexStillCompletesSpecialtyItem() = runBlocking {
        val baseDir = Files.createTempDirectory("door-panel-auto-complete-bad-index-test").toFile()
        val jobFolderName = "1234 - Door Panel Test"
        writeRawCutlistIndex(baseDir, jobFolderName, """{"documents": null}""")
        writeSpecialtyItems(baseDir, jobFolderName)

        val specialtyProgressStore = SpecialtyProgressStore(baseDir = baseDir, tabletId = "tablet-local")
        val store = SpecialtyStateStore(
            specialtyScanCoordinator = SpecialtyScanCoordinator(
                SpecialtyRepository(baseDir = baseDir, progressStore = specialtyProgressStore)
            ),
            specialtyProgressStore = specialtyProgressStore,
            hardwoodsProgressStore = HardwoodsProgressStore(baseDir = baseDir, tabletId = "tablet-local"),
            sheetRipProgressStore = SheetRipProgressStore(baseDir = baseDir),
            tabletItemsStore = TabletSpecialtyItemsStore(baseDir, "test-tablet"),
            baseDir = baseDir
        )

        store.setItemCompletion(jobFolderName, "auto-door", true)

        assertTrue(specialtyProgressStore.loadResolvedItems(jobFolderName).first().isComplete)
    }

    @Test
    fun setItemCompletionKey_sawStepOnMultiStationAutoDoorPanelUpdatesRows() = runBlocking {
        val baseDir = Files.createTempDirectory("door-panel-auto-complete-key-test").toFile()
        val jobFolderName = "1234 - Door Panel Test"
        writeMaterialMappings(
            baseDir = baseDir,
            body = """{"3/4 Armor Core":"3_4 PG Armor Core"}"""
        )
        writeArmorCoreCutlistIndex(baseDir, jobFolderName)
        writeArmorCoreSpecialtyItems(baseDir, jobFolderName)

        val specialtyProgressStore = SpecialtyProgressStore(baseDir = baseDir, tabletId = "tablet-local")
        val hardwoodsProgressStore = HardwoodsProgressStore(baseDir = baseDir, tabletId = "tablet-local")
        val store = SpecialtyStateStore(
            specialtyScanCoordinator = SpecialtyScanCoordinator(
                SpecialtyRepository(baseDir = baseDir, progressStore = specialtyProgressStore)
            ),
            specialtyProgressStore = specialtyProgressStore,
            hardwoodsProgressStore = hardwoodsProgressStore,
            sheetRipProgressStore = SheetRipProgressStore(baseDir = baseDir),
            tabletItemsStore = TabletSpecialtyItemsStore(baseDir, "test-tablet"),
            baseDir = baseDir
        )

        store.setItemCompletionKey(jobFolderName, "auto-door", "SAW", true)

        val resolvedAfterSaw = specialtyProgressStore.loadResolvedItems(jobFolderName).first()
        assertTrue(resolvedAfterSaw.completionByKey["SAW"]?.completed == true)
        assertTrue(resolvedAfterSaw.completionByKey["EDGE_BANDER"]?.completed != true)
        assertEquals(2, doneCount(hardwoodsProgressStore, jobFolderName, "slab"))
        assertEquals(0, doneCount(hardwoodsProgressStore, jobFolderName, "rail"))

        store.setItemCompletionKey(jobFolderName, "auto-door", "SAW", false)

        assertEquals(0, doneCount(hardwoodsProgressStore, jobFolderName, "slab"))
    }

    @Test
    fun setItemCompletion_autoClosetRodItemUpdatesMatchingClosetRodRows() = runBlocking {
        val baseDir = Files.createTempDirectory("closet-rod-auto-complete-wiring-test").toFile()
        val jobFolderName = "1234 - Closet Rod Test"
        writeClosetRodCutlistIndex(baseDir, jobFolderName)
        writeClosetRodSpecialtyItems(baseDir, jobFolderName)

        val specialtyProgressStore = SpecialtyProgressStore(baseDir = baseDir, tabletId = "tablet-local")
        val hardwoodsProgressStore = HardwoodsProgressStore(baseDir = baseDir, tabletId = "tablet-local")
        val store = SpecialtyStateStore(
            specialtyScanCoordinator = SpecialtyScanCoordinator(
                SpecialtyRepository(baseDir = baseDir, progressStore = specialtyProgressStore)
            ),
            specialtyProgressStore = specialtyProgressStore,
            hardwoodsProgressStore = hardwoodsProgressStore,
            sheetRipProgressStore = SheetRipProgressStore(baseDir = baseDir),
            tabletItemsStore = TabletSpecialtyItemsStore(baseDir, "test-tablet"),
            baseDir = baseDir
        )

        store.setItemCompletion(jobFolderName, "auto-rods", true)
        hardwoodsProgressStore.awaitPendingWrites()

        assertEquals(2, doneCount(hardwoodsProgressStore, jobFolderName, HardwoodDocType.CLOSET_ROD_CUT_LIST, "rod-a"))
        assertEquals(1, doneCount(hardwoodsProgressStore, jobFolderName, HardwoodDocType.CLOSET_ROD_CUT_LIST, "rod-b"))
        assertEquals(0, doneCount(hardwoodsProgressStore, jobFolderName, HardwoodDocType.CLOSET_ROD_CUT_LIST, "rod-other"))

        store.setItemCompletion(jobFolderName, "auto-rods", false)
        hardwoodsProgressStore.awaitPendingWrites()

        assertEquals(0, doneCount(hardwoodsProgressStore, jobFolderName, HardwoodDocType.CLOSET_ROD_CUT_LIST, "rod-a"))
        assertEquals(0, doneCount(hardwoodsProgressStore, jobFolderName, HardwoodDocType.CLOSET_ROD_CUT_LIST, "rod-b"))
    }

    private fun doneCount(
        hardwoodsProgressStore: HardwoodsProgressStore,
        jobFolderName: String,
        rowId: String
    ): Int {
        return hardwoodsProgressStore.getRowProgress(
            jobFolderName,
            HardwoodDocType.DOOR_CUT_LIST.name,
            rowId
        ).doneCount
    }

    private fun doneCount(
        hardwoodsProgressStore: HardwoodsProgressStore,
        jobFolderName: String,
        docType: HardwoodDocType,
        rowId: String
    ): Int {
        return hardwoodsProgressStore.getRowProgress(
            jobFolderName,
            docType.name,
            rowId
        ).doneCount
    }

    private fun writeMaterialMappings(baseDir: File, body: String) {
        val file = File(baseDir, ".metadata/material_mappings.json")
        file.parentFile?.mkdirs()
        file.writeText(body)
    }

    private fun writeCutlistIndex(baseDir: File, jobFolderName: String) {
        writeRawCutlistIndex(
            baseDir = baseDir,
            jobFolderName = jobFolderName,
            body =
            """
                {
                  "documents": [
                    {
                      "docType": "DOOR_CUT_LIST",
                      "rows": [
                        {
                          "rowId": "r1",
                          "page": 1,
                          "rowOrdinal": 1,
                          "qty": 3,
                          "material": "1_4 2s Rustic Hickory",
                          "description": "Hickory door panel",
                          "unitType": "SHEETS"
                        },
                        {
                          "rowId": "r2",
                          "page": 1,
                          "rowOrdinal": 2,
                          "qty": 1,
                          "material": "1_4 2s Rustic Hickory",
                          "description": "Hickory door panel",
                          "unitType": "SHEETS"
                        },
                        {
                          "rowId": "rail",
                          "page": 1,
                          "rowOrdinal": 3,
                          "qty": 2,
                          "material": "3/4 Solid Hickory Rustic",
                          "description": "Hickory rail",
                          "unitType": "BD_FT"
                        }
                      ]
                    }
                  ]
                }
            """.trimIndent()
        )
    }

    private fun writeRawCutlistIndex(baseDir: File, jobFolderName: String, body: String) {
        val file = File(baseDir, "$jobFolderName/.metadata/hardwoods/cutlist_index.json")
        file.parentFile?.mkdirs()
        file.writeText(body)
    }

    private fun writeSpecialtyItems(baseDir: File, jobFolderName: String) {
        val file = File(baseDir, "$jobFolderName/.metadata/admin/specialty_items.json")
        file.parentFile?.mkdirs()
        file.writeText(
            """
                {
                  "items": [
                    {
                      "id": "auto-door",
                      "name": "Door panels - 1/4 2s Hickory Rustic",
                      "category": "CUSTOM",
                      "stations": ["SAW"],
                      "autoDetected": true,
                      "automationKey": "door_panels_auto|1/4 2S HICKORY RUSTIC|flat",
                      "material": "1/4 2s Hickory Rustic"
                    }
                  ]
                }
            """.trimIndent()
        )
    }

    private fun writeArmorCoreCutlistIndex(baseDir: File, jobFolderName: String) {
        writeRawCutlistIndex(
            baseDir = baseDir,
            jobFolderName = jobFolderName,
            body =
            """
                {
                  "documents": [
                    {
                      "docType": "DOOR_CUT_LIST",
                      "rows": [
                        {
                          "rowId": "slab",
                          "page": 1,
                          "rowOrdinal": 1,
                          "qty": 2,
                          "material": "3/4 Armor Core",
                          "description": "Door Slab",
                          "unitType": "SHEETS"
                        },
                        {
                          "rowId": "rail",
                          "page": 1,
                          "rowOrdinal": 2,
                          "qty": 4,
                          "material": "3/4 Solid Hickory Rustic",
                          "description": "Rail",
                          "unitType": "BD_FT"
                        }
                      ]
                    }
                  ]
                }
            """.trimIndent()
        )
    }

    private fun writeArmorCoreSpecialtyItems(baseDir: File, jobFolderName: String) {
        val file = File(baseDir, "$jobFolderName/.metadata/admin/specialty_items.json")
        file.parentFile?.mkdirs()
        file.writeText(
            """
                {
                  "items": [
                    {
                      "id": "auto-door",
                      "name": "Door panels - 3/4 Armor Core",
                      "category": "CUSTOM",
                      "stations": ["SAW", "EDGE_BANDER"],
                      "autoDetected": true,
                      "automationKey": "door_panels_auto|3/4 ARMOR CORE|slab",
                      "material": "3/4 Armor Core"
                    }
                  ]
                }
            """.trimIndent()
        )
    }

    private fun writeClosetRodCutlistIndex(baseDir: File, jobFolderName: String) {
        writeRawCutlistIndex(
            baseDir = baseDir,
            jobFolderName = jobFolderName,
            body =
            """
                {
                  "documents": [
                    {
                      "docType": "CLOSET_ROD_CUT_LIST",
                      "rows": [
                        {
                          "rowId": "rod-a",
                          "page": 1,
                          "rowOrdinal": 1,
                          "qty": 2,
                          "material": "Oval Chrome",
                          "description": "Closet Rod",
                          "length": "36",
                          "unitType": "PER_FT"
                        },
                        {
                          "rowId": "rod-b",
                          "page": 1,
                          "rowOrdinal": 2,
                          "qty": 1,
                          "material": " oval   chrome ",
                          "description": "Closet Rod",
                          "length": "24",
                          "unitType": "PER_FT"
                        },
                        {
                          "rowId": "rod-other",
                          "page": 1,
                          "rowOrdinal": 3,
                          "qty": 1,
                          "material": "Brass",
                          "description": "Closet Rod",
                          "length": "48",
                          "unitType": "PER_FT"
                        }
                      ]
                    }
                  ]
                }
            """.trimIndent()
        )
    }

    private fun writeClosetRodSpecialtyItems(baseDir: File, jobFolderName: String) {
        val file = File(baseDir, "$jobFolderName/.metadata/admin/specialty_items.json")
        file.parentFile?.mkdirs()
        file.writeText(
            """
                {
                  "items": [
                    {
                      "id": "auto-rods",
                      "name": "Closet rods - Oval Chrome",
                      "category": "CUSTOM",
                      "stations": ["SPECIALTY", "HARDWOODS"],
                      "autoDetected": true,
                      "automationKey": "closet_rods_auto|OVAL CHROME",
                      "material": "Oval Chrome"
                    }
                  ]
                }
            """.trimIndent()
        )
    }
}
