package com.kkc.sheettracker.data

import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.google.gson.Gson
import com.kkc.sheettracker.BuildConfig
import com.kkc.sheettracker.data.models.Job
import com.kkc.sheettracker.data.models.Material
import com.kkc.sheettracker.data.models.MaterialMetadata
import com.kkc.sheettracker.data.models.PartSearchEntry
import com.kkc.sheettracker.data.models.RefreshReason
import com.kkc.sheettracker.data.models.ScanIssue
import com.kkc.sheettracker.data.models.ScanIssueType
import com.kkc.sheettracker.data.models.ScanSnapshot
import com.kkc.sheettracker.data.models.ScanSnapshotState
import com.kkc.sheettracker.data.models.ScanStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.atomic.AtomicLong

class ScanCoordinator(
    initialBaseDir: File,
    private val jobRepository: JobRepository
) {
    private val gson = Gson()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshMutex = Mutex()
    private val generation = AtomicLong(0L)
    private val pendingLock = Any()

    @Volatile private var baseDir: File = initialBaseDir
    @Volatile private var lastStalenessSignature: Long? = null
    @Volatile private var refreshInFlight = false
    @Volatile private var pendingRefresh = false
    @Volatile private var pendingForce = false
    @Volatile private var pendingReason: RefreshReason = RefreshReason.APP_START

    private val _state = MutableStateFlow(
        ScanSnapshotState(
            status = ScanStatus.IDLE,
            snapshot = ScanSnapshot(basePath = initialBaseDir.absolutePath)
        )
    )
    val state: StateFlow<ScanSnapshotState> = _state.asStateFlow()

    init {
        jobRepository.attachScanCoordinator(this)
    }

    fun refresh(reason: RefreshReason, force: Boolean = false) {
        synchronized(pendingLock) {
            pendingRefresh = true
            pendingForce = pendingForce || force
            pendingReason = reason
            if (refreshInFlight) return
            refreshInFlight = true
        }

        scope.launch {
            while (true) {
                val (runForce, runReason) = synchronized(pendingLock) {
                    val f = pendingForce
                    val r = pendingReason
                    pendingRefresh = false
                    pendingForce = false
                    f to r
                }
                runRefresh(runReason, runForce)

                val hasPending = synchronized(pendingLock) {
                    pendingRefresh
                }
                if (!hasPending) {
                    synchronized(pendingLock) { refreshInFlight = false }
                    break
                }
            }
        }
    }

    fun invalidate() {
        lastStalenessSignature = null
    }

    fun updateBasePath(path: String) {
        baseDir = File(path)
        jobRepository.updateBaseDir(baseDir)
        invalidate()
        refresh(RefreshReason.BASE_PATH_CHANGED, force = true)
    }

    fun currentSnapshotJobs(): List<Job> = state.value.snapshot.jobs

    fun currentSearchIndex(): List<PartSearchEntry> = state.value.snapshot.searchIndex

    private suspend fun runRefresh(reason: RefreshReason, force: Boolean) {
        refreshMutex.withLock {
            val startedAt = System.currentTimeMillis()
            val previous = _state.value
            _state.value = previous.copy(
                status = ScanStatus.LOADING,
                errorMessage = null,
                lastRefreshReason = reason
            )

            try {
                val currentSignature = computeStalenessSignature(baseDir)
                val unchanged = !force &&
                    currentSignature == lastStalenessSignature &&
                    previous.snapshot.basePath == baseDir.absolutePath &&
                    previous.snapshot.jobs.isNotEmpty()

                if (unchanged) {
                    _state.value = previous.copy(
                        status = ScanStatus.READY,
                        errorMessage = null,
                        lastRefreshReason = reason
                    )
                    return
                }

                val (jobs, issues) = scanJobsFromDisk(baseDir)
                val searchIndex = buildSearchIndex(jobs)
                val nextSnapshot = ScanSnapshot(
                    generation = generation.incrementAndGet(),
                    basePath = baseDir.absolutePath,
                    jobs = jobs,
                    searchIndex = searchIndex,
                    issues = issues,
                    startedAt = startedAt,
                    completedAt = System.currentTimeMillis()
                )

                lastStalenessSignature = currentSignature
                _state.value = ScanSnapshotState(
                    status = ScanStatus.READY,
                    snapshot = nextSnapshot,
                    errorMessage = null,
                    lastRefreshReason = reason
                )
            } catch (e: Exception) {
                _state.value = previous.copy(
                    status = ScanStatus.ERROR,
                    errorMessage = e.message ?: "Unknown scan failure",
                    lastRefreshReason = reason
                )
            }
        }
    }

    private fun scanJobsFromDisk(dir: File): Pair<List<Job>, List<ScanIssue>> {
        if (!dir.exists() || !dir.isDirectory) return emptyList<Job>() to emptyList()

        val issues = mutableListOf<ScanIssue>()
        val scanned = dir.listFiles()
            ?.filter { it.isDirectory && File(it, "CNC").isDirectory }
            ?.mapNotNull { jobDir ->
                val deploymentGate = DeploymentGateRules.evaluate(jobDir, isDebugBuild = BuildConfig.DEBUG)
                if (!deploymentGate.includeJob) return@mapNotNull null
                val match = Regex("""^(\d+)\s*-\s*(.+)$""").find(jobDir.name)
                if (match != null) {
                    val jobNumber = match.groupValues[1]
                    val jobName = match.groupValues[2].trim()
                    val materials = scanMaterials(jobDir.name, File(jobDir, "CNC"), jobNumber, issues)
                    Job(
                        folderName = jobDir.name,
                        jobNumber = jobNumber,
                        jobName = jobName,
                        materials = materials,
                        hiddenFromProduction = deploymentGate.hiddenFromProduction
                    )
                } else {
                    null
                }
            }
            ?.sortedByDescending { it.jobNumber.toIntOrNull() ?: 0 }
            ?: emptyList()

        return scanned to issues
    }

    private fun scanMaterials(
        jobFolderName: String,
        cncDir: File,
        jobNumber: String,
        issues: MutableList<ScanIssue>
    ): List<Material> {
        if (!cncDir.exists()) return emptyList()

        return cncDir.listFiles()
            ?.filter {
                it.extension.equals("pdf", ignoreCase = true) &&
                    "ALL SHEETS" !in it.name &&
                    it.name.startsWith("$jobNumber - ")
            }
            ?.map { pdfFile ->
                val materialName = pdfFile.nameWithoutExtension.removePrefix("$jobNumber - ")
                val pageCount = countPdfPages(jobFolderName, materialName, pdfFile, issues)
                val metadata = loadMetadata(jobFolderName, materialName, cncDir, pdfFile.name, issues)
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

    private fun countPdfPages(
        jobFolderName: String,
        materialName: String,
        pdfFile: File,
        issues: MutableList<ScanIssue>
    ): Int {
        return try {
            val fd = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = PdfRenderer(fd)
            val count = renderer.pageCount
            renderer.close()
            fd.close()
            count
        } catch (e: Exception) {
            issues += ScanIssue(
                type = ScanIssueType.PAGE_COUNT_ERROR,
                jobFolderName = jobFolderName,
                materialName = materialName,
                pdfFilename = pdfFile.name,
                detail = e.message
            )
            issues += ScanIssue(
                type = ScanIssueType.PDF_READ_ERROR,
                jobFolderName = jobFolderName,
                materialName = materialName,
                pdfFilename = pdfFile.name,
                detail = e.message
            )
            0
        }
    }

    private fun loadMetadata(
        jobFolderName: String,
        materialName: String,
        cncDir: File,
        pdfFilename: String,
        issues: MutableList<ScanIssue>
    ): MaterialMetadata? {
        val jsonFilename = pdfFilename.removeSuffix(".pdf") + ".json"
        val metadataFile = File(cncDir, ".metadata/$jsonFilename")
        if (!metadataFile.exists()) {
            issues += ScanIssue(
                type = ScanIssueType.MISSING_METADATA,
                jobFolderName = jobFolderName,
                materialName = materialName,
                pdfFilename = pdfFilename
            )
            return null
        }

        return try {
            gson.fromJson(metadataFile.readText(), MaterialMetadata::class.java)
        } catch (e: Exception) {
            issues += ScanIssue(
                type = ScanIssueType.INVALID_METADATA_JSON,
                jobFolderName = jobFolderName,
                materialName = materialName,
                pdfFilename = pdfFilename,
                detail = e.message
            )
            null
        }
    }

    private fun buildSearchIndex(jobs: List<Job>): List<PartSearchEntry> {
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
        return index
    }

    private fun computeStalenessSignature(root: File): Long {
        if (!root.exists() || !root.isDirectory) return Long.MIN_VALUE

        var hash = 1125899906842597L
        fun mix(value: Long) {
            hash = (hash * 31L) xor value
        }

        val jobDirs = root.listFiles()
            ?.filter { it.isDirectory && File(it, "CNC").isDirectory }
            ?.sortedBy { it.name }
            ?: emptyList()
        mix(jobDirs.size.toLong())

        for (jobDir in jobDirs) {
            mix(jobDir.name.hashCode().toLong())
            mix(jobDir.lastModified())
            val cncDir = File(jobDir, "CNC")
            val pdfs = cncDir.listFiles()
                ?.filter {
                    it.isFile && it.extension.equals("pdf", ignoreCase = true) && "ALL SHEETS" !in it.name
                }
                ?.sortedBy { it.name }
                ?: emptyList()
            mix(pdfs.size.toLong())
            for (pdf in pdfs) {
                mix(pdf.name.hashCode().toLong())
                mix(pdf.length())
                mix(pdf.lastModified())
            }

            val metadataDir = File(cncDir, ".metadata")
            val metadataFiles = metadataDir.listFiles()
                ?.filter { it.isFile && it.extension.equals("json", ignoreCase = true) }
                ?.sortedBy { it.name }
                ?: emptyList()
            mix(metadataFiles.size.toLong())
            for (json in metadataFiles) {
                mix(json.name.hashCode().toLong())
                mix(json.length())
                mix(json.lastModified())
            }

            val deploymentGateFile = File(jobDir, ".metadata/deployment_gate.json")
            mix(if (deploymentGateFile.isFile) 1L else 0L)
            if (deploymentGateFile.isFile) {
                mix(deploymentGateFile.length())
                mix(deploymentGateFile.lastModified())
            }
        }
        return hash
    }
}
