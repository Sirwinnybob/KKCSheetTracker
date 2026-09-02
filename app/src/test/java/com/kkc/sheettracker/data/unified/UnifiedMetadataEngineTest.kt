package com.kkc.sheettracker.data.unified

import com.google.gson.Gson
import com.kkc.sheettracker.data.models.HardwoodCutlistIndex
import com.kkc.sheettracker.data.models.HardwoodCutlistRow
import com.kkc.sheettracker.data.models.HardwoodDocType
import com.kkc.sheettracker.data.models.HardwoodDocumentIndex
import com.kkc.sheettracker.data.models.ReferenceDocType
import com.kkc.sheettracker.data.models.SheetStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class UnifiedMetadataEngineTest {
    private val gson = Gson()
    private val jobFolder = "1234 - Test Job"

    @Test
    fun loadsCncHardwoodsAndAssemblySnapshotsFromExistingFiles() {
        val baseDir = createTempBaseDir()
        seedJob(baseDir)
        val engine = FileBackedUnifiedMetadataEngine(
            basePath = baseDir.absolutePath,
            isDebugBuild = true,
            pdfPageCounter = { UnifiedPdfPageCountResult(8) }
        )

        val jobs = engine.listJobs()
        assertEquals(1, jobs.size)
        assertEquals(jobFolder, jobs.first().folderName)

        val cnc = engine.getCncSnapshot(jobFolder)
        assertNotNull(cnc)
        assertEquals(1, cnc?.job?.materials?.size)
        assertEquals(1, cnc?.searchIndex?.size)
        val loadedPart = cnc?.job?.materials?.first()?.metadata?.pages?.first()?.parts?.first()
        assertEquals(".metadata/parts/1234 - White Melamine_p001_part001.jpeg", loadedPart?.graphicPath)
        assertEquals("2WD2LD", loadedPart?.banding)

        val hardwood = engine.getHardwoodsSnapshot(jobFolder)
        assertNotNull(hardwood)
        assertEquals(1, hardwood?.job?.index?.documents?.size)

        val assembly = engine.getAssemblySnapshot(jobFolder)
        assertNotNull(assembly)
        assertNotNull(assembly?.job?.cabinetSheetIndex)
    }

    @Test
    fun getCncSnapshotPairsJobAndSearchIndexFromSameGeneration() {
        val baseDir = createTempBaseDir()
        seedJob(baseDir)
        val engine = FileBackedUnifiedMetadataEngine(
            basePath = baseDir.absolutePath,
            isDebugBuild = true,
            pdfPageCounter = { UnifiedPdfPageCountResult(8) }
        )

        val first = engine.getCncSnapshot(jobFolder)
        assertEquals(1, first?.searchIndex?.size)

        // Change raw CNC metadata to two parts (larger file -> new static signature).
        File(baseDir, "$jobFolder/CNC/.metadata/1234 - White Melamine.json").writeText(
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
                    { "number": 1, "width": 12.0, "length": 24.0, "name": "Side Panel", "cabNumber": 42, "room": "Kitchen" },
                    { "number": 2, "width": 6.0, "length": 10.0, "name": "Shelf", "cabNumber": 42, "room": "Kitchen" }
                  ]
                }
              ]
            }
            """.trimIndent()
        )
        engine.refreshJobDeep(jobFolder)

        val second = engine.getCncSnapshot(jobFolder)
        val partCount = second?.job?.materials?.sumOf { m -> m.metadata?.pages?.sumOf { it.parts.size } ?: 0 }
        // The returned cncJob and its memoized search index must be built from one generation,
        // so the index has exactly one entry per part in the (new) job data.
        assertEquals(2, partCount)
        assertEquals(partCount, second?.searchIndex?.size)
    }

    @Test
    fun getCncSnapshotStaysConsistentUnderConcurrentRefresh() {
        val baseDir = createTempBaseDir()
        seedJob(baseDir)
        val engine = FileBackedUnifiedMetadataEngine(
            basePath = baseDir.absolutePath,
            isDebugBuild = true,
            pdfPageCounter = { UnifiedPdfPageCountResult(8) }
        )
        val metadataFile = File(baseDir, "$jobFolder/CNC/.metadata/1234 - White Melamine.json")
        val onePartJson = metadataFile.readText()
        val twoPartJson = """
            {
              "jobNumber": "1234",
              "jobName": "Test Job",
              "material": "White Melamine",
              "pdfFilename": "1234 - White Melamine.pdf",
              "pages": [
                {
                  "pageNumber": 1,
                  "parts": [
                    { "number": 1, "width": 12.0, "length": 24.0, "name": "Side Panel", "cabNumber": 42, "room": "Kitchen" },
                    { "number": 2, "width": 6.0, "length": 10.0, "name": "Shelf", "cabNumber": 42, "room": "Kitchen" }
                  ]
                }
              ]
            }
            """.trimIndent()

        // One thread repeatedly flips the raw CNC data between 1-part and 2-part versions and
        // refreshes the job; another thread repeatedly reads getCncSnapshot(). Every observed
        // snapshot must have a search index sized to match its own job data — if getCncSnapshot
        // ever read the static job data and its signature from two different generations, this
        // invariant would break under this interleaving.
        val iterations = 200
        val mismatches = java.util.concurrent.atomic.AtomicInteger(0)
        val writer = Thread {
            repeat(iterations) { i ->
                metadataFile.writeText(if (i % 2 == 0) twoPartJson else onePartJson)
                engine.refreshJobDeep(jobFolder)
            }
        }
        val reader = Thread {
            repeat(iterations * 5) {
                val snapshot = engine.getCncSnapshot(jobFolder) ?: return@repeat
                val partCount = snapshot.job.materials.sumOf { m -> m.metadata?.pages?.sumOf { it.parts.size } ?: 0 }
                if (partCount != snapshot.searchIndex.size) {
                    mismatches.incrementAndGet()
                }
            }
        }
        writer.start()
        reader.start()
        writer.join()
        reader.join()

        assertEquals(0, mismatches.get())
    }

    @Test
    fun resolvesReferenceDocsAndCabinetJump() {
        val baseDir = createTempBaseDir()
        seedJob(baseDir)
        val engine = FileBackedUnifiedMetadataEngine(baseDir.absolutePath, isDebugBuild = true)

        val assemblyRef = engine.findReferencePdfFilename(
            jobFolderName = jobFolder,
            query = UnifiedReferenceQuery(ReferenceDocType.ASSEMBLY)
        ).pdfFilename
        assertEquals("1234 - Assembly Sheets.pdf", assemblyRef)

        val jump = engine.resolveCabinetJump(jobFolder, "42")
        assertEquals(3, jump.assemblyPage)
        assertEquals(9, jump.plansPage)
    }

    @Test
    fun resolvesPullsPdfByFilenameOnly() {
        val baseDir = createTempBaseDir()
        seedJob(baseDir)
        File(baseDir, "$jobFolder/1234 - PULLS.pdf").writeText("pdf")
        val engine = FileBackedUnifiedMetadataEngine(baseDir.absolutePath, isDebugBuild = true)

        val pullsRef = engine.findReferencePdfFilename(
            jobFolderName = jobFolder,
            query = UnifiedReferenceQuery(ReferenceDocType.PULLS)
        ).pdfFilename
        assertEquals("1234 - PULLS.pdf", pullsRef)

        assertTrue(
            engine.hasReferenceDocument(
                jobFolderName = jobFolder,
                query = UnifiedReferenceQuery(ReferenceDocType.PULLS)
            ).exists
        )

        val catalog = engine.getPdfCatalog(jobFolder).catalog
        assertEquals("1234 - PULLS.pdf", catalog.pullsSheet?.pdfFilename)
    }

    @Test
    fun pullsSheetSurvivesCacheStaticJsonRoundTrip() {
        // resolvesPullsPdfByFilenameOnly (above) only exercises the fresh filesystem scan path
        // (buildPdfCatalog). Real tablets mostly read job data back out of the on-disk
        // cache_static.json instead, which goes through sanitizeStaticJobData() — a separate
        // JobPdfCatalog reconstruction that must also preserve pullsSheet.
        val baseDir = createTempBaseDir()
        val jobDir = File(baseDir, jobFolder).apply { mkdirs() }
        val metadataDir = File(jobDir, ".metadata").apply { mkdirs() }
        File(metadataDir, "deployment_gate.json").writeText("""{"deployed": true}""")
        File(metadataDir, "cache_static.json").writeText(
            """
            {
              "jobInfo": {
                "folderName": "$jobFolder",
                "jobNumber": "1234",
                "jobName": "Test Job"
              },
              "pdfCatalog": {
                "deliverySheet": null,
                "pullsSheet": { "pdfFilename": "1234 - PULLS.pdf", "label": "Pulls" },
                "managedDocs": [],
                "otherDocs": []
              }
            }
            """.trimIndent()
        )

        val engine = FileBackedUnifiedMetadataEngine(baseDir.absolutePath, isDebugBuild = true)

        val catalog = engine.getPdfCatalog(jobFolder).catalog
        assertEquals("1234 - PULLS.pdf", catalog.pullsSheet?.pdfFilename)
    }

    @Test
    fun hasReferenceDocumentIsFalseForPullsWhenFileMissing() {
        val baseDir = createTempBaseDir()
        seedJob(baseDir)
        val engine = FileBackedUnifiedMetadataEngine(baseDir.absolutePath, isDebugBuild = true)

        assertFalse(
            engine.hasReferenceDocument(
                jobFolderName = jobFolder,
                query = UnifiedReferenceQuery(ReferenceDocType.PULLS)
            ).exists
        )
    }

    @Test
    fun loadsLegacyCncPartsWhenGraphicAndBandingFieldsAreMissing() {
        val legacyJson = """
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

        val metadata = gson.fromJson(
            legacyJson,
            com.kkc.sheettracker.data.models.MaterialMetadata::class.java
        )
        val part = metadata.pages.first().parts.first()

        assertEquals(false, part.rotated)
        assertEquals(null, part.graphicPath)
        assertEquals(null, part.banding)
    }

    @Test
    fun appliesBoardStockOverlayAndTracksSignatures() {
        val baseDir = createTempBaseDir()
        seedJob(baseDir)
        val engine = FileBackedUnifiedMetadataEngine(baseDir.absolutePath, isDebugBuild = true)

        val plain = engine.getBoardStockRows(jobFolder, includeProgressOverlay = false).rows
        assertFalse(plain.isEmpty())

        val overlaid = engine.getBoardStockRows(
            jobFolderName = jobFolder,
            includeProgressOverlay = true,
            overlayLookup = UnifiedBoardStockOverlayLookup(
                rowProgressMap = mapOf(
                    ("FACE_FRAME_CUT_LIST" to "row-1") to com.kkc.sheettracker.data.models.HardwoodRowProgress(skipped = true)
                )
            )
        ).rows
        assertTrue(overlaid.size <= plain.size)

        val before = engine.getSignatures(jobFolder)
        val trackerFile = File(baseDir, "$jobFolder/CNC/.tracker/tablet-a.json")
        trackerFile.parentFile?.mkdirs()
        trackerFile.writeText("""{"tabletId":"tablet-a","actions":[]}""")
        val after = engine.getSignatures(jobFolder)
        assertTrue(after.trackerSignature != before.trackerSignature)
    }

    @Test
    fun boardStockRowsAggregateFromRowsWhenPdfHasNoTotalsBlock() {
        // CabinetVision sometimes exports hardwood cut lists with no TOTALS/RIPS footer at all
        // (confirmed on job 644d - SHOWROOM AND STORAGE CABS: zero totals blocks across every
        // hardwood doc). Ready Jobs Watcher's own board-stock builder (metadata_cache.py
        // build_board_stock_rows) aggregates from row-level qty/width/length data, not the PDF's
        // totals footer, so it always produces a rip list as long as rows exist. The local
        // (stale-cache fallback) recompute here must match that source, not `doc.totals`.
        val baseDir = createTempBaseDir()
        val jobDir = File(baseDir, jobFolder).apply { mkdirs() }
        val metadataDir = File(jobDir, ".metadata").apply { mkdirs() }
        File(metadataDir, "deployment_gate.json").writeText("""{"deployed": true}""")

        val hardwoodDir = File(metadataDir, "hardwoods").apply { mkdirs() }
        val hardwoodIndex = HardwoodCutlistIndex(
            documents = listOf(
                HardwoodDocumentIndex(
                    docType = HardwoodDocType.FACE_FRAME_CUT_LIST,
                    pdfFilename = "1234 - Face Frame Cut List.pdf",
                    rows = listOf(
                        HardwoodCutlistRow(
                            rowId = "row-1",
                            qty = 2,
                            material = "Alder Knotty",
                            description = "Top Rail",
                            width = "4",
                            length = "120",
                            cabinets = listOf("42")
                        )
                    )
                    // totals intentionally omitted (defaults to emptyList()), matching a PDF
                    // export with no TOTALS/RIPS footer.
                )
            )
        )
        File(hardwoodDir, "cutlist_index.json").writeText(gson.toJson(hardwoodIndex))

        val engine = FileBackedUnifiedMetadataEngine(baseDir.absolutePath, isDebugBuild = true)
        val rows = engine.getBoardStockRows(jobFolder, includeProgressOverlay = false).rows

        assertFalse("expected rip list rows built from row-level data, got none", rows.isEmpty())
        val row = rows.first()
        assertEquals("Alder Knotty", row.material)
        assertEquals(20.0, row.totalFeet, 0.0001) // (120in * 2) / 12 = 20 ft
        assertEquals(2, row.neededRips) // ceil(20 / 10)
    }

    @Test
    fun resolvesCabinetPartsWithOverlayCallbacks() {
        val baseDir = createTempBaseDir()
        seedJob(baseDir)
        val engine = FileBackedUnifiedMetadataEngine(baseDir.absolutePath, isDebugBuild = true)

        val parts = engine.resolveCabinetParts(
            jobFolderName = jobFolder,
            cabinetNumber = "42",
            overlayLookup = UnifiedPartOverlayLookup(
                sheetStatus = { _, _, _, _ -> SheetStatus.COMPLETE },
                isBadPart = { _, _, _, _, _ -> true },
                rowProgress = { _, _, _ -> com.kkc.sheettracker.data.models.HardwoodRowProgress(doneCount = 1) }
            )
        ).parts

        assertEquals("42", parts.cabinetNumber)
        assertTrue(parts.cncParts.isNotEmpty())
        assertTrue(parts.hardwoodRows.isNotEmpty())
    }

    @Test
    fun scanCncMaterialsAcceptsBaseJobNumberForLetteredJobFolder() {
        // Split/lettered jobs (e.g. "530a") sometimes get CNC remake sheets exported
        // under the base numeric job number ("530 - 001 REMAKE - ...") instead of the
        // lettered folder number. Those must still show up on the tablet.
        val baseDir = createTempBaseDir()
        val letteredJobFolder = "530a - Test Job"
        val jobDir = File(baseDir, letteredJobFolder).apply { mkdirs() }
        File(jobDir, ".metadata").apply { mkdirs() }
        File(jobDir, ".metadata/deployment_gate.json").writeText("""{"deployed": true}""")
        val cncDir = File(jobDir, "CNC").apply { mkdirs() }
        File(cncDir, "530a - Maple.pdf").writeText("pdf")
        File(cncDir, "530 - 001 REMAKE - Maple.pdf").writeText("pdf")

        val engine = FileBackedUnifiedMetadataEngine(
            basePath = baseDir.absolutePath,
            isDebugBuild = true,
            pdfPageCounter = { UnifiedPdfPageCountResult(1) }
        )

        val cnc = engine.getCncSnapshot(letteredJobFolder)
        val materialNames = cnc?.job?.materials?.map { it.materialName }?.toSet()
        assertEquals(setOf("Maple", "001 REMAKE - Maple"), materialNames)
    }

    @Test
    fun deepScanAllJobsReportsNewAndUpdatedJobs() {
        val baseDir = createTempBaseDir()
        seedJob(baseDir)
        val engine = FileBackedUnifiedMetadataEngine(
            basePath = baseDir.absolutePath,
            isDebugBuild = true,
            pdfPageCounter = { UnifiedPdfPageCountResult(8) }
        )

        // First deep scan: job not yet in the in-memory cache -> reported as changed.
        assertEquals(listOf(jobFolder), engine.deepScanAllJobs())

        // Nothing changed on disk -> deep scan finds no changes (cheap staleness pass).
        assertTrue(engine.deepScanAllJobs().isEmpty())

        // Update raw CNC metadata (larger file -> different signature) without touching any cache.
        File(baseDir, "$jobFolder/CNC/.metadata/1234 - White Melamine.json").writeText(
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
                    { "number": 1, "width": 12.0, "length": 24.0, "name": "Side Panel", "cabNumber": 42, "room": "Kitchen" },
                    { "number": 2, "width": 6.0, "length": 10.0, "name": "Shelf", "cabNumber": 42, "room": "Kitchen" }
                  ]
                }
              ]
            }
            """.trimIndent()
        )

        // Deep scan re-parses the newer file and reports the job as changed.
        assertEquals(listOf(jobFolder), engine.deepScanAllJobs())
    }

    @Test
    fun progressFromIndexReloadsWhenPublishedCacheIndexChanges() {
        val baseDir = createTempBaseDir()
        val jobDir = File(baseDir, jobFolder).apply { mkdirs() }
        val metadataDir = File(jobDir, ".metadata").apply { mkdirs() }
        File(metadataDir, "deployment_gate.json").writeText("""{"deployed": true}""")
        val indexFile = File(metadataDir, "cache_index.json")
        indexFile.writeText(
            """{"jobInfo":{"folderName":"$jobFolder","jobNumber":"1234","jobName":"Test Job"},"progressSummary":{"cnc":{"totalSheets":10,"done":1}}}"""
        )
        val firstTimestamp = indexFile.lastModified()
        val engine = FileBackedUnifiedMetadataEngine(baseDir.absolutePath, isDebugBuild = true)

        engine.listJobsFromCacheIndex()
        assertEquals(1, engine.getProgressFromIndex(jobFolder)?.cnc?.done)

        indexFile.writeText(
            """{"jobInfo":{"folderName":"$jobFolder","jobNumber":"1234","jobName":"Test Job"},"progressSummary":{"cnc":{"totalSheets":10,"done":2}}}"""
        )
        assertTrue(indexFile.setLastModified(firstTimestamp + 2_000))

        assertEquals(2, engine.getProgressFromIndex(jobFolder)?.cnc?.done)
    }

    @Test
    fun jobsListProjectionsDoNotReadJobBoardToBuildCards() {
        val baseDir = createTempBaseDir()
        val jobDir = File(baseDir, jobFolder).apply { mkdirs() }
        val metadataDir = File(jobDir, ".metadata").apply { mkdirs() }
        File(metadataDir, "deployment_gate.json").writeText("""{"deployed": true}""")
        File(metadataDir, "cache_index.json").writeText(
            """{"jobInfo":{"folderName":"$jobFolder","jobNumber":"1234","jobName":"Test Job"},"progressSummary":{"cnc":{"totalSheets":10},"hasDeliverySheet":false,"has3DAssets":false}}"""
        )
        File(baseDir, "job_board.json").writeText(
            """{"labels":[{"id":7,"name":"Urgent","color":"#FF0000"}],"jobs":[{"folder_name":"$jobFolder","label_ids":[7],"is_pending":1,"board_section":1}]}"""
        )
        val engine = FileBackedUnifiedMetadataEngine(baseDir.absolutePath, isDebugBuild = true)

        val indexJob = engine.listJobsFromCacheIndex().first.single()
        val legacyProjectionJob = engine.listJobsFromCacheOnly().first.single()

        listOf(indexJob, legacyProjectionJob).forEach { job ->
            assertTrue(job.labels.isEmpty())
            assertFalse(job.isPending)
            assertEquals(0, job.boardSection)
        }
    }

    @Test
    fun transientMissingIndexDoesNotErasePublishedJobProjection() {
        val baseDir = createTempBaseDir()
        val jobDir = File(baseDir, jobFolder).apply { mkdirs() }
        val metadataDir = File(jobDir, ".metadata").apply { mkdirs() }
        File(metadataDir, "deployment_gate.json").writeText("""{"deployed":true}""")
        File(metadataDir, "cache_index.json").writeText(
            """{"jobInfo":{"folderName":"$jobFolder","jobNumber":"1234","jobName":"Test Job"}}"""
        )
        val engine = FileBackedUnifiedMetadataEngine(baseDir.absolutePath, isDebugBuild = true)

        engine.listJobsFromCacheIndex()
        assertTrue(File(metadataDir, "cache_index.json").delete())
        engine.listJobsFromCacheIndex()

        assertEquals(listOf(jobFolder), engine.getCachedJobInfos().map { it.folderName })
    }

    private fun seedJob(baseDir: File) {
        val jobDir = File(baseDir, jobFolder).apply { mkdirs() }
        val sheetIndexDir = File(jobDir, ".metadata").apply { mkdirs() }
        File(sheetIndexDir, "deployment_gate.json").writeText("""{"deployed": true}""")
        File(jobDir, "1234 - Assembly Sheets.pdf").writeText("pdf")
        File(jobDir, "1234 - Plans & Elevations.pdf").writeText("pdf")

        val cncDir = File(jobDir, "CNC").apply { mkdirs() }
        val cncPdf = File(cncDir, "1234 - White Melamine.pdf")
        cncPdf.writeText("pdf")
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
                      "room": "Kitchen",
                      "rotated": true,
                      "graphicPath": ".metadata/parts/1234 - White Melamine_p001_part001.jpeg",
                      "banding": "2WD2LD"
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

    private fun createTempBaseDir(): File = Files.createTempDirectory("unified-metadata-engine-test").toFile()
}
