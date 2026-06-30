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
