package com.kkc.sheettracker.data

import com.kkc.sheettracker.data.models.AssemblyBomEntry
import com.kkc.sheettracker.data.models.AssemblyCabinetParts
import com.kkc.sheettracker.data.models.AssemblyCncPart
import com.kkc.sheettracker.data.models.AssemblyCncSummary
import com.kkc.sheettracker.data.models.AssemblyHardwoodRow
import com.kkc.sheettracker.data.models.AssemblyHardwoodsSummary
import com.kkc.sheettracker.data.models.AssemblyJob
import com.kkc.sheettracker.data.models.AssemblyJobCard
import com.kkc.sheettracker.data.models.AssemblySearchEntry
import com.kkc.sheettracker.data.models.AssemblySheetPart
import com.kkc.sheettracker.data.models.AssemblyVirtualSourceRef
import com.kkc.sheettracker.data.models.CabinetSheetIndex

class AssemblyStateStore(
    private val assemblyScanCoordinator: AssemblyScanCoordinator,
    private val scanCoordinator: ScanCoordinator,
    private val hardwoodsScanCoordinator: HardwoodsScanCoordinator,
    private val progressStore: ProgressStore,
    private val hardwoodsProgressStore: HardwoodsProgressStore
) {
    fun getJobs(): List<AssemblyJob> {
        return assemblyScanCoordinator.state.value.snapshot.jobs
    }

    fun getCabinetSheetIndex(jobFolderName: String): CabinetSheetIndex? {
        return getJobs().firstOrNull { it.folderName == jobFolderName }?.cabinetSheetIndex
    }

    fun deriveJobCards(): List<AssemblyJobCard> {
        val assemblyJobs = getJobs()
        val cncJobsByFolder = scanCoordinator.state.value.snapshot.jobs.associateBy { it.folderName }
        val hardwoodJobsByFolder = hardwoodsScanCoordinator.state.value.snapshot.jobs.associateBy { it.folderName }

        return assemblyJobs.map { job ->
            val cncJob = cncJobsByFolder[job.folderName]
            val hardwoodJob = hardwoodJobsByFolder[job.folderName]

            val cncCounts = if (cncJob != null) {
                progressStore.getJobStatusCounts(job.folderName, cncJob.materials)
            } else {
                null
            }

            val hardwoodCounts = if (hardwoodJob != null) {
                hardwoodsProgressStore.summarizeJob(hardwoodJob).counts
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
                hasBothModes = cncJob != null && hardwoodJob != null,
                hiddenFromProduction = job.hiddenFromProduction
            )
        }
    }

    fun getCabinetJumpPages(jobFolderName: String, cabinetNumber: String): Pair<Int?, Int?> {
        val index = getCabinetSheetIndex(jobFolderName) ?: return null to null
        val normalized = cabinetNumber.trim()
        val assemblyPage = assemblyCabinetToPages(index)[normalized]?.firstOrNull()
        val plansPage = index.documents.plansElevations.cabinetToPages[normalized]?.firstOrNull()
        return assemblyPage to plansPage
    }

    fun getCabinetContext(jobFolderName: String, cabinetNumber: String): String {
        val index = getCabinetSheetIndex(jobFolderName) ?: return ""
        val page = assemblyCabinetToPages(index)[cabinetNumber.trim()]?.firstOrNull() ?: return ""
        val detail = assemblyPageDetails(index)[page.toString()] ?: return ""
        val room = detail.room?.trim().orEmpty()
        val wall = detail.wall?.trim().orEmpty()
        return listOf(room, wall).filter { it.isNotBlank() }.joinToString(" - ")
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
        val normalizedCab = cabinetNumber.trim()
        val index = getCabinetSheetIndex(jobFolderName)
        val assemblyPages = assemblyCabinetToPages(index)[normalizedCab].orEmpty()
        val assemblyPageDetails = assemblyPageDetails(index)

        val sheetParts = assemblyPages.flatMap { page ->
            assemblyPageDetails[page.toString()]?.parts.orEmpty()
        }

        val cncJob = scanCoordinator.state.value.snapshot.jobs.firstOrNull { it.folderName == jobFolderName }
        val cncParts = mutableListOf<AssemblyCncPart>()
        cncJob?.materials.orEmpty().forEach { material ->
            material.metadata?.pages.orEmpty().forEach { page ->
                if (page.hiddenInApp || page.trackingExcluded || page.isPartListContinuation) return@forEach
                page.parts
                    .filter { it.cabNumber.toString() == normalizedCab }
                    .forEach { part ->
                        val status = progressStore.getSheetStatus(
                            jobFolderName = jobFolderName,
                            pdfFilename = material.pdfFilename,
                            page = page.pageNumber,
                            fileFingerprint = material.fileFingerprint
                        )
                        val isBad = progressStore.isPartBad(
                            jobFolderName = jobFolderName,
                            pdfFilename = material.pdfFilename,
                            page = page.pageNumber,
                            fileFingerprint = material.fileFingerprint,
                            partNumber = part.number
                        )
                        cncParts += AssemblyCncPart(
                            materialName = material.materialName,
                            pdfFilename = material.pdfFilename,
                            pageNumber = page.pageNumber,
                            partNumber = part.number,
                            partName = part.name,
                            width = part.width,
                            length = part.length,
                            room = part.room,
                            sheetStatus = status,
                            isBadPart = isBad
                        )
                    }
            }
        }

        val hardwoodJob = hardwoodsScanCoordinator.state.value.snapshot.jobs.firstOrNull { it.folderName == jobFolderName }
        val hardwoodRows = mutableListOf<AssemblyHardwoodRow>()
        hardwoodJob?.index?.documents.orEmpty().forEach { doc ->
            doc.rows
                .filter { row -> row.cabinets.any { it.trim() == normalizedCab } }
                .forEach { row ->
                    val progress = hardwoodsProgressStore.getRowProgress(jobFolderName, doc.docType.name, row.rowId)
                    hardwoodRows += AssemblyHardwoodRow(
                        docType = doc.docType,
                        description = row.description,
                        material = row.material,
                        qty = row.qty,
                        width = row.width,
                        length = row.length,
                        doneCount = progress.doneCount,
                        badCount = progress.badCount,
                        skipped = progress.skipped
                    )
                }
        }

        if (sheetParts.isEmpty()) {
            return AssemblyCabinetParts(
                cabinetNumber = normalizedCab,
                bom = emptyList(),
                cncParts = cncParts,
                hardwoodRows = hardwoodRows
            )
        }

        val cncByDesc = cncParts.groupBy { normalizeKey(it.partName) }
        val hwByDesc = hardwoodRows.groupBy { normalizeKey(it.description) }
        val bom = sheetParts.map { part ->
            val key = normalizeKey(part.description)
            AssemblyBomEntry(
                part = part,
                cncParts = cncByDesc[key].orEmpty(),
                hardwoodRows = hwByDesc[key].orEmpty()
            )
        }

        return AssemblyCabinetParts(
            cabinetNumber = normalizedCab,
            bom = bom,
            cncParts = cncParts,
            hardwoodRows = hardwoodRows
        )
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
