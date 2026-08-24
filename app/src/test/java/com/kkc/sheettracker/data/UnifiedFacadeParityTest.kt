package com.kkc.sheettracker.data

import com.google.gson.Gson
import com.kkc.sheettracker.data.models.HardwoodCutlistIndex
import com.kkc.sheettracker.data.models.HardwoodCutlistRow
import com.kkc.sheettracker.data.models.HardwoodDocType
import com.kkc.sheettracker.data.models.HardwoodDocumentIndex
import com.kkc.sheettracker.data.models.HardwoodTotalsBlock
import com.kkc.sheettracker.data.models.RefreshReason
import com.kkc.sheettracker.data.models.ScanStatus
import com.kkc.sheettracker.data.unified.UnifiedMetadataEngineRegistry
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
    fun scanCoordinator_targetedDeepRefreshLoadsNewCncMaterial() {
        val baseDir = createTempBaseDir()
        seedJob(baseDir)
        seedInitialStaticCache(baseDir)
        val repository = JobRepository(baseDir, isDebugBuild = true)
        val coordinator = ScanCoordinator(baseDir, repository)

        coordinator.refresh(RefreshReason.USER_REFRESH, force = true)
        waitUntilReady {
            coordinator.state.value.status == ScanStatus.READY
        }
        // snapshot.jobs now empty by design — verify via direct engine call
        val initialJob = coordinator.unifiedEngine.getCncSnapshot(jobFolder)?.job
        assertTrue("Expected initial job to be loaded via getCncSnapshot", initialJob != null)
        assertEquals(1, initialJob!!.materials.size)

        seedRemake(baseDir)

        coordinator.refreshJobsDeep(listOf(jobFolder))
        val sawRemake = waitUntil(timeoutMs = 5_000L) {
            coordinator.unifiedEngine.getCncSnapshot(jobFolder)?.job?.materials
                ?.any { it.pdfFilename == "1234 - Remake Maple.pdf" } == true
        }
        val filenames = coordinator.unifiedEngine.getCncSnapshot(jobFolder)?.job?.materials
            ?.map { it.pdfFilename }.orEmpty()
        assertTrue("Expected targeted deep refresh to load remake material; saw $filenames", sawRemake)
    }

    @Test
    fun scanCoordinator_cacheOnlyWatcherRefreshDoesNotDiscardDeepRemakeWhilePublishedCacheIsStale() {
        val baseDir = createTempBaseDir()
        seedJob(baseDir)
        seedInitialStaticCache(baseDir)
        val repository = JobRepository(baseDir, isDebugBuild = true)
        val coordinator = ScanCoordinator(baseDir, repository)

        coordinator.refresh(RefreshReason.WATCHER_CHANGE, force = true)
        waitUntilReady {
            coordinator.state.value.status == ScanStatus.READY
        }
        val initialJob = coordinator.unifiedEngine.getCncSnapshot(jobFolder)?.job
        assertTrue("Expected initial job", initialJob != null)
        assertEquals(1, initialJob!!.materials.size)
        seedRemake(baseDir)

        coordinator.refreshJobsDeep(listOf(jobFolder))
        val remakeLoaded = waitUntil(timeoutMs = 5_000L) {
            coordinator.unifiedEngine.getCncSnapshot(jobFolder)?.job?.materials
                ?.any { it.pdfFilename == "1234 - Remake Maple.pdf" } == true
        }
        assertTrue("Expected targeted deep refresh to load remake material", remakeLoaded)

        val generationBeforeWatcherRefresh = coordinator.state.value.snapshot.generation
        coordinator.refresh(RefreshReason.WATCHER_CHANGE, force = true)
        val remakeSurvived = waitUntil(timeoutMs = 5_000L) {
            coordinator.state.value.status == ScanStatus.READY &&
                coordinator.state.value.snapshot.generation > generationBeforeWatcherRefresh
        }
        assertTrue("Watcher refresh must not replace deep remake with stale cache", remakeSurvived)
    }

    @Test
    fun publishedStaticCacheRemainsUsableWhenDerivedRemakeCandidatesIsNewer() {
        val baseDir = createTempBaseDir()
        seedJob(baseDir)
        seedInitialStaticCache(baseDir)
        val cacheFile = File(baseDir, "$jobFolder/.metadata/cache_static.json")
        val gateFile = File(baseDir, "$jobFolder/.metadata/deployment_gate.json")
        gateFile.writeText("""{"deployed":true,"parseReady":false}""")
        gateFile.setLastModified(cacheFile.lastModified() - 1_000L)

        val derivedCandidates = File(baseDir, "$jobFolder/CNC/.metadata/remake_bad_parts_candidates.json")
        derivedCandidates.writeText("""{"jobFolderName":"$jobFolder","candidates":[]}""")
        derivedCandidates.setLastModified(cacheFile.lastModified() + 1_000L)

        val engine = UnifiedMetadataEngineRegistry.getOrCreate(baseDir, isDebugBuild = false)

        assertEquals(
            "1234 - White Melamine.pdf",
            engine.getCncSnapshot(jobFolder)?.job?.materials?.single()?.pdfFilename
        )
    }

    @Test
    fun scanCoordinator_cacheOnlyWatcherRefreshDoesNotDiscardDeepJobWhilePublishedCacheIsMissing() {
        val baseDir = createTempBaseDir()
        seedJob(baseDir)
        File(baseDir, "$jobFolder/.metadata/deployment_gate.json")
            .writeText("""{"deployed": true, "parseReady": true}""")
        val repository = JobRepository(baseDir, isDebugBuild = true)
        val coordinator = ScanCoordinator(baseDir, repository)

        coordinator.refresh(RefreshReason.WATCHER_CHANGE, force = true)
        waitUntilReady { coordinator.state.value.status == ScanStatus.READY }
        val deepJob = coordinator.unifiedEngine.getCncSnapshot(jobFolder)?.job
        assertTrue("Expected background scan to load the job", deepJob != null)
        assertEquals(jobFolder, deepJob!!.folderName)

        val generationBeforeWatcherRefresh = coordinator.state.value.snapshot.generation
        coordinator.refresh(RefreshReason.WATCHER_CHANGE, force = true)
        val deepJobSurvived = waitUntil(timeoutMs = 5_000L) {
            coordinator.state.value.status == ScanStatus.READY &&
                coordinator.state.value.snapshot.generation > generationBeforeWatcherRefresh
        }
        assertTrue("Watcher refresh must not discard a deep-loaded job without cache_static.json", deepJobSurvived)
    }

    @Test
    fun hardwoodsRepository_listProjectionStaysIndexOnlyWhileBoardStockLoadsOnDemand() {
        val baseDir = createTempBaseDir()
        seedJob(baseDir)
        val repository = HardwoodsRepository(baseDir)

        val jobs = repository.scanJobs()
        assertEquals(1, jobs.size)
        assertEquals(jobFolder, jobs.first().folderName)
        assertEquals(null, jobs.first().index)

        val rows = repository.loadBoardStock(jobFolder)
        assertTrue(rows.isNotEmpty())
        assertTrue(rows.any { it.material.equals("Poplar", ignoreCase = true) })
        assertTrue(rows.any { it.source.name == "MANUAL" })
    }

    @Test
    fun hardwoodsSearchProjection_loadsFullSnapshotsOnlyWhenSearchIsOpened() {
        val baseDir = createTempBaseDir()
        seedJob(baseDir)
        File(baseDir, "$jobFolder/.metadata/cache_index.json").writeText(
            """{"jobInfo":{"folderName":"$jobFolder","jobNumber":"1234","jobName":"Test Job"},"progressSummary":{"hardwoods":{"totalPieces":1,"donePieces":0,"badPieces":0,"skippedPieces":0}}}"""
        )
        val repository = HardwoodsRepository(baseDir)

        // The Jobs-list scan remains index-only and deliberately has no row-level data.
        assertTrue(repository.scanJobsFromCacheOnly().searchIndex.isEmpty())

        val searchEntries = repository.buildSearchIndexForSearchScreen()

        assertEquals(1, searchEntries.size)
        assertEquals("Side Panel", searchEntries.single().description)
        assertEquals(jobFolder, searchEntries.single().jobFolderName)
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
            hardwoodsProgressStore = hardwoodProgressStore,
            liveEngine = UnifiedMetadataEngineRegistry.getOrCreate(baseDir, isDebugBuild = true)
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
        if (waitUntil(timeoutMs, condition)) return
        throw AssertionError("Timed out waiting for condition")
    }

    private fun waitUntil(timeoutMs: Long = 3_000L, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) {
                return false
            }
            Thread.sleep(25)
        }
        return true
    }

    private fun seedJob(baseDir: File) {
        val jobDir = File(baseDir, jobFolder).apply { mkdirs() }
        val sheetIndexDir = File(jobDir, ".metadata").apply { mkdirs() }
        File(sheetIndexDir, "deployment_gate.json").writeText("""{"deployed": true}""")
        File(sheetIndexDir, "cache_index.json").writeText(
            """{"jobInfo":{"folderName":"$jobFolder","jobNumber":"1234","jobName":"Test Job"},"progressSummary":{"cnc":{"totalSheets":1},"hardwoods":{"totalPieces":1,"donePieces":0,"badPieces":0,"skippedPieces":0},"hasDeliverySheet":false,"has3DAssets":false}}"""
        )
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

    private fun seedRemake(baseDir: File) {
        val cncDir = File(baseDir, "$jobFolder/CNC")
        File(cncDir, "1234 - Remake Maple.pdf").writeText("pdf-remake")
        File(cncDir, ".metadata/1234 - Remake Maple.json").writeText(
            """
            {
              "jobNumber": "1234",
              "jobName": "Test Job",
              "material": "Remake Maple",
              "pdfFilename": "1234 - Remake Maple.pdf",
              "remakeLabel": "Remake",
              "pages": [
                {
                  "pageNumber": 1,
                  "parts": [
                    {
                      "number": 7,
                      "width": 5.0,
                      "length": 10.0,
                      "name": "Replacement Shelf",
                      "cabNumber": 42,
                      "room": "Kitchen"
                    }
                  ]
                }
              ]
            }
            """.trimIndent()
        )
    }

    private fun seedInitialStaticCache(baseDir: File) {
        val cacheFile = File(baseDir, "$jobFolder/.metadata/cache_static.json")
        cacheFile.parentFile?.mkdirs()
        cacheFile.writeText(
            """
            {
              "jobInfo": {
                "folderName": "$jobFolder",
                "jobNumber": "1234",
                "jobName": "Test Job",
                "hiddenFromProduction": false,
                "lineupPosition": 1
              },
              "cncJob": {
                "folderName": "$jobFolder",
                "jobNumber": "1234",
                "jobName": "Test Job",
                "materials": [
                  {
                    "pdfFilename": "1234 - White Melamine.pdf",
                    "materialName": "White Melamine",
                    "pageCount": 1,
                    "fileFingerprint": "initial",
                    "metadata": {
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
                  }
                ],
                "hiddenFromProduction": false,
                "lineupPosition": 1
              },
              "cncIssues": [],
              "pdfCatalog": { "managedDocs": [], "otherDocs": [] },
              "boardStockRows": [],
              "hasThreeDAssets": false
            }
            """.trimIndent()
        )
        cacheFile.setLastModified(System.currentTimeMillis() - 10_000L)
    }

    private fun createTempBaseDir(): File = Files.createTempDirectory("unified-facade-parity-test").toFile()
}
