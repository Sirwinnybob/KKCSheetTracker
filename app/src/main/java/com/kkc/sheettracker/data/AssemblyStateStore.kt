package com.kkc.sheettracker.data

import com.kkc.sheettracker.data.models.AssemblyBomEntry
import com.kkc.sheettracker.data.models.AssemblyCabinetParts
import com.kkc.sheettracker.data.models.AssemblyCncPart
import com.kkc.sheettracker.data.models.AssemblyCncSummary
import com.kkc.sheettracker.data.models.AssemblyHardwoodRow
import com.kkc.sheettracker.data.models.AssemblyHardwoodsSummary
import com.kkc.sheettracker.data.models.AssemblyJob
import com.kkc.sheettracker.data.models.HardwoodStatusCounts
import com.kkc.sheettracker.data.models.AssemblyJobCard
import com.kkc.sheettracker.data.models.AssemblySearchEntry
import com.kkc.sheettracker.data.models.AssemblySheetPart
import com.kkc.sheettracker.data.models.AssemblyVirtualSourceRef
import com.kkc.sheettracker.data.models.StatusCounts
import com.kkc.sheettracker.data.models.CabinetSheetIndex
import com.kkc.sheettracker.data.unified.UnifiedBoardStockOverlayLookup
import com.kkc.sheettracker.data.unified.UnifiedMetadataEngine
import com.kkc.sheettracker.data.unified.UnifiedPartOverlayLookup

class AssemblyStateStore(
    private val assemblyScanCoordinator: AssemblyScanCoordinator,
    private val scanCoordinator: ScanCoordinator,
    private val hardwoodsScanCoordinator: HardwoodsScanCoordinator,
    private val progressStore: ProgressStore,
    private val hardwoodsProgressStore: HardwoodsProgressStore,
    private val liveEngine: UnifiedMetadataEngine
) {
    private fun engine(): UnifiedMetadataEngine = liveEngine

    fun getJobs(): List<AssemblyJob> {
        return engine().getCachedJobInfos().mapNotNull { info ->
            engine().getAssemblySnapshot(info.folderName)?.job
        }
    }

    fun getCabinetSheetIndex(jobFolderName: String): CabinetSheetIndex? {
        return engine().getCabinetSheetIndex(jobFolderName).index
    }

    /**
     * @param resolveCounts When false, skips the progress-tracker file reads (CNC/hardwoods
     * counts) and returns cards with zero summaries. Used for an instant placeholder pass so the
     * job list renders immediately instead of staying empty until the tracker I/O resolves.
     */
    fun deriveJobCards(resolveCounts: Boolean = true): List<AssemblyJobCard> {
        val assemblyJobs = getJobs()

        return assemblyJobs.map { job ->
            val cncCounts = if (resolveCounts) {
                engine().getProgressFromIndex(job.folderName)?.cnc?.let { cnc ->
                    StatusCounts(total = cnc.totalSheets, complete = cnc.done, bad = cnc.bad, skipped = cnc.skipped)
                }
            } else {
                null
            }

            val hardwoodCounts = if (resolveCounts) {
                engine().getProgressFromIndex(job.folderName)?.hardwoods?.let { hw ->
                    HardwoodStatusCounts(totalPieces = hw.totalPieces, donePieces = hw.donePieces, badPieces = hw.badPieces, skippedPieces = hw.skippedPieces)
                }
            } else {
                null
            }

            AssemblyJobCard(
                folderName = job.folderName,
                jobNumber = job.jobNumber,
                jobName = job.jobName,
                cncSummary = if (cncCounts == null) {
                    AssemblyCncSummary()
                } else {
                    AssemblyCncSummary(
                        totalSheets = cncCounts.total,
                        completedSheets = cncCounts.complete,
                        skippedSheets = cncCounts.skipped,
                        badPartsSheets = cncCounts.bad
                    )
                },
                hardwoodsSummary = if (hardwoodCounts == null) {
                    AssemblyHardwoodsSummary()
                } else {
                    AssemblyHardwoodsSummary(
                        totalPieces = hardwoodCounts.totalPieces,
                        donePieces = hardwoodCounts.donePieces,
                        badPieces = hardwoodCounts.badPieces,
                        skippedPieces = hardwoodCounts.skippedPieces
                    )
                },
                hasBothModes = engine().getProgressFromIndex(job.folderName)?.let { it.cnc != null && it.hardwoods != null } ?: false,
                hiddenFromProduction = job.hiddenFromProduction,
                lineupPosition = job.lineupPosition,
                labels = job.labels,
                isPending = job.isPending,
                boardSection = job.boardSection
            )
        }
    }

    fun getCabinetJumpPages(jobFolderName: String, cabinetNumber: String): Pair<Int?, Int?> {
        val jump = engine().resolveCabinetJump(jobFolderName, cabinetNumber)
        return jump.assemblyPage to jump.plansPage
    }

    fun getCabinetContext(jobFolderName: String, cabinetNumber: String): String {
        return engine().resolveCabinetContext(jobFolderName, cabinetNumber).contextLine
    }

    fun resolveAssemblyPageSource(jobFolderName: String, assemblyPage: Int): AssemblyVirtualSourceRef? {
        if (assemblyPage <= 0) return null
        val index = getCabinetSheetIndex(jobFolderName) ?: return null
        val virtual = index.documents.assembly.virtualCombined
        val virtualRef = virtual?.virtualPageToSource?.get(assemblyPage.toString())
        if (virtualRef != null) return virtualRef
        val fallbackPdf = index.documents.assembly.pdfFilename
        if (fallbackPdf.isBlank()) return null
        return AssemblyVirtualSourceRef(
            variant = "BASE",
            pdfFilename = fallbackPdf,
            page = assemblyPage,
            cabinet = null
        )
    }

    fun resolveVirtualAssemblyPage(jobFolderName: String, sourcePdfFilename: String, sourcePage: Int): Int? {
        if (sourcePdfFilename.isBlank() || sourcePage <= 0) return null
        val index = getCabinetSheetIndex(jobFolderName) ?: return null
        val virtualMap = index.documents.assembly.virtualCombined?.virtualPageToSource ?: return null
        val match = virtualMap.entries.firstOrNull { (_, source) ->
            source.pdfFilename.equals(sourcePdfFilename, ignoreCase = true) && source.page == sourcePage
        } ?: return null
        return match.key.toIntOrNull()
    }

    fun deriveCabinetParts(jobFolderName: String, cabinetNumber: String): AssemblyCabinetParts {
        val parts = engine().resolveCabinetParts(
            jobFolderName = jobFolderName,
            cabinetNumber = cabinetNumber,
            overlayLookup = UnifiedPartOverlayLookup(
                sheetStatus = { job, pdf, page, fp ->
                    progressStore.getSheetStatus(
                        jobFolderName = job,
                        pdfFilename = pdf,
                        page = page,
                        fileFingerprint = fp
                    )
                },
                isBadPart = { job, pdf, page, fp, part ->
                    progressStore.isPartBad(
                        jobFolderName = job,
                        pdfFilename = pdf,
                        page = page,
                        fileFingerprint = fp,
                        partNumber = part
                    )
                },
                rowProgress = { job, docType, rowId ->
                    hardwoodsProgressStore.getRowProgress(job, docType, rowId)
                }
            )
        )
        return parts.parts
    }

    fun deriveSearchIndex(): List<AssemblySearchEntry> {
        val jobs = getJobs()
        val out = mutableListOf<AssemblySearchEntry>()

        jobs.forEach { job ->
            val index = job.cabinetSheetIndex ?: return@forEach
            val assemblyDoc = index.documents.assembly
            val plansDoc = index.documents.plansElevations
            val assemblyCabPages = assemblyCabinetToPages(index)
            val assemblyDetails = assemblyPageDetails(index)

            val allCabinets = linkedSetOf<String>()
            allCabinets.addAll(assemblyCabPages.keys)
            allCabinets.addAll(plansDoc.cabinetToPages.keys)

            allCabinets.forEach { cabinet ->
                val assemblyPage = assemblyCabPages[cabinet]?.firstOrNull()
                val plansPage = plansDoc.cabinetToPages[cabinet]?.firstOrNull()
                val detail = assemblyPage?.let { assemblyDetails[it.toString()] }

                out += AssemblySearchEntry(
                    jobFolderName = job.folderName,
                    jobNumber = job.jobNumber,
                    jobName = job.jobName,
                    cabinetNumber = cabinet,
                    room = detail?.room,
                    wall = detail?.wall,
                    assemblyPage = assemblyPage,
                    plansPage = plansPage,
                    description = "",
                    material = "",
                    sectionType = ""
                )
            }

            assemblyDetails.forEach { (_, detail) ->
                val assemblyPage = detail.cabinets.firstOrNull()?.let { assemblyCabPages[it]?.firstOrNull() }
                detail.cabinets.forEach { cabinet ->
                    val plansPage = plansDoc.cabinetToPages[cabinet]?.firstOrNull()
                    detail.parts.forEach { part ->
                        out += AssemblySearchEntry(
                            jobFolderName = job.folderName,
                            jobNumber = job.jobNumber,
                            jobName = job.jobName,
                            cabinetNumber = cabinet,
                            room = detail.room,
                            wall = detail.wall,
                            assemblyPage = assemblyPage,
                            plansPage = plansPage,
                            description = part.description,
                            material = part.material,
                            sectionType = part.sectionType
                        )
                    }
                }
            }
        }

        return out
    }

    private fun normalizeKey(value: String): String {
        return value
            .trim()
            .lowercase()
            .replace(Regex("\\s+"), " ")
    }

    private fun assemblyCabinetToPages(index: CabinetSheetIndex?): Map<String, List<Int>> {
        if (index == null) return emptyMap()
        val virtual = index.documents.assembly.virtualCombined?.cabinetToPages
        if (!virtual.isNullOrEmpty()) return virtual
        return index.documents.assembly.cabinetToPages
    }

    private fun assemblyPageDetails(index: CabinetSheetIndex?): Map<String, com.kkc.sheettracker.data.models.CabinetPageDetail> {
        if (index == null) return emptyMap()
        val virtual = index.documents.assembly.virtualCombined?.pageDetails
        if (!virtual.isNullOrEmpty()) return virtual
        return index.documents.assembly.pageDetails
    }
}
