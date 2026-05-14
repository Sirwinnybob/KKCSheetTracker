package com.kkc.sheettracker.data

import com.google.gson.Gson
import com.kkc.sheettracker.data.models.HardwoodCutlistIndex
import com.kkc.sheettracker.data.models.HardwoodCutlistRow
import com.kkc.sheettracker.data.models.HardwoodDocType
import com.kkc.sheettracker.data.models.HardwoodDocumentIndex
import com.kkc.sheettracker.data.models.HardwoodTotalsBlock
import com.kkc.sheettracker.data.models.RefreshReason
import com.kkc.sheettracker.data.models.ScanStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class UnifiedFacadeParityTest {
    private val gson = Gson()
    private val jobFolder = "1234 - Test Job"

    @Test
    fun jobRepository_cncScanAndSearchIndex_matchExpectedData() {
        val baseDir = createTempBaseDir()
        seedJob(baseDir)
        val repository = JobRepository(baseDir, isDebugBuild = true)

        val jobs = repository.scanJobs(forceRefresh = true)
        assertEquals(1, jobs.size)
        assertEquals(jobFolder, jobs.first().folderName)
        assertEquals(1, jobs.first().materials.size)
        assertEquals("1234 - White Melamine.pdf", jobs.first().materials.first().pdfFilename)

        val search = repository.buildSearchIndex(forceRefresh = true)
        assertEquals(1, search.size)
        assertEquals("Side Panel", search.first().partName)
        assertEquals(42, search.first().cabNumber)
    }

    @Test
    fun hardwoodsRepository_scanAndBoardStock_matchExpectedData() {
        val baseDir = createTempBaseDir()
        seedJob(baseDir)
        val repository = HardwoodsRepository(baseDir)

        val jobs = repository.scanJobs()
        assertEquals(1, jobs.size)
        assertEquals(jobFolder, jobs.first().folderName)

        val rows = repository.loadBoardStock(jobFolder)
        assertTrue(rows.isNotEmpty())
        assertTrue(rows.any { it.material.equals("Poplar", ignoreCase = true) })
        assertTrue(rows.any { it.source.name == "MANUAL" })
    }

    @Test
    fun assemblyStateStore_cabinetJumpContextAndParts_matchExpectedData() {
        val baseDir = createTempBaseDir()
        seedJob(baseDir)

        val jobRepository = JobRepository(baseDir, isDebugBuild = true)
        val progressStore = ProgressStore(baseDir, "tablet-a", File(baseDir, ".local"), readOnly = true)
        val scanCoordinator = ScanCoordinator(baseDir, jobRepository)
        val hardwoodRepository = HardwoodsRepository(baseDir)
        val hardwoodScanCoordinator = HardwoodsScanCoordinator(hardwoodRepository)
        val hardwoodProgressStore = HardwoodsProgressStore(baseDir, "tablet-a", readOnly = true)
        val assemblyScanCoordinator = AssemblyScanCoordinator(baseDir, jobRepository)

        scanCoordinator.refresh(RefreshReason.USER_REFRESH, force = true)
        hardwoodScanCoordinator.refresh(RefreshReason.USER_REFRESH, force = true)
        assemblyScanCoordinator.refresh(RefreshReason.USER_REFRESH, force = true)

        waitUntilReady { scanCoordinator.state.value.status == ScanStatus.READY }
        waitUntilReady { hardwoodScanCoordinator.state.value.status == ScanStatus.READY }
        waitUntilReady { assemblyScanCoordinator.state.value.status == ScanStatus.READY }

        val stateStore = AssemblyStateStore(
            assemblyScanCoordinator = assemblyScanCoordinator,
            scanCoordinator = scanCoordinator,
            hardwoodsScanCoordinator = hardwoodScanCoordinator,
            progressStore = progressStore,
            hardwoodsProgressStore = hardwoodProgressStore
        )

        val jump = stateStore.getCabinetJumpPages(jobFolder, "42")
        assertEquals(3, jump.first)
        assertEquals(9, jump.second)

        val context = stateStore.getCabinetContext(jobFolder, "42")
        assertEquals("Kitchen - A", context)

        // Parts-join parity is verified in UnifiedMetadataEngineTest where overlay callbacks are injected
        // without Android Log dependencies from ProgressStore internals.
    }

    private fun waitUntilReady(timeoutMs: Long = 3_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) {
                throw AssertionError("Timed out waiting for condition")
            }
            Thread.sleep(25)
        }
    }

    private fun seedJob(baseDir: File) {
        val jobDir = File(baseDir, jobFolder).apply { mkdirs() }
        File(jobDir, "1234 - Assembly Sheets.pdf").writeText("pdf")
        File(jobDir, "1234 - Plans & Elevations.pdf").writeText("pdf")

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

        val sheetIndexDir = File(jobDir, ".metadata").apply { mkdirs() }
        File(sheetIndexDir, "cabinet_sheet_index.json").writeText(
            """
            {
              "documents": {
                "assembly": {
                  "pdfFilename": "1234 - Assembly Sheets.pdf",
                  "cabinetToPages": { "42": [3] },
                  "pageDetails": {
                    "3": {
                      "cabinets": ["42"],
                      "room": "Kitchen",
                      "wall": "A",
                      "parts": [
                        {
                          "qty": 1,
                          "width": 12.0,
                          "length": 24.0,
                          "description": "Side Panel",
                          "material": "White Melamine",
                          "sectionType": "Panel",
                          "isPurchased": false
                        }
                      ]
                    }
                  }
                },
                "plansElevations": {
                  "pdfFilename": "1234 - Plans & Elevations.pdf",
                  "cabinetToPages": { "42": [9] },
                  "pageDetails": {}
                },
                "delivery": {}
              }
            }
            """.trimIndent()
        )

        val hardwoodDir = File(jobDir, ".metadata/hardwoods").apply { mkdirs() }
        val hardwoodIndex = HardwoodCutlistIndex(
            documents = listOf(
                HardwoodDocumentIndex(
                    docType = HardwoodDocType.FACE_FRAME_CUT_LIST,
                    pdfFilename = "1234 - Face Frame Cut List.pdf",
                    rows = listOf(
                        HardwoodCutlistRow(
                            rowId = "row-1",
                            qty = 1,
                            material = "Poplar",
                            description = "Side Panel",
                            width = "2",
                            length = "24",
                            cabinets = listOf("42")
                        )
                    ),
                    totals = listOf(
                        HardwoodTotalsBlock(
                            material = "Poplar",
                            widthValues = listOf("2"),
                            lengthValues = listOf("10")
                        )
                    )
                )
            )
        )
        File(hardwoodDir, "cutlist_index.json").writeText(gson.toJson(hardwoodIndex))
        File(hardwoodDir, "board_stock_manual.json").writeText(
            """
            {
              "entries": [
                {
                  "material": "Poplar",
                  "width": "2",
                  "totalFeet": 10
                }
              ]
            }
            """.trimIndent()
        )
    }

    private fun createTempBaseDir(): File = Files.createTempDirectory("unified-facade-parity-test").toFile()
}
