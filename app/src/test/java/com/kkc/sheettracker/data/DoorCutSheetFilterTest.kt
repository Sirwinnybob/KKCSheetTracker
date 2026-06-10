package com.kkc.sheettracker.data

import com.google.gson.Gson
import com.kkc.sheettracker.data.models.HardwoodCutlistIndex
import com.kkc.sheettracker.data.models.HardwoodCutlistRow
import com.kkc.sheettracker.data.models.HardwoodDocType
import com.kkc.sheettracker.data.models.HardwoodDocumentIndex
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
}
