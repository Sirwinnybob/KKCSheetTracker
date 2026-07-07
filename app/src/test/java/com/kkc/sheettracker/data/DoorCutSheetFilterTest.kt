package com.kkc.sheettracker.data

import com.google.gson.Gson
import com.kkc.sheettracker.data.models.HardwoodCutlistIndex
import com.kkc.sheettracker.data.models.HardwoodCutlistRow
import com.kkc.sheettracker.data.models.HardwoodDocType
import com.kkc.sheettracker.data.models.HardwoodDocumentIndex
import com.kkc.sheettracker.data.models.Job
import com.kkc.sheettracker.data.models.Material
import com.kkc.sheettracker.data.models.MaterialMetadata
import com.kkc.sheettracker.data.models.PageMetadata
import com.kkc.sheettracker.data.models.Part
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DoorCutSheetFilterTest {
    private val gson = Gson()
    private val jobFolder = "1234 - Test Job"

    @Test
    fun testParseDimension() {
        assertEquals(15.375, parseDimension("15 3/8")!!, 0.0001)
        assertEquals(0.375, parseDimension("3/8")!!, 0.0001)
        assertEquals(15.375, parseDimension("15.375")!!, 0.0001)
        assertEquals(15.0, parseDimension("15")!!, 0.0001)
        assertEquals(null, parseDimension(""))
        assertEquals(null, parseDimension("   "))
        assertEquals(null, parseDimension("3 / 0")) // denominator zero
    }

    @Test
    fun testPreciseMatches() {
        val row = HardwoodCutlistRow(
            cabinets = listOf("34", "35"),
            description = " shaker  Door ",
            width = "15 3/8",
            length = "30 1/2"
        )

        // Exact match
        val partExact = Part(
            cabNumber = 34,
            name = "SHAKER DOOR",
            width = 15.375,
            length = 30.5
        )
        assertTrue(preciseMatches(row, partExact))

        // Rotated match
        val partRotated = Part(
            cabNumber = 35,
            name = "Shaker Door",
            width = 30.5,
            length = 15.375
        )
        assertTrue(preciseMatches(row, partRotated))

        // Tolerance match (0.015 deviation)
        val partWithinTolerance = Part(
            cabNumber = 34,
            name = "Shaker Door",
            width = 15.36,
            length = 30.51
        )
        assertTrue(preciseMatches(row, partWithinTolerance))

        // Out of tolerance match (0.03 deviation)
        val partOutOfTolerance = Part(
            cabNumber = 34,
            name = "Shaker Door",
            width = 15.34,
            length = 30.5
        )
        assertFalse(preciseMatches(row, partOutOfTolerance))

        // Cabinet mismatch
        val partWrongCabinet = Part(
            cabNumber = 36,
            name = "SHAKER DOOR",
            width = 15.375,
            length = 30.5
        )
        assertFalse(preciseMatches(row, partWrongCabinet))

        // Name mismatch
        val partWrongName = Part(
            cabNumber = 34,
            name = "SLAB DRAWER FRONT",
            width = 15.375,
            length = 30.5
        )
        assertFalse(preciseMatches(row, partWrongName))
    }

    @Test
    fun testSyncCncToHardwoodsExcludesFaceFrameCutList() {
        val baseDir = Files.createTempDirectory("door-cut-sheet-filter-test").toFile()
        
        // Seed CNC files and metadata
        val jobDir = File(baseDir, jobFolder).apply { mkdirs() }
        val metadataDir = File(jobDir, ".metadata").apply { mkdirs() }
        File(metadataDir, "deployment_gate.json").writeText("{\"deployed\": true}")
        val cncDir = File(jobDir, "CNC").apply { mkdirs() }
        File(cncDir, "1234 - White Melamine.pdf").writeText("pdf")
        val cncMetaDir = File(cncDir, ".metadata").apply { mkdirs() }
        File(cncMetaDir, "1234 - White Melamine.json").writeText(
            """
            {
              "jobNumber": "1234",
              "jobName": "Test Job",
              "material": "White Melamine",
              "pdfFilename": "1234 - White Melamine.pdf",
              "pages": [
                {
                  "pageNumber": 1,
                  "parts": [
                    {
                      "number": 1,
                      "width": 12.0,
                      "length": 24.0,
                      "name": "Side Panel",
                      "cabNumber": 42,
                      "room": "Kitchen"
                    }
                  ]
                }
              ]
            }
            """.trimIndent()
        )

        // Seed ProgressStore state to show sheet 1 is complete
        val progressStore = ProgressStore(
            baseDir = baseDir,
            tabletId = "tablet-a",
            localStateDir = File(baseDir, ".local"),
            readOnly = false
        )
        progressStore.markSheetComplete(
            jobFolderName = jobFolder,
            pdfFilename = "1234 - White Melamine.pdf",
            page = 1,
            fileFingerprint = "${File(cncDir, "1234 - White Melamine.pdf").length()}_${File(cncDir, "1234 - White Melamine.pdf").lastModified()}"
        )

        // Seed Hardwoods index containing FACE_FRAME_CUT_LIST, DOOR_CUT_LIST, DOOR_LIST, NAILER_CUT_LIST
        val hardwoodDir = File(jobDir, ".metadata/hardwoods").apply { mkdirs() }
        val hardwoodIndex = HardwoodCutlistIndex(
            documents = listOf(
                HardwoodDocumentIndex(
                    docType = HardwoodDocType.FACE_FRAME_CUT_LIST,
                    pdfFilename = "1234 - Face Frame Cut List.pdf",
                    rows = listOf(
                        HardwoodCutlistRow(
                            rowId = "ff-row-1",
                            qty = 1,
                            material = "Poplar",
                            description = "Side Panel",
                            width = "12",
                            length = "24",
                            cabinets = listOf("42")
                        )
                    )
                ),
                HardwoodDocumentIndex(
                    docType = HardwoodDocType.DOOR_CUT_LIST,
                    pdfFilename = "1234 - Door Cut List.pdf",
                    rows = listOf(
                        HardwoodCutlistRow(
                            rowId = "door-cut-row-1",
                            qty = 1,
                            material = "Poplar",
                            description = "Side Panel",
                            width = "12",
                            length = "24",
                            cabinets = listOf("42")
                        )
                    )
                ),
                HardwoodDocumentIndex(
                    docType = HardwoodDocType.DOOR_LIST,
                    pdfFilename = "1234 - Door List.pdf",
                    rows = listOf(
                        HardwoodCutlistRow(
                            rowId = "door-list-row-1",
                            qty = 1,
                            material = "Poplar",
                            description = "Side Panel",
                            width = "12",
                            length = "24",
                            cabinets = listOf("42")
                        )
                    )
                ),
                HardwoodDocumentIndex(
                    docType = HardwoodDocType.NAILER_CUT_LIST,
                    pdfFilename = "1234 - Nailer Cut List.pdf",
                    rows = listOf(
                        HardwoodCutlistRow(
                            rowId = "nailer-row-1",
                            qty = 1,
                            material = "Poplar",
                            description = "Side Panel",
                            width = "12",
                            length = "24",
                            cabinets = listOf("42")
                        )
                    )
                )
            )
        )
        File(hardwoodDir, "cutlist_index.json").writeText(gson.toJson(hardwoodIndex))

        // Create repositories and progress store
        val jobRepository = JobRepository(baseDir, isDebugBuild = true)
        val hardwoodsRepository = HardwoodsRepository(baseDir)
        val hardwoodsProgressStore = HardwoodsProgressStore(
            baseDir = baseDir,
            tabletId = "tablet-a",
            readOnly = false
        )

        // Perform synchronization
        syncCncToHardwoods(
            jobFolderName = jobFolder,
            jobRepository = jobRepository,
            progressStore = progressStore,
            hardwoodsRepository = hardwoodsRepository,
            hardwoodsProgressStore = hardwoodsProgressStore
        )

        // Flush writes
        hardwoodsProgressStore.awaitPendingWrites()

        // Verify that FACE_FRAME_CUT_LIST is NOT synced (doneCount remains 0)
        val ffProgress = hardwoodsProgressStore.getRowProgress(jobFolder, HardwoodDocType.FACE_FRAME_CUT_LIST.name, "ff-row-1")
        assertEquals(0, ffProgress.doneCount)

        // Verify that DOOR_CUT_LIST, DOOR_LIST, and NAILER_CUT_LIST ARE synced (doneCount is 1)
        val doorCutProgress = hardwoodsProgressStore.getRowProgress(jobFolder, HardwoodDocType.DOOR_CUT_LIST.name, "door-cut-row-1")
        assertEquals(1, doorCutProgress.doneCount)

        val doorListProgress = hardwoodsProgressStore.getRowProgress(jobFolder, HardwoodDocType.DOOR_LIST.name, "door-list-row-1")
        assertEquals(1, doorListProgress.doneCount)

        val nailerProgress = hardwoodsProgressStore.getRowProgress(jobFolder, HardwoodDocType.NAILER_CUT_LIST.name, "nailer-row-1")
        assertEquals(1, nailerProgress.doneCount)
    }

    @Test
    fun deriveCncOwnedDoorPanelMaterials_usesMappedMaterialWhenAllRowsAreCovered() {
        val baseDir = createTempDirectory()
        writeMaterialMappings(
            baseDir,
            """
                {
                  "1/4 MDF": "1_4 MDF"
                }
            """.trimIndent()
        )
        writeDoorCutIndex(
            baseDir = baseDir,
            rows = listOf(
                sheetDoorRow(
                    rowId = "DOOR_CUT_LIST:1:0:a",
                    material = "1/4 MDF",
                    description = "Center Panel",
                    cabinets = listOf("22"),
                    qty = 1
                ),
                sheetDoorRow(
                    rowId = "DOOR_CUT_LIST:1:1:b",
                    material = "1/4 MDF",
                    description = "Center Panel",
                    cabinets = listOf("23"),
                    qty = 1
                )
            )
        )

        val cncJob = Job(
            folderName = jobFolder,
            jobNumber = "1234",
            jobName = "Test Job",
            materials = listOf(
                materialWithParts(
                    materialName = "1_4 MDF",
                    parts = listOf(
                        Part(name = "Center Panel", cabNumber = 22, width = 10.0, length = 20.0),
                        Part(name = "Center Panel", cabNumber = 23, width = 10.0, length = 20.0)
                    )
                )
            )
        )

        val owned = deriveCncOwnedDoorPanelMaterials(
            baseDir = baseDir,
            jobFolderName = jobFolder,
            cncJob = cncJob
        )

        assertEquals(setOf("1_4 MDF"), owned)
    }

    @Test
    fun deriveCncOwnedDoorPanelMaterials_requiresFullCoveragePerSheetRow() {
        val baseDir = createTempDirectory()
        writeMaterialMappings(baseDir, """{"1/4 MDF":"1_4 MDF"}""")
        writeDoorCutIndex(
            baseDir = baseDir,
            rows = listOf(
                sheetDoorRow(
                    rowId = "DOOR_CUT_LIST:1:0:a",
                    material = "1/4 MDF",
                    description = "Center Panel",
                    cabinets = listOf("22"),
                    qty = 1
                ),
                sheetDoorRow(
                    rowId = "DOOR_CUT_LIST:1:1:b",
                    material = "1/4 MDF",
                    description = "Center Panel",
                    cabinets = listOf("23"),
                    qty = 1
                )
            )
        )

        val cncJob = Job(
            folderName = jobFolder,
            jobNumber = "1234",
            jobName = "Test Job",
            materials = listOf(
                materialWithParts(
                    materialName = "1_4 MDF",
                    parts = listOf(
                        Part(name = "Center Panel", cabNumber = 22, width = 10.0, length = 20.0)
                    )
                )
            )
        )

        val owned = deriveCncOwnedDoorPanelMaterials(
            baseDir = baseDir,
            jobFolderName = jobFolder,
            cncJob = cncJob
        )

        assertTrue(owned.isEmpty())
    }

    @Test
    fun deriveCncOwnedDoorPanelMaterials_fallsBackToNormalizedRawMaterialWhenNoMappingExists() {
        val baseDir = createTempDirectory()
        writeDoorCutIndex(
            baseDir = baseDir,
            rows = listOf(
                sheetDoorRow(
                    rowId = "DOOR_CUT_LIST:1:0:a",
                    material = "1/4 Mystery Core",
                    description = "Panel Slab",
                    cabinets = listOf("9"),
                    qty = 1
                )
            )
        )

        val cncJob = Job(
            folderName = jobFolder,
            jobNumber = "1234",
            jobName = "Test Job",
            materials = listOf(
                materialWithParts(
                    materialName = "1/4 MYSTERY CORE",
                    parts = listOf(
                        Part(name = "Panel Slab", cabNumber = 9, width = 10.0, length = 20.0)
                    )
                )
            )
        )

        val owned = deriveCncOwnedDoorPanelMaterials(
            baseDir = baseDir,
            jobFolderName = jobFolder,
            cncJob = cncJob
        )

        assertEquals(setOf("1/4 MYSTERY CORE"), owned)
    }

    @Test
    fun deriveCncOwnedDoorPanelMaterials_skipsMaterialWithoutMatchingCncMaterial() {
        val baseDir = createTempDirectory()
        writeMaterialMappings(baseDir, """{"1/4 MDF":"1_4 MDF"}""")
        writeDoorCutIndex(
            baseDir = baseDir,
            rows = listOf(
                sheetDoorRow(
                    rowId = "DOOR_CUT_LIST:1:0:a",
                    material = "1/4 MDF",
                    description = "Center Panel",
                    cabinets = listOf("22"),
                    qty = 1
                )
            )
        )

        val cncJob = Job(
            folderName = jobFolder,
            jobNumber = "1234",
            jobName = "Test Job",
            materials = listOf(
                materialWithParts(
                    materialName = "1_4 WHITE OAK",
                    parts = listOf(
                        Part(name = "Center Panel", cabNumber = 22, width = 10.0, length = 20.0)
                    )
                )
            )
        )

        val owned = deriveCncOwnedDoorPanelMaterials(
            baseDir = baseDir,
            jobFolderName = jobFolder,
            cncJob = cncJob
        )

        assertTrue(owned.isEmpty())
    }

    @Test
    fun parseDoorCutUnitTypeMetadata_acceptsCabinetVisionUnitNames() {
        val cabinetVisionUnitNames = listOf(
            "Each",
            "Per FT",
            "SQ FT",
            "BD FT",
            "Sheet",
            "Per M",
            "SQ M",
            "BD M",
            "Cubic M",
            "Cubic FT",
            "Pair"
        )
        val rowsJson = cabinetVisionUnitNames.mapIndexed { index, unitName ->
            """
                {
                  "rowId": "row-$index",
                  "material": "Material $index",
                  "unitType": "$unitName"
                }
            """.trimIndent()
        }.joinToString(",")
        val materialUnitJson = cabinetVisionUnitNames.mapIndexed { index, unitName ->
            """"Material By Unit $index": "$unitName""""
        }.joinToString(",")
        val rawJson = """
            {
              "documents": [
                {
                  "docType": "DOOR_CUT_LIST",
                  "rows": [$rowsJson],
                  "unitTypeByMaterial": {
                    $materialUnitJson
                  }
                }
              ]
            }
        """.trimIndent()

        val parsed = parseDoorCutUnitTypeMetadata(rawJson)

        assertTrue(parsed.hasUnitTypeMetadata)
        assertEquals(setOf("ROW-4"), parsed.sheetRowIds)
        assertEquals(setOf("MATERIAL 4", "MATERIAL BY UNIT 4"), parsed.sheetMaterials)
    }

    @Test
    fun testMarkSheetCompleteSyncsToHardwoodsViaListener() {
        val baseDir = Files.createTempDirectory("door-cut-sheet-filter-test").toFile()
        
        // Seed CNC files and metadata
        val jobDir = File(baseDir, jobFolder).apply { mkdirs() }
        val metadataDir = File(jobDir, ".metadata").apply { mkdirs() }
        File(metadataDir, "deployment_gate.json").writeText("{\"deployed\": true}")
        val cncDir = File(jobDir, "CNC").apply { mkdirs() }
        File(cncDir, "1234 - White Melamine.pdf").writeText("pdf")
        val cncMetaDir = File(cncDir, ".metadata").apply { mkdirs() }
        File(cncMetaDir, "1234 - White Melamine.json").writeText(
            """
            {
              "jobNumber": "1234",
              "jobName": "Test Job",
              "material": "White Melamine",
              "pdfFilename": "1234 - White Melamine.pdf",
              "pages": [
                {
                  "pageNumber": 1,
                  "parts": [
                    {
                      "number": 1,
                      "width": 12.0,
                      "length": 24.0,
                      "name": "Side Panel",
                      "cabNumber": 42,
                      "room": "Kitchen"
                    }
                  ]
                }
              ]
            }
            """.trimIndent()
        )

        // Seed Hardwoods index containing matching rows
        val hardwoodDir = File(jobDir, ".metadata/hardwoods").apply { mkdirs() }
        val hardwoodIndex = HardwoodCutlistIndex(
            documents = listOf(
                HardwoodDocumentIndex(
                    docType = HardwoodDocType.DOOR_CUT_LIST,
                    pdfFilename = "1234 - Door Cut List.pdf",
                    rows = listOf(
                        HardwoodCutlistRow(
                            rowId = "door-cut-row-1",
                            qty = 1,
                            material = "Poplar",
                            description = "Side Panel",
                            width = "12",
                            length = "24",
                            cabinets = listOf("42")
                        )
                    )
                )
            )
        )
        File(hardwoodDir, "cutlist_index.json").writeText(gson.toJson(hardwoodIndex))

        // Create repositories, progress stores, etc.
        val jobRepository = JobRepository(baseDir, isDebugBuild = true)
        val progressStore = ProgressStore(
            baseDir = baseDir,
            tabletId = "tablet-a",
            localStateDir = File(baseDir, ".local"),
            readOnly = false
        )
        val hardwoodsRepository = HardwoodsRepository(baseDir)
        val hardwoodsProgressStore = HardwoodsProgressStore(
            baseDir = baseDir,
            tabletId = "tablet-a",
            readOnly = false
        )

        // Set up the onSheetStatusChangedListener to trigger syncCncToHardwoods
        // exactly like in NavGraph.kt
        progressStore.onSheetStatusChangedListener = { jobFolderName, _, _, _, _ ->
            syncCncToHardwoods(
                jobFolderName = jobFolderName,
                jobRepository = jobRepository,
                progressStore = progressStore,
                hardwoodsRepository = hardwoodsRepository,
                hardwoodsProgressStore = hardwoodsProgressStore
            )
        }

        // Verify that hardwoods progress doneCount is initially 0
        var doorCutProgress = hardwoodsProgressStore.getRowProgress(jobFolder, HardwoodDocType.DOOR_CUT_LIST.name, "door-cut-row-1")
        assertEquals(0, doorCutProgress.doneCount)

        // Mark the sheet complete, which triggers the listener
        val pdfFile = File(cncDir, "1234 - White Melamine.pdf")
        val fingerprint = "${pdfFile.length()}_${pdfFile.lastModified()}"
        progressStore.markSheetComplete(
            jobFolderName = jobFolder,
            pdfFilename = "1234 - White Melamine.pdf",
            page = 1,
            fileFingerprint = fingerprint
        )

        // Flush writes
        hardwoodsProgressStore.awaitPendingWrites()

        // Verify that hardwoods progress doneCount has synced to 1
        doorCutProgress = hardwoodsProgressStore.getRowProgress(jobFolder, HardwoodDocType.DOOR_CUT_LIST.name, "door-cut-row-1")
        assertEquals(1, doorCutProgress.doneCount)

        // Unmark the sheet complete, which also triggers the listener
        progressStore.unmarkSheetComplete(
            jobFolderName = jobFolder,
            pdfFilename = "1234 - White Melamine.pdf",
            page = 1,
            fileFingerprint = fingerprint
        )

        // Flush writes
        hardwoodsProgressStore.awaitPendingWrites()

        // Verify that hardwoods progress doneCount has reverted to 0
        doorCutProgress = hardwoodsProgressStore.getRowProgress(jobFolder, HardwoodDocType.DOOR_CUT_LIST.name, "door-cut-row-1")
        assertEquals(0, doorCutProgress.doneCount)
    }

    private fun createTempDirectory(): File = Files.createTempDirectory("door-cut-sheet-filter-test").toFile()

    private fun writeMaterialMappings(baseDir: File, body: String) {
        val file = File(baseDir, ".metadata/material_mappings.json")
        file.parentFile?.mkdirs()
        file.writeText(body)
    }

    private fun writeDoorCutIndex(baseDir: File, rows: List<HardwoodCutlistRow>) {
        val file = File(baseDir, "$jobFolder/.metadata/hardwoods/cutlist_index.json")
        file.parentFile?.mkdirs()
        val rowsJson = rows.joinToString(",\n") { row ->
            """
                {
                  "rowId": "${row.rowId}",
                  "qty": ${row.qty},
                  "material": ${gson.toJson(row.material)},
                  "description": ${gson.toJson(row.description)},
                  "width": ${gson.toJson(row.width)},
                  "length": ${gson.toJson(row.length)},
                  "cabinets": ${gson.toJson(row.cabinets)},
                  "unitType": "SHEETS"
                }
            """.trimIndent()
        }
        file.writeText(
            """
                {
                  "documents": [
                    {
                      "docType": "${HardwoodDocType.DOOR_CUT_LIST.name}",
                      "pdfFilename": "1234 - Door Cut List.pdf",
                      "rows": [
                        $rowsJson
                      ]
                    }
                  ]
                }
            """.trimIndent()
        )
    }

    private fun sheetDoorRow(
        rowId: String,
        material: String,
        description: String,
        cabinets: List<String>,
        qty: Int
    ): HardwoodCutlistRow {
        return HardwoodCutlistRow(
            rowId = rowId,
            qty = qty,
            material = material,
            description = description,
            width = "10",
            length = "20",
            cabinets = cabinets
        )
    }

    private fun materialWithParts(
        materialName: String,
        parts: List<Part>
    ): Material {
        return Material(
            pdfFilename = "1234 - $materialName.pdf",
            materialName = materialName,
            pageCount = 1,
            metadata = MaterialMetadata(
                material = materialName,
                pdfFilename = "1234 - $materialName.pdf",
                pages = listOf(
                    PageMetadata(
                        pageNumber = 1,
                        parts = parts
                    )
                )
            )
        )
    }
}
