package com.kkc.sheettracker.data

import org.junit.Test
import java.io.File
import com.kkc.sheettracker.data.models.HardwoodCutlistRow
import com.kkc.sheettracker.data.models.HardwoodDocType
import com.kkc.sheettracker.data.models.Job
import com.kkc.sheettracker.data.models.Part

class BatchSyncTest {

    @Test
    fun runBatchSyncOnReadyJobs() {
        val readyJobsDir = File("Y:\\Ready Jobs")
        if (!readyJobsDir.exists() || !readyJobsDir.isDirectory) {
            println("======================================================================")
            println("Y:\\Ready Jobs does not exist or is not a directory.")
            println("Skipping batch sync on this machine.")
            println("======================================================================")
            return
        }

        println("======================================================================")
        println("STARTING BATCH SYNC ON READY JOBS IN: ${readyJobsDir.absolutePath}")
        println("======================================================================")

        val jobRepository = JobRepository(readyJobsDir, isDebugBuild = false)
        val progressStore = ProgressStore(
            baseDir = readyJobsDir,
            tabletId = "batch-sync-worker",
            localStateDir = File(System.getProperty("java.io.tmpdir"), "batch-sync-state"),
            readOnly = false
        )
        val hardwoodsRepository = HardwoodsRepository(readyJobsDir)
        val hardwoodsProgressStore = HardwoodsProgressStore(
            baseDir = readyJobsDir,
            tabletId = "batch-sync-worker",
            readOnly = false
        )

        val scannedJobs = jobRepository.scanJobs()
        println("Discovered ${scannedJobs.size} total CNC jobs.")

        var syncedJobsCount = 0
        var skippedJobsCount = 0
        var failedJobsCount = 0

        scannedJobs.forEach { job ->
            val jobFolderName = job.folderName
            val hardwoodsDir = File(readyJobsDir, "$jobFolderName/.metadata/hardwoods")
            
            if (hardwoodsDir.exists() && hardwoodsDir.isDirectory) {
                print(" -> Syncing CNC progress to Hardwoods for job: $jobFolderName... ")
                try {
                    val startActionCount = hardwoodsProgressStore.getRowProgressMap(jobFolderName).values.sumOf { it.doneCount }
                    
                    syncCncToHardwoods(
                        jobFolderName = jobFolderName,
                        jobRepository = jobRepository,
                        progressStore = progressStore,
                        hardwoodsRepository = hardwoodsRepository,
                        hardwoodsProgressStore = hardwoodsProgressStore
                    )
                    
                    val endActionCount = hardwoodsProgressStore.getRowProgressMap(jobFolderName).values.sumOf { it.doneCount }
                    val delta = endActionCount - startActionCount
                    
                    if (delta != 0) {
                        println("DONE (Updated door panel counts by $delta)")
                    } else {
                        println("DONE (Already in sync)")
                    }
                    syncedJobsCount++
                } catch (e: Exception) {
                    println("FAILED")
                    System.err.println("Error syncing job '$jobFolderName': ${e.message}")
                    e.printStackTrace()
                    failedJobsCount++
                }
            } else {
                println(" -> Skipping job: $jobFolderName (no hardwoods metadata folder)")
                skippedJobsCount++
            }
        }

        println("======================================================================")
        println("BATCH SYNC SUMMARY:")
        println(" - Scanned Jobs: ${scannedJobs.size}")
        println(" - Synced Jobs: $syncedJobsCount")
        println(" - Skipped Jobs: $skippedJobsCount")
        println(" - Failed Jobs: $failedJobsCount")
        println("======================================================================")

        // Flush all pending async disk writes before the JVM exits.
        println("Flushing pending writes to disk...")
        hardwoodsProgressStore.awaitPendingWrites()
        println("All writes flushed.")
    }

    @Test
    fun inspect568() {
        val readyJobsDir = java.io.File("Y:\\Ready Jobs")
        if (!readyJobsDir.exists()) return
        val jobFolderName = "568 - H2 611 E 15TH"
        val jobRepository = JobRepository(readyJobsDir, isDebugBuild = false)
        val progressStore = ProgressStore(
            baseDir = readyJobsDir,
            tabletId = "batch-sync-worker",
            localStateDir = java.io.File(System.getProperty("java.io.tmpdir"), "batch-sync-state"),
            readOnly = false
        )
        val hardwoodsRepository = HardwoodsRepository(readyJobsDir)
        
        val cncJob = jobRepository.scanJobs().firstOrNull { it.folderName == jobFolderName }
        if (cncJob == null) {
            println("CNC Job not found for $jobFolderName")
            return
        }
        
        println("CNC Job Materials:")
        cncJob.materials.forEach { mat ->
            println(" - ${mat.materialName} (${mat.pageCount} sheets)")
            val pages = mat.metadata?.pages.orEmpty()
            pages.forEach { page ->
                val isCompleted = progressStore.isSheetComplete(jobFolderName, mat.pdfFilename, page.pageNumber, mat.fileFingerprint)
                println("   * Page ${page.pageNumber}: complete=$isCompleted, parts=${page.parts.size}")
                page.parts.forEach { part ->
                    println("     + Part: cab=${part.cabNumber}, name=${part.name}, w=${part.width}, l=${part.length}")
                }
            }
        }
        
        val hardwoodsIndex = hardwoodsRepository.loadHardwoodsIndex(jobFolderName)
        if (hardwoodsIndex == null) {
            println("Hardwoods index not found for $jobFolderName")
            return
        }

        // Gather all CNC parts
        data class CncPartState(
            val part: Part,
            val isCompleted: Boolean
        )
        val allCncParts = mutableListOf<CncPartState>()
        cncJob.materials.forEach { material ->
            val pageCount = material.pageCount
            val pages = material.metadata?.pages.orEmpty()
            for (pageNum in 1..pageCount) {
                val isCompleted = progressStore.isSheetComplete(jobFolderName, material.pdfFilename, pageNum, material.fileFingerprint)
                val pageMeta = pages.firstOrNull { it.pageNumber == pageNum }
                if (pageMeta != null) {
                    pageMeta.parts.forEach { part ->
                        allCncParts.add(CncPartState(part, isCompleted))
                    }
                }
            }
        }
        println("Gathered ${allCncParts.size} total CNC parts.")

        println("Hardwoods Documents and Matches:")
        hardwoodsIndex.documents.forEach { doc ->
            if (doc.docType != HardwoodDocType.FACE_FRAME_CUT_LIST) {
                println(" - Doc: ${doc.docType}, rows=${doc.rows.size}")
                doc.rows.forEach { row ->
                    val matching = allCncParts.filter { preciseMatches(row, it.part) }
                    if (matching.isNotEmpty()) {
                        val completedCount = matching.count { it.isCompleted }
                        println("   * MATCHED Row: id=${row.rowId}, description=${row.description}, qty=${row.qty}, cabs=${row.cabinets}")
                        println("     -> ${matching.size} matching CNC parts found (completed: $completedCount)")
                        matching.forEach { match ->
                            println("       + CNC Part: cab=${match.part.cabNumber}, name=${match.part.name}, w=${match.part.width}, l=${match.part.length}, complete=${match.isCompleted}")
                        }
                    } else {
                        // Let's see if there is any partial name/size match to see if it was close
                        val closeMatches = allCncParts.filter { partState ->
                            val cabMatch = row.cabinets.any { cabStr -> cabStr.trim() == partState.part.cabNumber.toString() }
                            cabMatch
                        }
                        if (closeMatches.isNotEmpty()) {
                            println("   * Close Cab Match Row: id=${row.rowId}, description=${row.description}, w=${row.width}, l=${row.length}, cabs=${row.cabinets}")
                            closeMatches.forEach { match ->
                                println("       ~ Close CNC Part: cab=${match.part.cabNumber}, name=${match.part.name}, w=${match.part.width}, l=${match.part.length}")
                            }
                        }
                        if (row.rowId == "DOOR_LIST:2:5:b2f5f60400b6f738") {
                            println("===== DIAGNOSTIC TRACE FOR DOOR_LIST:2:5:b2f5f60400b6f738 =====")
                            closeMatches.forEach { match ->
                                val part = match.part
                                val cabMatch = row.cabinets.any { cabStr ->
                                    val cabInt = cabStr.toIntOrNull()
                                    if (cabInt != null) {
                                        cabInt == part.cabNumber
                                    } else {
                                        cabStr.trim() == part.cabNumber.toString()
                                    }
                                }
                                val normRow = row.description.trim().replace(Regex("""\s+"""), " ").uppercase(java.util.Locale.US)
                                val normPart = part.name.trim().replace(Regex("""\s+"""), " ").uppercase(java.util.Locale.US)
                                val nameMatch = normRow == normPart || 
                                                normRow.contains(normPart) || 
                                                normPart.contains(normRow) ||
                                                (normRow.contains("PANEL") && normPart.contains("PANEL")) ||
                                                (normRow.contains("SLAB") && normPart.contains("SLAB"))
                                
                                val rowWidth = parseDimension(row.width)
                                val rowLength = parseDimension(row.length)
                                val stileRail = parseStileRailWidths(row.description)
                                val doubleG = 0.936
                                
                                val expW1 = rowWidth?.let { it - (2 * stileRail.stileWidth - doubleG) }
                                val expL1 = rowLength?.let { it - (2 * stileRail.railWidth - doubleG) }
                                
                                val widthDiff1 = expW1?.let { kotlin.math.abs(it - part.width) }
                                val lengthDiff1 = expL1?.let { kotlin.math.abs(it - part.length) }
                                
                                println("Part Name: '${part.name}', cabMatch=$cabMatch, nameMatch=$nameMatch, rowWidth=$rowWidth, rowLength=$rowLength, stile=${stileRail.stileWidth}, rail=${stileRail.railWidth}")
                                println("  expW1=$expW1, part.width=${part.width}, diffW=$widthDiff1")
                                println("  expL1=$expL1, part.length=${part.length}, diffL=$lengthDiff1")
                                println("  preciseMatches result = ${preciseMatches(row, part)}")
                            }
                            println("==================================================================")
                        }
                    }
                }
            }
        }

    }

    @Test
    fun testPreciseMatchDebug() {
        val row = HardwoodCutlistRow(
            rowId = "DOOR_LIST:2:5:b2f5f60400b6f738",
            qty = 1,
            description = "2 1/4\" Flat Panel (Paint Grade MDF)",
            width = "14.875",
            length = "24.125",
            cabinets = listOf("10"),
            material = "2 1/4\" Flat Panel (Paint Grade MDF)",
            rowOrdinal = 5,
            page = 2
        )
        val part = Part(
            cabNumber = 10,
            name = "Door Flat Panel 1B",
            width = 11.311,
            length = 20.561
        )
        println("=== DEBUG PRECISE MATCH ===")
        val normRow = row.description.trim().replace(Regex("""\s+"""), " ").uppercase(java.util.Locale.US)
        val normPart = part.name.trim().replace(Regex("""\s+"""), " ").uppercase(java.util.Locale.US)
        println("normRow: '$normRow'")
        println("normPart: '$normPart'")
        val nameMatch = normRow == normPart || 
                        normRow.contains(normPart) || 
                        normPart.contains(normRow) ||
                        (normRow.contains("PANEL") && normPart.contains("PANEL")) ||
                        (normRow.contains("SLAB") && normPart.contains("SLAB"))
        println("nameMatch: $nameMatch")

        val widths = parseStileRailWidths(row.description)
        println("widths: stile=${widths.stileWidth}, rail=${widths.railWidth}")
        
        val doubleG = 0.936
        val expW1 = parseDimension(row.width)!! - (2 * widths.stileWidth - doubleG)
        val expL1 = parseDimension(row.length)!! - (2 * widths.railWidth - doubleG)
        println("expW1: $expW1, part.width: ${part.width}, diff: ${kotlin.math.abs(expW1 - part.width)}")
        println("expL1: $expL1, part.length: ${part.length}, diff: ${kotlin.math.abs(expL1 - part.length)}")
        
        val matches = preciseMatches(row, part)
        println("preciseMatches returned: $matches")
    }
}


