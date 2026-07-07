package com.kkc.sheettracker.data

import com.google.gson.Gson
import com.kkc.sheettracker.data.models.HardwoodCutlistIndex
import com.kkc.sheettracker.data.models.HardwoodCutlistRow
import com.kkc.sheettracker.data.models.HardwoodDocType
import com.kkc.sheettracker.data.models.HardwoodDocumentIndex
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Regression test for code-review finding #23: the CNC-completion -> hardwoods sync
 * listener. Mirrors the wiring registered in NavGraph.kt, where
 * `progressStore.onSheetStatusChangedListener` invokes `syncCncToHardwoods` on the IO
 * dispatcher whenever a sheet is marked complete/incomplete.
 */
class CncToHardwoodsSyncTest {
    private val gson = Gson()
    private val jobFolder = "1234 - Test Job"

    private fun createTempDirectory(): File = Files.createTempDirectory("cnc-to-hardwoods-sync-test").toFile()

    @Test
    fun testMarkSheetCompleteSyncsToHardwoodsViaListener() {
        val baseDir = createTempDirectory()

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
}
