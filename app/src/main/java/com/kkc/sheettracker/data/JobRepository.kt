package com.kkc.sheettracker.data

import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.google.gson.Gson
import com.kkc.sheettracker.data.models.*
import java.io.File
import java.util.Locale

class JobRepository(private var baseDir: File) {

    private val gson = Gson()
    private val cacheLock = Any()
    @Volatile private var cachedJobs: List<Job>? = null
    @Volatile private var cachedSearchIndex: List<PartSearchEntry>? = null
    @Volatile private var scanCoordinator: ScanCoordinator? = null

    internal fun attachScanCoordinator(coordinator: ScanCoordinator) {
        scanCoordinator = coordinator
    }

    fun updateBaseDir(newBaseDir: File) {
        baseDir = newBaseDir
        invalidateCache()
    }

    fun invalidateCache() {
        synchronized(cacheLock) {
            cachedJobs = null
            cachedSearchIndex = null
        }
    }

    fun scanJobs(forceRefresh: Boolean = false): List<Job> {
        scanCoordinator?.let { coordinator ->
            if (forceRefresh) {
                coordinator.refresh(RefreshReason.USER_REFRESH, force = true)
            }
            return coordinator.currentSnapshotJobs()
        }

        if (!forceRefresh) {
            cachedJobs?.let { return it }
        } else {
            invalidateCache()
        }

        if (!baseDir.exists() || !baseDir.isDirectory) return emptyList()

        val scanned = baseDir.listFiles()
            ?.filter { it.isDirectory && File(it, "CNC").isDirectory }
            ?.mapNotNull { jobDir ->
                val match = Regex("""^(\d+)\s*-\s*(.+)$""").find(jobDir.name)
                if (match != null) {
                    val jobNumber = match.groupValues[1]
                    val jobName = match.groupValues[2].trim()
                    val materials = scanMaterials(File(jobDir, "CNC"), jobNumber)
                    Job(jobDir.name, jobNumber, jobName, materials)
                } else null
            }
            ?.sortedByDescending { it.jobNumber.toIntOrNull() ?: 0 }
            ?: emptyList()

        synchronized(cacheLock) {
            cachedJobs = scanned
        }
        return scanned
    }

    fun buildSearchIndex(forceRefresh: Boolean = false): List<PartSearchEntry> {
        scanCoordinator?.let { coordinator ->
            if (forceRefresh) {
                coordinator.refresh(RefreshReason.USER_REFRESH, force = true)
            }
            return coordinator.currentSearchIndex()
        }

        if (!forceRefresh) {
            cachedSearchIndex?.let { return it }
        }

        val jobs = scanJobs(forceRefresh = forceRefresh)
        val index = mutableListOf<PartSearchEntry>()
        for (job in jobs) {
            for (mat in job.materials) {
                val pages = mat.metadata?.pages ?: continue
                for (page in pages) {
                    if (page.hiddenInApp || page.trackingExcluded || page.isPartListContinuation) continue
                    for (part in page.parts) {
                        index.add(
                            PartSearchEntry(
                                jobFolderName = job.folderName,
                                jobNumber = job.jobNumber,
                                materialName = mat.materialName,
                                pdfFilename = mat.pdfFilename,
                                pageNumber = page.pageNumber,
                                partNumber = part.number,
                                partName = part.name,
                                room = part.room,
                                cabNumber = part.cabNumber
                            )
                        )
                    }
                }
            }
        }
        synchronized(cacheLock) {
            cachedSearchIndex = index
        }
        return index
    }

    private fun scanMaterials(cncDir: File, jobNumber: String): List<Material> {
        if (!cncDir.exists()) return emptyList()

        return cncDir.listFiles()
            ?.filter { it.extension == "pdf" && "ALL SHEETS" !in it.name && it.name.startsWith("$jobNumber - ") }
            ?.map { pdfFile ->
                val materialName = pdfFile.nameWithoutExtension
                    .removePrefix("$jobNumber - ")
                val pageCount = countPdfPages(pdfFile)
                val metadata = loadMetadata(cncDir, pdfFile.name)
                val fingerprint = "${pdfFile.length()}_${pdfFile.lastModified()}"
                Material(
                    pdfFilename = pdfFile.name,
                    materialName = materialName,
                    pageCount = pageCount,
                    fileFingerprint = fingerprint,
                    metadata = metadata
                )
            }
            ?.sortedBy { it.materialName }
            ?: emptyList()
    }

    private fun countPdfPages(pdfFile: File): Int {
        return try {
            val fd = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = PdfRenderer(fd)
            val count = renderer.pageCount
            renderer.close()
            fd.close()
            count
        } catch (e: Exception) {
            0
        }
    }

    private fun loadMetadata(cncDir: File, pdfFilename: String): MaterialMetadata? {
        val jsonFilename = pdfFilename.removeSuffix(".pdf") + ".json"
        val metadataFile = File(cncDir, ".metadata/$jsonFilename")
        if (!metadataFile.exists()) return null

        return try {
            gson.fromJson(metadataFile.readText(), MaterialMetadata::class.java)
        } catch (e: Exception) {
            null
        }
    }

    fun getPdfFile(jobFolderName: String, pdfFilename: String): File {
        return File(baseDir, "$jobFolderName/CNC/$pdfFilename")
    }

    fun getJobRootPdfFile(
        jobFolderName: String,
        pdfFilename: String,
        preferDarkMode: Boolean
    ): File? {
        val jobDir = File(baseDir, jobFolderName)
        val light = File(jobDir, pdfFilename)
        val dark = File(jobDir, "DARK MODE/$pdfFilename")
        return when {
            preferDarkMode && dark.exists() -> dark
            light.exists() -> light
            dark.exists() -> dark
            else -> null
        }
    }

    fun getCabinetSheetIndex(jobFolderName: String): CabinetSheetIndex? {
        val indexFile = File(baseDir, "$jobFolderName/.metadata/cabinet_sheet_index.json")
        if (!indexFile.exists() || !indexFile.isFile) return null
        return try {
            gson.fromJson(indexFile.readText(), CabinetSheetIndex::class.java)
        } catch (_: Exception) {
            null
        }
    }

    fun findReferencePdfFilename(jobFolderName: String, docType: ReferenceDocType): String? {
        if (docType == ReferenceDocType.DELIVERY_SHEETS) {
            return getJobPdfCatalog(jobFolderName).deliverySheet?.pdfFilename
        }

        val jobDir = File(baseDir, jobFolderName)
        if (!jobDir.isDirectory) return null
        val target = when (docType) {
            ReferenceDocType.ASSEMBLY -> "assembly sheets"
            ReferenceDocType.PLANS_ELEVATIONS -> "plans & elevations"
            ReferenceDocType.DELIVERY_SHEETS -> "delivery sheets"
        }

        fun findIn(dir: File): String? {
            val files = dir.listFiles() ?: return null
            return files.firstOrNull { file ->
                file.isFile &&
                    file.extension.lowercase(Locale.US) == "pdf" &&
                    file.name.lowercase(Locale.US).contains(target)
            }?.name
        }

        return findIn(jobDir) ?: findIn(File(jobDir, "DARK MODE"))
    }

    fun getJobPdfCatalog(jobFolderName: String): JobPdfCatalog {
        val jobDir = File(baseDir, jobFolderName)
        if (!jobDir.isDirectory) return JobPdfCatalog()

        val rootPdfs = jobDir.listFiles()
            ?.asSequence()
            ?.filter { file ->
                file.isFile && file.extension.lowercase(Locale.US) == "pdf"
            }
            ?.sortedBy { it.name.lowercase(Locale.US) }
            ?.toList()
            .orEmpty()

        val managed = mutableListOf<JobPdfRef>()
        val other = mutableListOf<JobPdfRef>()
        var deliverySheet: JobPdfRef? = null

        rootPdfs.forEach { file ->
            val lower = file.name.lowercase(Locale.US)
            val managedLabel = when {
                isDeliverySheetPdf(lower) -> "Delivery Sheets"
                isAssemblySheetPdf(lower) -> "Assembly Sheets"
                isPlansElevationsPdf(lower) -> "Plans & Elevations"
                isDoorListPdf(lower) -> "Door List"
                isCutListPdf(lower) -> "Cut List"
                else -> null
            }

            if (managedLabel != null) {
                val ref = JobPdfRef(pdfFilename = file.name, label = managedLabel)
                managed += ref
                if (managedLabel == "Delivery Sheets" && deliverySheet == null) {
                    deliverySheet = ref
                }
            } else {
                other += JobPdfRef(pdfFilename = file.name, label = file.nameWithoutExtension)
            }
        }

        return JobPdfCatalog(
            deliverySheet = deliverySheet,
            managedDocs = managed,
            otherDocs = other
        )
    }

    private fun isAssemblySheetPdf(lowercaseFilename: String): Boolean {
        return lowercaseFilename.contains("assembly sheets")
    }

    private fun isPlansElevationsPdf(lowercaseFilename: String): Boolean {
        return lowercaseFilename.contains("plans & elevations") || lowercaseFilename.contains("plans and elevations")
    }

    private fun isDeliverySheetPdf(lowercaseFilename: String): Boolean {
        return lowercaseFilename.contains("delivery sheets")
    }

    private fun isDoorListPdf(lowercaseFilename: String): Boolean {
        return lowercaseFilename.contains("door list")
    }

    private fun isCutListPdf(lowercaseFilename: String): Boolean {
        return lowercaseFilename.contains("cut list") || lowercaseFilename.contains("cutlist")
    }
}
