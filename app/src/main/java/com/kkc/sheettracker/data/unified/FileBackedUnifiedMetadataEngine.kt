package com.kkc.sheettracker.data.unified

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.kkc.sheettracker.data.DeploymentGateRules
import com.kkc.sheettracker.data.compareJobNumbersDesc
import com.kkc.sheettracker.data.models.CacheIndexProgressSummary
import com.kkc.sheettracker.data.models.CacheIndexRoot
import com.kkc.sheettracker.data.models.AssemblyBomEntry
import com.kkc.sheettracker.data.models.AssemblyCabinetParts
import com.kkc.sheettracker.data.models.AssemblyCncPart
import com.kkc.sheettracker.data.models.AssemblyHardwoodRow
import com.kkc.sheettracker.data.models.AssemblyJob
import com.kkc.sheettracker.data.models.AssemblySheetPart
import com.kkc.sheettracker.data.models.BoardStockRow
import com.kkc.sheettracker.data.models.BoardStockSource
import com.kkc.sheettracker.data.models.CabinetSheetIndex
import com.kkc.sheettracker.data.models.HardwoodCutlistIndex
import com.kkc.sheettracker.data.models.HardwoodDocType
import com.kkc.sheettracker.data.models.HardwoodJob
import com.kkc.sheettracker.data.models.HardwoodRevisionHistory
import com.kkc.sheettracker.data.models.HardwoodRowProgress
import com.kkc.sheettracker.data.models.Job
import com.kkc.sheettracker.data.models.JobPdfCatalog
import com.kkc.sheettracker.data.models.JobPdfRef
import com.kkc.sheettracker.data.models.Material
import com.kkc.sheettracker.data.models.MaterialMetadata
import com.kkc.sheettracker.data.models.PartSearchEntry
import com.kkc.sheettracker.data.models.ReferenceDocType
import com.kkc.sheettracker.data.models.JobLabel
import com.kkc.sheettracker.data.models.ScanIssue
import com.kkc.sheettracker.data.models.ScanIssueType
import com.kkc.sheettracker.data.parseJobFolderName
import com.kkc.sheettracker.data.models.CabinetSheetIndexDocuments
import com.kkc.sheettracker.data.models.ReferenceDocumentIndex
import com.kkc.sheettracker.data.models.DeliveryDocumentIndex
import com.kkc.sheettracker.data.models.CabinetPageDetail
import com.kkc.sheettracker.data.models.AssemblySourceDocumentIndex
import com.kkc.sheettracker.data.models.AssemblyVirtualCombinedIndex
import com.kkc.sheettracker.data.models.AssemblyVirtualEntry
import com.kkc.sheettracker.data.models.AssemblyVirtualSourceRef
import com.kkc.sheettracker.data.models.HardwoodDocumentIndex
import com.kkc.sheettracker.data.models.HardwoodCutlistRow
import com.kkc.sheettracker.data.models.HardwoodTotalsBlock
import com.kkc.sheettracker.data.models.HardwoodRevisionEntry
import com.kkc.sheettracker.data.models.HardwoodRevisionRowSnapshot
import com.kkc.sheettracker.data.models.HardwoodRevisionModifiedEntry
import com.kkc.sheettracker.data.models.HardwoodRowRevisionState
import com.kkc.sheettracker.data.models.PageMetadata
import com.kkc.sheettracker.data.models.Part
import com.kkc.sheettracker.data.models.OcrBoxMetadata
import com.kkc.sheettracker.data.models.RemakePageMetadata
import com.kkc.sheettracker.data.models.RemadePartMetadata
import java.io.File
import java.math.BigDecimal
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil

class FileBackedUnifiedMetadataEngine(
    basePath: String,
    private val isDebugBuild: Boolean,
    private val pdfPageCounter: (File) -> UnifiedPdfPageCountResult = { UnifiedPdfPageCountResult(0) }
) : UnifiedMetadataEngine {
    private val gson = Gson()
    @Volatile
    private var baseDir: File = File(basePath)

    private fun <T> gsonNullable(value: T): T? = value

    private data class StaticJobData(
        val jobInfo: UnifiedJobInfo,
        val cncJob: Job?,
        val cncIssues: List<ScanIssue>,
        val hardwoodJob: HardwoodJob?,
        val hardwoodRevisionHistory: HardwoodRevisionHistory?,
        val assemblyJob: AssemblyJob?,
        val cabinetSheetIndex: CabinetSheetIndex?,
        val pdfCatalog: JobPdfCatalog,
        val boardStockRows: List<BoardStockRow>,
        val hasThreeDAssets: Boolean
    )

    private enum class StaticEntryOrigin { PUBLISHED_CACHE, DEEP_PARSE }

    private data class CachedStaticEntry(
        val signature: Long,
        val data: StaticJobData,
        val origin: StaticEntryOrigin
    )

    private data class CachedTrackerEntry(
        val signature: Long
    )

    private data class CachedSearchEntry(
        val signature: Long,
        val index: List<PartSearchEntry>
    )

    private val staticByJob = ConcurrentHashMap<String, CachedStaticEntry>()
    private val trackerByJob = ConcurrentHashMap<String, CachedTrackerEntry>()
    // CNC part search index, memoized per job keyed by the static signature so it is rebuilt
    // only when the underlying static data changes (not on every getCncSnapshot call).
    private val cncSearchByJob = ConcurrentHashMap<String, CachedSearchEntry>()
    // Lightweight cache_index.json entries: job info + progress summary, no full static data.
    private val cacheIndexByJob = ConcurrentHashMap<String, CachedCacheIndexEntry>()
    @Volatile private var cachedJobInfoList: List<UnifiedJobInfo> = emptyList()
    private data class CachedCacheIndexEntry(
        val signature: Long,
        val jobInfo: UnifiedJobInfo,
        val progressSummary: CacheIndexProgressSummary?
    )

    override fun updateBasePath(path: String) {
        baseDir = File(path)
        invalidateAll()
    }

    override fun invalidateAll() {
        staticByJob.clear()
        trackerByJob.clear()
        cncSearchByJob.clear()
        cacheIndexByJob.clear()
    }

    override fun invalidateJob(jobFolderName: String) {
        staticByJob.remove(jobFolderName)
        trackerByJob.remove(jobFolderName)
        cncSearchByJob.remove(jobFolderName)
        cacheIndexByJob.remove(jobFolderName)
        cachedJobInfoList = cachedJobInfoList.filterNot { it.folderName == jobFolderName }
    }

    override fun basePath(): String = baseDir.absolutePath

    private fun getProductionOrder(): List<String> {
        val file = File(baseDir, "production_order.json")
        if (!file.exists() || !file.isFile) return emptyList()
        return try {
            val listType = object : com.google.gson.reflect.TypeToken<List<String>>() {}.type
            gson.fromJson<List<String>>(file.readText(), listType) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    override fun getBoardGridColumns(): Int {
        val file = File(baseDir, "job_board.json")
        if (!file.exists()) return 3
        return try {
            val root = gson.fromJson(file.readText(), JsonObject::class.java) ?: return 3
            root.getAsJsonObject("settings")?.get("grid_cols")?.asInt ?: 3
        } catch (e: Exception) {
            3
        }
    }

    private data class JobBoardConfig(
        val labels: List<JobLabel> = emptyList(),
        val isPending: Boolean = false,
        val boardSection: Int = 0,
        val isDeleted: Boolean = false
    )

    private fun readLabelCatalog(root: JsonObject): Map<Int, JobLabel> {
        val labelDefs = mutableMapOf<Int, JobLabel>()
        root.getAsJsonArray("labels")?.forEach { el ->
            val obj = el.asJsonObject
            val id = obj.get("id")?.asInt ?: return@forEach
            val name = obj.get("name")?.asString ?: return@forEach
            val color = obj.get("color")?.asString ?: "#888888"
            labelDefs[id] = JobLabel(id, name, color)
        }
        return labelDefs
    }

    override fun listAllLabels(): List<JobLabel> {
        val file = File(baseDir, "job_board.json")
        if (!file.exists()) return emptyList()
        return try {
            val root = gson.fromJson(file.readText(), JsonObject::class.java) ?: return emptyList()
            readLabelCatalog(root).values.sortedBy { it.name }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun readJobBoardConfig(): Map<String, JobBoardConfig> {
        val file = File(baseDir, "job_board.json")
        if (!file.exists()) return emptyMap()
        return try {
            val root = gson.fromJson(file.readText(), JsonObject::class.java) ?: return emptyMap()
            val labelDefs = readLabelCatalog(root)
            val result = mutableMapOf<String, JobBoardConfig>()
            root.getAsJsonArray("jobs")?.forEach { el ->
                val obj = el.asJsonObject
                val folderName = obj.get("folder_name")?.asString?.takeIf { it.isNotBlank() } ?: return@forEach
                val labelIds = obj.getAsJsonArray("label_ids")?.map { it.asInt } ?: emptyList()
                val isPending = obj.get("is_pending")?.asInt == 1
                val boardSection = obj.get("board_section")?.asInt ?: 0
                val isDeleted = obj.get("is_deleted")?.asInt == 1
                result[folderName] = JobBoardConfig(
                    labels = labelIds.mapNotNull { labelDefs[it] },
                    isPending = isPending,
                    boardSection = boardSection,
                    isDeleted = isDeleted
                )
            }
            result
        } catch (e: Exception) {
            emptyMap()
        }
    }

    override fun listJobs(): List<UnifiedJobInfo> {
        if (!baseDir.exists() || !baseDir.isDirectory) return emptyList()

        val boardConfigs = readJobBoardConfig()

        val activeJobs = baseDir.listFiles()
            ?.asSequence()
            ?.filter { it.isDirectory }
            ?.mapNotNull { jobDir ->
                val config = boardConfigs[jobDir.name]
                if (config?.isDeleted == true) return@mapNotNull null

                val parsed = parseJobFolderName(jobDir.name) ?: return@mapNotNull null
                val decision = DeploymentGateRules.evaluate(jobDir, isDebugBuild = isDebugBuild)
                if (!decision.includeJob) return@mapNotNull null
                UnifiedJobInfo(
                    folderName = jobDir.name,
                    jobNumber = parsed.jobNumber,
                    jobName = parsed.jobName,
                    hiddenFromProduction = decision.hiddenFromProduction,
                    isPending = config?.isPending ?: false,
                    boardSection = config?.boardSection ?: 0
                )
            }
            ?.toList()
            ?: emptyList()

        val explicitOrder = getProductionOrder()
        val activeJobsMap = activeJobs.associateBy { it.folderName }.toMutableMap()
        val computedJobs = mutableListOf<UnifiedJobInfo>()

        // 1. Add matching jobs from the explicit order first
        for (folderName in explicitOrder) {
            val job = activeJobsMap.remove(folderName)
            if (job != null) {
                computedJobs.add(job)
            }
        }

        // 2. Add the remaining jobs sorted in standard fallback descending order
        val fallbackComparator = Comparator<UnifiedJobInfo> { a, b ->
            val numberCmp = compareJobNumbersDesc(a.jobNumber, b.jobNumber)
            if (numberCmp != 0) numberCmp else a.folderName.compareTo(b.folderName, ignoreCase = true)
        }
        val remainingJobs = activeJobsMap.values.sortedWith(fallbackComparator)
        computedJobs.addAll(remainingJobs)

        // 3. Assign 1-based index (lineupPosition) and attach labels from job_board.json
        return computedJobs.mapIndexed { index, job ->
            val config = boardConfigs[job.folderName]
            job.copy(
                lineupPosition = index + 1,
                labels = config?.labels ?: emptyList()
            )
        }
    }

    override fun getJobInfo(folderName: String): UnifiedJobInfo? =
        staticByJob[folderName]?.data?.jobInfo

    override fun getMergedJobInfo(folderName: String): UnifiedJobInfo? {
        val jobDir = File(baseDir, folderName)
        if (!jobDir.isDirectory) return null
        // Gate check first — skip hidden/undeployed jobs before touching the cache file.
        if (!DeploymentGateRules.evaluate(jobDir, isDebugBuild = isDebugBuild).includeJob) return null
        val cacheFile = File(jobDir, ".metadata/cache_static.json")
        if (!cacheFile.isFile) return null
        val rawInfo = try {
            val cacheMTime = cacheFile.lastModified()
            val existing = staticByJob[folderName]
            if (existing != null && existing.signature == cacheMTime) {
                existing.data.jobInfo
            } else {
                val rawData = gson.fromJson(cacheFile.readText(), StaticJobData::class.java) ?: return null
                val data = sanitizeStaticJobData(rawData)
                staticByJob[folderName] = CachedStaticEntry(
                    signature = cacheMTime,
                    data = data,
                    origin = StaticEntryOrigin.PUBLISHED_CACHE
                )
                data.jobInfo
            }
        } catch (e: Exception) {
            return null
        }
        // Merge board config for just this folder — same fields listJobsFromCacheOnly() merges,
        // but reads job_board.json once for one job instead of scanning every job dir.
        val config = readJobBoardConfig()[folderName]
        return mergedJobInfo(
            rawInfo = rawInfo,
            fallbackFolderName = folderName,
            config = config
        )
    }

    private fun mergedJobInfo(
        rawInfo: UnifiedJobInfo,
        fallbackFolderName: String,
        config: JobBoardConfig?
    ): UnifiedJobInfo {
        return UnifiedJobInfo(
            folderName = gsonNullable(rawInfo.folderName) ?: fallbackFolderName,
            jobNumber = gsonNullable(rawInfo.jobNumber) ?: "",
            jobName = gsonNullable(rawInfo.jobName) ?: "",
            hiddenFromProduction = rawInfo.hiddenFromProduction,
            lineupPosition = rawInfo.lineupPosition,
            labels = config?.labels ?: emptyList(),
            isPending = config?.isPending ?: false,
            boardSection = config?.boardSection ?: 0,
            indexProgress = rawInfo.indexProgress
        )
    }

    override fun listJobsFromCacheOnly(): Pair<List<UnifiedJobInfo>, List<String>> {
        // Kept for legacy callers, but list projections may only use Ready Jobs Watcher's
        // deployment gate and lightweight cache index. Full static data belongs to job-open paths.
        return listJobsFromCacheIndex()
    }

    override fun listJobsFromCacheIndex(): Pair<List<UnifiedJobInfo>, List<String>> {
        if (!baseDir.exists() || !baseDir.isDirectory) return Pair(emptyList(), emptyList())
        val loaded = mutableListOf<UnifiedJobInfo>()
        val needsDeep = mutableListOf<String>()
        val dirs = baseDir.listFiles() ?: return Pair(emptyList(), emptyList())
        for (dir in dirs) {
            if (!dir.isDirectory) continue
            if (!DeploymentGateRules.evaluate(dir, isDebugBuild = isDebugBuild).includeJob) continue
            val indexFile = File(dir, ".metadata/cache_index.json")
            if (!indexFile.isFile) {
                if (parseJobFolderName(dir.name) != null) needsDeep.add(dir.name)
                continue
            }
            try {
                val cacheMTime = indexFile.lastModified()
                val existing = cacheIndexByJob[dir.name]
                val info = if (existing != null && existing.signature == cacheMTime) {
                    existing.jobInfo
                } else {
                    val root = gson.fromJson(indexFile.readText(), CacheIndexRoot::class.java) ?: continue
                    val rawInfo = root.jobInfo ?: continue
                    val ji = UnifiedJobInfo(
                        folderName = rawInfo.folderName,
                        jobNumber = rawInfo.jobNumber,
                        jobName = rawInfo.jobName,
                        hiddenFromProduction = rawInfo.hiddenFromProduction,
                        lineupPosition = rawInfo.lineupPosition,
                        indexProgress = root.progressSummary
                    )
                    cacheIndexByJob[dir.name] = CachedCacheIndexEntry(
                        signature = cacheMTime,
                        jobInfo = ji,
                        progressSummary = root.progressSummary
                    )
                    ji
                }
                // The Jobs list contract is deployment_gate.json + cache_index.json only.
                // Do not pull job_board.json or cache_static.json while projecting list cards.
                loaded.add(info)
            } catch (e: Exception) {
                if (parseJobFolderName(dir.name) != null) needsDeep.add(dir.name)
            }
        }
        val sorted = loaded.sortedWith(
            compareBy<UnifiedJobInfo> { it.lineupPosition ?: Int.MAX_VALUE }
                .thenByDescending { it.jobNumber.toIntOrNull() ?: 0 }
                .thenBy { it.folderName }
        )
        // Syncthing can briefly expose a job directory before its index arrives (or while it is
        // being atomically replaced). Do not turn an already usable Jobs/dashboard projection
        // into an empty screen during that short window; the next valid index scan replaces it.
        if (sorted.isNotEmpty() || cachedJobInfoList.isEmpty()) {
            cachedJobInfoList = sorted
        }
        return Pair(sorted, needsDeep)
    }

    override fun getProgressFromIndex(folderName: String): CacheIndexProgressSummary? {
        val jobDir = File(baseDir, folderName)
        val indexFile = File(jobDir, ".metadata/cache_index.json")
        if (!indexFile.isFile) return null
        val cacheMTime = indexFile.lastModified()
        val existing = cacheIndexByJob[folderName]
        if (existing != null && existing.signature == cacheMTime) return existing.progressSummary
        return try {
            val root = gson.fromJson(indexFile.readText(), CacheIndexRoot::class.java) ?: return null
            val rawInfo = root.jobInfo
            if (rawInfo != null) {
                cacheIndexByJob[folderName] = CachedCacheIndexEntry(
                    signature = cacheMTime,
                    jobInfo = UnifiedJobInfo(
                        folderName = rawInfo.folderName,
                        jobNumber = rawInfo.jobNumber,
                        jobName = rawInfo.jobName,
                        hiddenFromProduction = rawInfo.hiddenFromProduction,
                        lineupPosition = rawInfo.lineupPosition,
                        indexProgress = root.progressSummary
                    ),
                    progressSummary = root.progressSummary
                )
            }
            root.progressSummary
        } catch (e: Exception) {
            null
        }
    }

    override fun getCachedJobInfos(): List<UnifiedJobInfo> = cachedJobInfoList

    override fun getCachedCncSearchIndex(jobFolderName: String): List<PartSearchEntry>? {
        return cncSearchByJob[jobFolderName]?.index
    }

    override fun refreshJobDeep(folderName: String): Boolean {
        val oldSignature = staticByJob[folderName]?.signature
        // Remove so loadStaticJobData sees a cache miss and runs the staleness check
        staticByJob.remove(folderName)
        loadStaticJobData(folderName)
        val newSignature = staticByJob[folderName]?.signature
        return newSignature != oldSignature
    }

    override fun deepScanAllJobs(): List<String> {
        if (!baseDir.exists() || !baseDir.isDirectory) return emptyList()
        val dirs = baseDir.listFiles() ?: return emptyList()
        val changed = mutableListOf<String>()
        for (dir in dirs) {
            if (!dir.isDirectory) continue
            // Only real job folders that pass the deployment gate — mirrors listJobs()/listJobsFromCacheOnly().
            if (parseJobFolderName(dir.name) == null) continue
            if (!DeploymentGateRules.evaluate(dir, isDebugBuild = isDebugBuild).includeJob) continue
            // refreshJobDeep runs checkIsCacheStale (cheap stats) and only re-parses when stale.
            if (refreshJobDeep(dir.name)) changed.add(dir.name)
        }
        return changed
    }

    override fun loadJobFromCacheFile(folderName: String): UnifiedJobInfo? {
        val jobDir = File(baseDir, folderName)
        val cacheFile = File(jobDir, ".metadata/cache_static.json")
        if (!cacheFile.isFile) return null
        return try {
            val cacheMTime = cacheFile.lastModified()
            val rawData = gson.fromJson(cacheFile.readText(), StaticJobData::class.java) ?: return null
            val data = sanitizeStaticJobData(rawData)
            staticByJob[folderName] = CachedStaticEntry(
                signature = cacheMTime,
                data = data,
                origin = StaticEntryOrigin.PUBLISHED_CACHE
            )
            data.jobInfo
        } catch (e: Exception) {
            null
        }
    }

    override fun getCncSnapshot(jobFolderName: String): UnifiedCncSnapshot? {
        // Stale index detection: if cache_index.json is older than cache_static.json,
        // the index may be stale. Log a warning but continue — static cache is authoritative.
        val jobDir = File(baseDir, jobFolderName)
        val indexFile = File(jobDir, ".metadata/cache_index.json")
        val staticFile = File(jobDir, ".metadata/cache_static.json")
        if (indexFile.isFile && staticFile.isFile) {
            if (indexFile.lastModified() < staticFile.lastModified()) {
                Log.w("CacheIndex", "cache_index.json is older than cache_static.json for $jobFolderName; progress may be stale")
            }
        }
        
        val loaded = loadStaticJobData(jobFolderName) ?: return null
        // Read the cached entry once so the static data and its signature come from a single
        // generation. Re-reading staticByJob separately (as the previous code did) let another
        // thread's reload swap the signature between the two reads, pairing this generation's
        // cncJob with a different generation's signature/search index. If the job was invalidated
        // concurrently (entry == null) fall back to the just-loaded data and skip index caching.
        val entry = staticByJob[jobFolderName]
        val staticData = entry?.data ?: loaded
        val signature = entry?.signature
        val cncJob = staticData.cncJob ?: return null
        // Reuse the memoized search index when the static signature is unchanged; only rebuild
        // (one PartSearchEntry per part across all materials/pages) on a real data change.
        val searchIndex = if (signature != null) {
            val cached = cncSearchByJob[jobFolderName]
            if (cached != null && cached.signature == signature) {
                cached.index
            } else {
                buildCncSearchIndex(cncJob).also {
                    cncSearchByJob[jobFolderName] = CachedSearchEntry(signature, it)
                }
            }
        } else {
            buildCncSearchIndex(cncJob)
        }
        return UnifiedCncSnapshot(job = cncJob, searchIndex = searchIndex, issues = staticData.cncIssues)
    }

    override fun getHardwoodsSnapshot(jobFolderName: String): UnifiedHardwoodsSnapshot? {
        val staticData = loadStaticJobData(jobFolderName) ?: return null
        val job = staticData.hardwoodJob ?: return null
        return UnifiedHardwoodsSnapshot(job = job)
    }

    override fun getHardwoodsRevisionHistory(jobFolderName: String): UnifiedHardwoodsRevisionHistory {
        val staticData = loadStaticJobData(jobFolderName)
        return UnifiedHardwoodsRevisionHistory(history = staticData?.hardwoodRevisionHistory)
    }

    override fun getAssemblySnapshot(jobFolderName: String): UnifiedAssemblySnapshot? {
        val staticData = loadStaticJobData(jobFolderName) ?: return null
        val job = staticData.assemblyJob ?: return null
        return UnifiedAssemblySnapshot(job = job)
    }

    override fun getCabinetSheetIndex(jobFolderName: String): UnifiedCabinetIndexLookup {
        val staticData = loadStaticJobData(jobFolderName)
        return UnifiedCabinetIndexLookup(index = staticData?.cabinetSheetIndex)
    }

    override fun getPdfCatalog(jobFolderName: String): UnifiedPdfCatalog {
        val staticData = loadStaticJobData(jobFolderName)
        return UnifiedPdfCatalog(catalog = staticData?.pdfCatalog ?: JobPdfCatalog())
    }

    override fun findReferencePdfFilename(jobFolderName: String, query: UnifiedReferenceQuery): UnifiedReferenceLookup {
        val docType = query.docType
        if (docType == ReferenceDocType.DELIVERY_SHEETS) {
            return UnifiedReferenceLookup(getPdfCatalog(jobFolderName).catalog.deliverySheet?.pdfFilename)
        }
        val staticData = loadStaticJobData(jobFolderName)
        val sheetIndex = staticData?.cabinetSheetIndex
        val fromIndex = when (docType) {
            ReferenceDocType.ASSEMBLY -> sheetIndex?.documents?.assembly?.pdfFilename
            ReferenceDocType.PLANS_ELEVATIONS -> sheetIndex?.documents?.plansElevations?.pdfFilename
            ReferenceDocType.DELIVERY_SHEETS -> null
            ReferenceDocType.SHEET -> null
        }?.takeIf { it.isNotBlank() }
        if (fromIndex != null) return UnifiedReferenceLookup(fromIndex)

        val target = when (docType) {
            ReferenceDocType.ASSEMBLY -> "assembly sheets"
            ReferenceDocType.PLANS_ELEVATIONS -> "plans & elevations"
            ReferenceDocType.DELIVERY_SHEETS -> "delivery sheets"
            ReferenceDocType.SHEET -> "sheet"
        }
        val jobDir = File(baseDir, jobFolderName)
        if (!jobDir.isDirectory) return UnifiedReferenceLookup(null)
        fun findIn(dir: File): String? {
            val files = dir.listFiles() ?: return null
            return files.firstOrNull { file ->
                file.isFile &&
                    file.extension.lowercase(Locale.US) == "pdf" && !file.name.contains(".sync-conflict-") &&
                    file.name.lowercase(Locale.US).contains(target)
            }?.name
        }
        return UnifiedReferenceLookup(findIn(jobDir) ?: findIn(File(jobDir, "DARK MODE")))
    }

    override fun hasReferenceDocument(jobFolderName: String, query: UnifiedReferenceQuery): UnifiedReferencePresence {
        val filename = findReferencePdfFilename(jobFolderName, query).pdfFilename
        if (filename.isNullOrBlank()) return UnifiedReferencePresence(false)
        val jobDir = File(baseDir, jobFolderName)
        val light = File(jobDir, filename)
        val dark = File(jobDir, "DARK MODE/$filename")
        return UnifiedReferencePresence(light.exists() || dark.exists())
    }

    override fun hasThreeDAssets(jobFolderName: String): UnifiedThreeDPresence {
        val staticData = loadStaticJobData(jobFolderName)
        if (staticData != null) {
            return UnifiedThreeDPresence(staticData.hasThreeDAssets)
        }
        val threeDDir = File(baseDir, "$jobFolderName/3D")
        if (!threeDDir.isDirectory) return UnifiedThreeDPresence(false)
        val exists = threeDDir.walkTopDown().maxDepth(2).any { file ->
            file.isFile && (
                file.extension.equals("glb", ignoreCase = true) ||
                    file.extension.equals("dae", ignoreCase = true)
                )
        }
        return UnifiedThreeDPresence(exists)
    }

    override fun resolveCabinetJump(jobFolderName: String, cabinetNumber: String): UnifiedCabinetJump {
        val index = getCabinetSheetIndex(jobFolderName).index
        val normalized = cabinetNumber.trim()
        val assemblyPage = assemblyCabinetToPages(index)[normalized]?.firstOrNull()
        val plansPage = index?.documents?.plansElevations?.cabinetToPages?.get(normalized)?.firstOrNull()
        return UnifiedCabinetJump(assemblyPage = assemblyPage, plansPage = plansPage)
    }

    override fun resolveCabinetContext(jobFolderName: String, cabinetNumber: String): UnifiedCabinetContext {
        val index = getCabinetSheetIndex(jobFolderName).index
        val page = assemblyCabinetToPages(index)[cabinetNumber.trim()]?.firstOrNull()
        if (page == null) return UnifiedCabinetContext("")
        val detail = assemblyPageDetails(index)[page.toString()]
        val room = detail?.room?.trim().orEmpty()
        val wall = detail?.wall?.trim().orEmpty()
        return UnifiedCabinetContext(listOf(room, wall).filter { it.isNotBlank() }.joinToString(" - "))
    }

    override fun resolveCabinetParts(
        jobFolderName: String,
        cabinetNumber: String,
        overlayLookup: UnifiedPartOverlayLookup
    ): UnifiedAssemblyCabinetParts {
        val normalizedCab = cabinetNumber.trim()
        // Resolve the cabinet index, CNC job, and hardwood job from a single cached
        // StaticJobData snapshot. Going through getCabinetSheetIndex/getCncSnapshot/
        // getHardwoodsSnapshot would re-stat the cache file three times and, via
        // getCncSnapshot, rebuild the entire CNC search index only to discard it.
        val staticData = loadStaticJobData(jobFolderName)
        val index = staticData?.cabinetSheetIndex
        val assemblyPages = assemblyCabinetToPages(index)[normalizedCab].orEmpty()
        val assemblyDetails = assemblyPageDetails(index)

        val sheetParts = assemblyPages.flatMap { page ->
            assemblyDetails[page.toString()]?.parts.orEmpty()
        }

        val cncParts = mutableListOf<AssemblyCncPart>()
        val cncJob = staticData?.cncJob
        cncJob?.materials.orEmpty().forEach { material ->
            material.metadata?.pages.orEmpty().forEach { page ->
                if (page.hiddenInApp || page.trackingExcluded || page.isPartListContinuation) return@forEach
                page.parts
                    .filter { it.cabNumber.toString() == normalizedCab }
                    .forEach { part ->
                        val status = overlayLookup.sheetStatus(jobFolderName, material.pdfFilename, page.pageNumber, material.fileFingerprint)
                        val bad = overlayLookup.isBadPart(jobFolderName, material.pdfFilename, page.pageNumber, material.fileFingerprint, part.number)
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
                            isBadPart = bad
                        )
                    }
            }
        }

        val hardwoodRows = mutableListOf<AssemblyHardwoodRow>()
        val hardwoodJob = staticData?.hardwoodJob
        hardwoodJob?.index?.documents.orEmpty().forEach { doc ->
            doc.rows
                .filter { row -> row.cabinets.any { it.trim() == normalizedCab } }
                .forEach { row ->
                    val progress = overlayLookup.rowProgress(jobFolderName, doc.docType.name, row.rowId)
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

        val bom = if (sheetParts.isEmpty()) {
            emptyList()
        } else {
            val cncByDesc = cncParts.groupBy { normalizeKey(it.partName) }
            val hardwoodByDesc = hardwoodRows.groupBy { normalizeKey(it.description) }
            sheetParts.map { part ->
                val key = normalizeKey(part.description)
                AssemblyBomEntry(
                    part = part,
                    cncParts = cncByDesc[key].orEmpty(),
                    hardwoodRows = hardwoodByDesc[key].orEmpty()
                )
            }
        }

        return UnifiedAssemblyCabinetParts(
            AssemblyCabinetParts(
                cabinetNumber = normalizedCab,
                bom = bom,
                cncParts = cncParts,
                hardwoodRows = hardwoodRows
            )
        )
    }

    override fun getBoardStockRows(
        jobFolderName: String,
        includeProgressOverlay: Boolean,
        overlayLookup: UnifiedBoardStockOverlayLookup
    ): UnifiedBoardStockRows {
        val staticData = loadStaticJobData(jobFolderName)
        val rows = staticData?.boardStockRows.orEmpty()
        if (!includeProgressOverlay) return UnifiedBoardStockRows(rows)
        val adjusted = applySkippedPartRowsToBoardStockRows(
            rows = rows,
            index = staticData?.hardwoodJob?.index,
            rowProgressMap = overlayLookup.rowProgressMap
        )
        return UnifiedBoardStockRows(adjusted)
    }

    private fun checkIsCacheStale(jobDir: File, cacheMTime: Long): Boolean {
        val deploymentGate = File(jobDir, ".metadata/deployment_gate.json")
        if (deploymentGate.isFile && deploymentGate.lastModified() > cacheMTime) return true

        val cabinetIndex = File(jobDir, ".metadata/cabinet_sheet_index.json")
        if (cabinetIndex.isFile && cabinetIndex.lastModified() > cacheMTime) return true

        val hardwoodIndex = File(jobDir, ".metadata/hardwoods/cutlist_index.json")
        if (hardwoodIndex.isFile && hardwoodIndex.lastModified() > cacheMTime) return true

        val hardwoodRevision = File(jobDir, ".metadata/hardwoods/cutlist_revisions.json")
        if (hardwoodRevision.isFile && hardwoodRevision.lastModified() > cacheMTime) return true

        val manualBoard = File(jobDir, ".metadata/hardwoods/board_stock_manual.json")
        if (manualBoard.isFile && manualBoard.lastModified() > cacheMTime) return true

        val rootPdfs = jobDir.listFiles()
        if (rootPdfs != null) {
            for (file in rootPdfs) {
                if (file.isFile && file.extension.equals("pdf", ignoreCase = true) && !file.name.contains(".sync-conflict-")) {
                    if (file.lastModified() > cacheMTime) return true
                }
            }
        }

        val cncDir = File(jobDir, "CNC")
        val cncPdfs = cncDir.listFiles()
        if (cncPdfs != null) {
            for (file in cncPdfs) {
                if (file.isFile && file.extension.equals("pdf", ignoreCase = true) && "ALL SHEETS" !in file.name && !file.name.contains(".sync-conflict-")) {
                    if (file.lastModified() > cacheMTime) return true
                }
            }
        }

        val cncMetadataDir = File(cncDir, ".metadata")
        val cncMetadata = cncMetadataDir.listFiles()
        if (cncMetadata != null) {
            for (file in cncMetadata) {
                if (file.isFile && file.extension.equals("json", ignoreCase = true) && !file.name.contains(".sync-conflict-")) {
                    if (file.lastModified() > cacheMTime) return true
                }
            }
        }

        return false
    }

    override fun getSignatures(jobFolderName: String): UnifiedMetadataSignature {
        val jobDir = File(baseDir, jobFolderName)
        if (!jobDir.isDirectory) return UnifiedMetadataSignature(staticSignature = 0L, trackerSignature = 0L)
        val cacheFile = File(jobDir, ".metadata/cache_static.json")
        val staticSignature = if (cacheFile.isFile) {
            val cacheMTime = cacheFile.lastModified()
            if (checkIsCacheStale(jobDir, cacheMTime)) {
                computeStaticSignature(jobDir)
            } else {
                cacheMTime
            }
        } else {
            computeStaticSignature(jobDir)
        }
        val trackerSignature = computeTrackerSignature(jobDir)
        trackerByJob[jobFolderName] = CachedTrackerEntry(signature = trackerSignature)
        return UnifiedMetadataSignature(staticSignature = staticSignature, trackerSignature = trackerSignature)
    }

    private fun loadStaticJobData(jobFolderName: String): StaticJobData? {
        val jobDir = File(baseDir, jobFolderName)
        if (!jobDir.isDirectory) return null

        val cacheFile = File(jobDir, ".metadata/cache_static.json")
        if (cacheFile.isFile) {
            val cacheMTime = cacheFile.lastModified()
            // Fast path: if in-memory cache was already populated (e.g. by listJobsFromCacheOnly
            // or a previous load) and the file hasn't changed, return immediately — no staleness check.
            val cached = staticByJob[jobFolderName]
            if (cached != null && cached.signature == cacheMTime) return cached.data

            if (!checkIsCacheStale(jobDir, cacheMTime)) {
                try {
                    val rawData = gson.fromJson(cacheFile.readText(), StaticJobData::class.java)
                    if (rawData != null) {
                        val data = sanitizeStaticJobData(rawData)
                        staticByJob[jobFolderName] = CachedStaticEntry(
                            signature = cacheMTime,
                            data = data,
                            origin = StaticEntryOrigin.PUBLISHED_CACHE
                        )
                        return data
                    }
                } catch (e: Exception) {
                    // Fallback to raw parsing if cache reading fails
                }
            }
        }

        val currentSig = computeStaticSignature(jobDir)
        val cached = staticByJob[jobFolderName]
        if (cached != null && cached.signature == currentSig) return cached.data

        val parsed = parseJobFolderName(jobFolderName) ?: return null
        val gate = DeploymentGateRules.evaluate(jobDir, isDebugBuild = isDebugBuild)
        if (!gate.includeJob) return null

        val allJobs = listJobs()
        val thisJob = allJobs.find { it.folderName == jobFolderName }
        val lineupPosition = thisJob?.lineupPosition
        val isPending = thisJob?.isPending ?: false
        val boardSection = thisJob?.boardSection ?: 0

        val jobInfo = UnifiedJobInfo(
            folderName = jobFolderName,
            jobNumber = parsed.jobNumber,
            jobName = parsed.jobName,
            hiddenFromProduction = gate.hiddenFromProduction,
            lineupPosition = lineupPosition,
            labels = thisJob?.labels ?: emptyList(),
            isPending = isPending,
            boardSection = boardSection
        )

        val cnc = buildCncJob(jobInfo)
        val cabinetSheetIndex = loadCabinetSheetIndex(jobFolderName)
        val hardwoodIndex = loadHardwoodIndex(jobFolderName)
        val hardwoodRevisionHistory = loadHardwoodRevisionHistory(jobFolderName)
        val hardwoodJob = HardwoodJob(
            folderName = jobInfo.folderName,
            jobNumber = jobInfo.jobNumber,
            jobName = jobInfo.jobName,
            index = hardwoodIndex,
            hiddenFromProduction = jobInfo.hiddenFromProduction,
            lineupPosition = jobInfo.lineupPosition,
            labels = jobInfo.labels,
            isPending = jobInfo.isPending,
            boardSection = jobInfo.boardSection
        )
        val assemblyJob = AssemblyJob(
            folderName = jobInfo.folderName,
            jobNumber = jobInfo.jobNumber,
            jobName = jobInfo.jobName,
            cabinetSheetIndex = cabinetSheetIndex,
            hiddenFromProduction = jobInfo.hiddenFromProduction,
            lineupPosition = jobInfo.lineupPosition,
            labels = jobInfo.labels,
            isPending = jobInfo.isPending,
            boardSection = jobInfo.boardSection
        )
        val pdfCatalog = buildPdfCatalog(jobFolderName)
        val boardStockRows = buildBoardStockRows(jobFolderName, hardwoodIndex)

        val threeDDir = File(baseDir, "$jobFolderName/3D")
        val hasThreeD = threeDDir.isDirectory && threeDDir.walkTopDown().maxDepth(2).any { file ->
            file.isFile && (
                file.extension.equals("glb", ignoreCase = true) ||
                    file.extension.equals("dae", ignoreCase = true)
                )
        }

        val next = StaticJobData(
            jobInfo = jobInfo,
            cncJob = cnc.job,
            cncIssues = cnc.issues,
            hardwoodJob = hardwoodJob,
            hardwoodRevisionHistory = hardwoodRevisionHistory,
            assemblyJob = assemblyJob,
            cabinetSheetIndex = cabinetSheetIndex,
            pdfCatalog = pdfCatalog,
            boardStockRows = boardStockRows,
            hasThreeDAssets = hasThreeD
        )
        staticByJob[jobFolderName] = CachedStaticEntry(
            signature = currentSig,
            data = next,
            origin = StaticEntryOrigin.DEEP_PARSE
        )
        return next
    }

    private data class BuiltCncJob(
        val job: Job,
        val issues: List<ScanIssue>
    )

    private fun buildCncJob(job: UnifiedJobInfo): BuiltCncJob {
        val cncDir = File(baseDir, "${job.folderName}/CNC")
        val issues = mutableListOf<ScanIssue>()
        val materials = scanCncMaterials(job.folderName, cncDir, job.jobNumber, issues)
        return BuiltCncJob(
            job = Job(
                folderName = job.folderName,
                jobNumber = job.jobNumber,
                jobName = job.jobName,
                materials = materials,
                hiddenFromProduction = job.hiddenFromProduction,
                lineupPosition = job.lineupPosition,
                labels = job.labels,
                isPending = job.isPending,
                boardSection = job.boardSection
            ),
            issues = issues
        )
    }

    private fun scanCncMaterials(
        jobFolderName: String,
        cncDir: File,
        jobNumber: String,
        issues: MutableList<ScanIssue>
    ): List<Material> {
        if (!cncDir.exists()) return emptyList()
        // Split/lettered jobs (e.g. "530a") sometimes get CNC remake sheets exported
        // under the base numeric job number ("530 - 001 REMAKE - ...") instead of the
        // lettered folder number. Accept either prefix so those aren't silently dropped.
        val jobBaseNumber = jobNumber.replace(Regex("[A-Za-z]+$"), "")
        return cncDir.listFiles()
            ?.mapNotNull { file ->
                if (!file.extension.equals("pdf", ignoreCase = true) || "ALL SHEETS" in file.name) return@mapNotNull null
                val matchedPrefix = when {
                    file.name.startsWith("$jobNumber - ") -> jobNumber
                    jobBaseNumber.isNotEmpty() && jobBaseNumber != jobNumber && file.name.startsWith("$jobBaseNumber - ") -> jobBaseNumber
                    else -> null
                }
                if (matchedPrefix == null) null else file to matchedPrefix
            }
            ?.map { (pdfFile, matchedPrefix) ->
                val materialName = pdfFile.nameWithoutExtension.removePrefix("$matchedPrefix - ")
                val metadata = loadCncMaterialMetadata(
                    jobFolderName = jobFolderName,
                    materialName = materialName,
                    cncDir = cncDir,
                    pdfFilename = pdfFile.name,
                    issues = issues
                )
                val pageCountResult = runCatching { pdfPageCounter(pdfFile) }
                    .getOrElse { UnifiedPdfPageCountResult(pageCount = metadata?.pages?.size ?: 0, errorDetail = it.message) }
                if (!pageCountResult.errorDetail.isNullOrBlank()) {
                    issues += ScanIssue(
                        type = ScanIssueType.PAGE_COUNT_ERROR,
                        jobFolderName = jobFolderName,
                        materialName = materialName,
                        pdfFilename = pdfFile.name,
                        detail = pageCountResult.errorDetail
                    )
                    issues += ScanIssue(
                        type = ScanIssueType.PDF_READ_ERROR,
                        jobFolderName = jobFolderName,
                        materialName = materialName,
                        pdfFilename = pdfFile.name,
                        detail = pageCountResult.errorDetail
                    )
                }
                val pageCount = if (pageCountResult.pageCount > 0) pageCountResult.pageCount else (metadata?.pages?.size ?: 0)
                Material(
                    pdfFilename = pdfFile.name,
                    materialName = materialName,
                    pageCount = pageCount,
                    fileFingerprint = "${pdfFile.length()}_${pdfFile.lastModified()}",
                    metadata = metadata
                )
            }
            ?.sortedBy { it.materialName }
            ?: emptyList()
    }

    private fun loadCncMaterialMetadata(
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
        return runCatching {
            val parsed = gson.fromJson(metadataFile.readText(), MaterialMetadata::class.java)
            sanitizeMaterialMetadata(parsed)
        }
            .onFailure { error ->
                issues += ScanIssue(
                    type = ScanIssueType.INVALID_METADATA_JSON,
                    jobFolderName = jobFolderName,
                    materialName = materialName,
                    pdfFilename = pdfFilename,
                    detail = error.message
                )
            }
            .getOrNull()
    }

    private fun loadCabinetSheetIndex(jobFolderName: String): CabinetSheetIndex? {
        val indexFile = File(baseDir, "$jobFolderName/.metadata/cabinet_sheet_index.json")
        if (!indexFile.exists() || !indexFile.isFile) return null
        return runCatching {
            val parsed = gson.fromJson(indexFile.readText(), CabinetSheetIndex::class.java)
            sanitizeCabinetSheetIndex(parsed)
        }.getOrNull()
    }

    private fun loadHardwoodIndex(jobFolderName: String): HardwoodCutlistIndex? {
        val indexFile = File(baseDir, "$jobFolderName/.metadata/hardwoods/cutlist_index.json")
        if (!indexFile.exists() || !indexFile.isFile) return null
        return runCatching {
            val parsed = gson.fromJson(indexFile.readText(), HardwoodCutlistIndex::class.java)
            sanitizeHardwoodCutlistIndex(parsed)
        }.getOrNull()
    }

    private fun loadHardwoodRevisionHistory(jobFolderName: String): HardwoodRevisionHistory? {
        val file = File(baseDir, "$jobFolderName/.metadata/hardwoods/cutlist_revisions.json")
        if (!file.exists() || !file.isFile) return null
        return runCatching {
            val parsed = gson.fromJson(file.readText(), HardwoodRevisionHistory::class.java)
            sanitizeHardwoodRevisionHistory(parsed)
        }.getOrNull()
    }

    private fun buildPdfCatalog(jobFolderName: String): JobPdfCatalog {
        val jobDir = File(baseDir, jobFolderName)
        if (!jobDir.isDirectory) return JobPdfCatalog()
        val rootPdfs = jobDir.listFiles()
            ?.asSequence()
            ?.filter { it.isFile && it.extension.lowercase(Locale.US) == "pdf" }
            ?.sortedBy { it.name.lowercase(Locale.US) }
            ?.toList()
            .orEmpty()

        val managed = mutableListOf<JobPdfRef>()
        val other = mutableListOf<JobPdfRef>()
        var deliverySheet: JobPdfRef? = null
        rootPdfs.forEach { file ->
            val lower = file.name.lowercase(Locale.US)
            val managedLabel = when {
                lower.contains("delivery sheets") -> "Delivery Sheets"
                lower.contains("assembly sheets") -> "Assembly Sheets"
                lower.contains("plans & elevations") || lower.contains("plans and elevations") -> "Plans & Elevations"
                lower.contains("door list") -> "Door List"
                lower.contains("cut list") || lower.contains("cutlist") -> "Cut List"
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
        return JobPdfCatalog(deliverySheet = deliverySheet, managedDocs = managed, otherDocs = other)
    }

    private fun buildCncSearchIndex(job: Job): List<PartSearchEntry> {
        val index = mutableListOf<PartSearchEntry>()
        for (material in job.materials) {
            val pages = material.metadata?.pages ?: continue
            for (page in pages) {
                if (page.hiddenInApp || page.trackingExcluded || page.isPartListContinuation) continue
                for (part in page.parts) {
                    index += PartSearchEntry(
                        jobFolderName = job.folderName,
                        jobNumber = job.jobNumber,
                        materialName = material.materialName,
                        pdfFilename = material.pdfFilename,
                        pageNumber = page.pageNumber,
                        partNumber = part.number,
                        partName = part.name,
                        room = part.room,
                        cabNumber = part.cabNumber
                    )
                }
            }
        }
        return index
    }

    private fun buildBoardStockRows(jobFolderName: String, index: HardwoodCutlistIndex?): List<BoardStockRow> {
        val aggregated = linkedMapOf<Triple<String, Double, BoardStockSource>, Double>()
        index?.documents.orEmpty().forEach { doc ->
            val source = when (doc.docType) {
                HardwoodDocType.FACE_FRAME_CUT_LIST -> BoardStockSource.FRAME
                HardwoodDocType.NAILER_CUT_LIST -> BoardStockSource.NAILER
                HardwoodDocType.DOOR_CUT_LIST -> BoardStockSource.DOOR
                HardwoodDocType.CLOSET_ROD_CUT_LIST -> null
                HardwoodDocType.DOOR_LIST -> null
            } ?: return@forEach
            doc.totals.forEach { block ->
                val material = block.material.orEmpty().trim()
                val maxSize = maxOf(block.widthValues.size, block.lengthValues.size)
                for (i in 0 until maxSize) {
                    val widthRaw = block.widthValues.getOrNull(i).orEmpty().trim()
                    val feetRaw = block.lengthValues.getOrNull(i).orEmpty().trim().replace(",", "")
                    val width = widthRaw.toDoubleOrNull() ?: continue
                    val feet = feetRaw.toDoubleOrNull() ?: 0.0
                    if (feet <= 0.0) continue
                    val key = Triple(material, width, source)
                    aggregated[key] = (aggregated[key] ?: 0.0) + feet
                }
            }
        }

        val rows = aggregated.map { (k, feet) ->
            BoardStockRow(
                stableKey = "board_stock|${k.first}|${formatWidth(k.second)}|${k.third.name}",
                material = k.first,
                width = formatWidth(k.second),
                normalizedWidth = k.second,
                source = k.third,
                sourceLabel = k.third.name,
                totalFeet = feet,
                neededRips = ceil(feet / 10.0).toInt()
            )
        }.toMutableList()

        rows += loadManualBoardStockRows(jobFolderName)
        return rows.sortedWith(
            compareBy<BoardStockRow, String>(String.CASE_INSENSITIVE_ORDER) { it.material }
                .thenByDescending { it.normalizedWidth }
                .thenBy {
                    when (it.source) {
                        BoardStockSource.FRAME -> 0
                        BoardStockSource.NAILER -> 1
                        BoardStockSource.DOOR -> 2
                        BoardStockSource.MANUAL -> 3
                    }
                }
        )
    }

    private fun loadManualBoardStockRows(jobFolderName: String): List<BoardStockRow> {
        val file = File(baseDir, "$jobFolderName/.metadata/hardwoods/board_stock_manual.json")
        if (!file.exists() || !file.isFile) return emptyList()
        return runCatching {
            val rootObj = gson.fromJson(file.readText(), JsonObject::class.java)
            val entries = rootObj?.getAsJsonArray("entries") ?: JsonArray()
            entries.mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                val material = obj.get("material")?.asString?.trim().orEmpty()
                val widthRaw = obj.get("width")?.asString ?: obj.get("normalizedWidth")?.asString.orEmpty()
                val width = widthRaw.trim().toDoubleOrNull() ?: return@mapNotNull null
                val feet = obj.get("totalFeet")?.asDouble ?: return@mapNotNull null
                if (feet <= 0.0) return@mapNotNull null
                BoardStockRow(
                    stableKey = "board_stock|$material|${formatWidth(width)}|MANUAL",
                    material = material,
                    width = formatWidth(width),
                    normalizedWidth = width,
                    source = BoardStockSource.MANUAL,
                    sourceLabel = BoardStockSource.MANUAL.name,
                    totalFeet = feet,
                    neededRips = ceil(feet / 10.0).toInt(),
                    manualCategory = obj.get("category")?.asString,
                    manualSubtype = obj.get("subtype")?.asString,
                    notes = obj.get("notes")?.asString
                )
            }
        }.getOrElse { emptyList() }
    }

    private fun formatWidth(value: Double): String {
        return BigDecimal.valueOf(if (value == -0.0) 0.0 else value).stripTrailingZeros().toPlainString()
    }

    private fun applySkippedPartRowsToBoardStockRows(
        rows: List<BoardStockRow>,
        index: HardwoodCutlistIndex?,
        rowProgressMap: Map<Pair<String, String>, HardwoodRowProgress>
    ): List<BoardStockRow> {
        if (rows.isEmpty() || index == null || rowProgressMap.isEmpty()) return rows
        val skippedFeetByKey = mutableMapOf<String, Double>()
        val remainingFeetByKey = mutableMapOf<String, Double>()
        index.documents.forEach { doc ->
            val source = when (doc.docType) {
                HardwoodDocType.FACE_FRAME_CUT_LIST -> BoardStockSource.FRAME
                HardwoodDocType.NAILER_CUT_LIST -> BoardStockSource.NAILER
                HardwoodDocType.DOOR_CUT_LIST -> BoardStockSource.DOOR
                HardwoodDocType.CLOSET_ROD_CUT_LIST -> null
                HardwoodDocType.DOOR_LIST -> null
            } ?: return@forEach

            doc.rows.forEach { row ->
                val state = rowProgressMap[doc.docType.name to row.rowId] ?: return@forEach
                val material = row.material?.trim().orEmpty()
                if (material.isBlank()) return@forEach
                val width = parseDimensionToken(row.width) ?: return@forEach
                val lengthInches = parseDimensionToken(row.length) ?: return@forEach
                val qty = row.qty.coerceAtLeast(0)
                if (qty <= 0) return@forEach
                val feet = (lengthInches * qty.toDouble()) / 12.0
                if (feet <= 0.0) return@forEach
                val key = boardStockKey(material, width, source)
                if (state.skipped) {
                    skippedFeetByKey[key] = (skippedFeetByKey[key] ?: 0.0) + feet
                } else {
                    remainingFeetByKey[key] = (remainingFeetByKey[key] ?: 0.0) + feet
                }
            }
        }
        if (skippedFeetByKey.isEmpty() && remainingFeetByKey.isEmpty()) return rows
        return rows.mapNotNull { row ->
            val key = boardStockKey(row.material, row.normalizedWidth, row.source)
            val remainingFeetFromRows = remainingFeetByKey[key]
            val adjustedFeet = when {
                remainingFeetFromRows != null -> remainingFeetFromRows
                else -> (row.totalFeet - (skippedFeetByKey[key] ?: 0.0)).coerceAtLeast(0.0)
            }
            val adjustedRips = ceil(adjustedFeet / 10.0).toInt()
            if (adjustedRips <= 0) return@mapNotNull null
            row.copy(totalFeet = adjustedFeet, neededRips = adjustedRips)
        }
    }

    private fun parseDimensionToken(raw: String): Double? {
        val text = raw.trim().replace("\"", "")
        if (text.isEmpty()) return null
        text.toDoubleOrNull()?.let { return it }

        val mixed = Regex("""^(\d+)\s+(\d+)\s*/\s*(\d+)$""").matchEntire(text)
        if (mixed != null) {
            val whole = mixed.groupValues[1].toDoubleOrNull() ?: return null
            val num = mixed.groupValues[2].toDoubleOrNull() ?: return null
            val den = mixed.groupValues[3].toDoubleOrNull()?.takeIf { it != 0.0 } ?: return null
            return whole + (num / den)
        }

        val frac = Regex("""^(\d+)\s*/\s*(\d+)$""").matchEntire(text)
        if (frac != null) {
            val num = frac.groupValues[1].toDoubleOrNull() ?: return null
            val den = frac.groupValues[2].toDoubleOrNull()?.takeIf { it != 0.0 } ?: return null
            return num / den
        }

        val dashMixed = Regex("""^(\d+)-(\d+)\s*/\s*(\d+)$""").matchEntire(text)
        if (dashMixed != null) {
            val whole = dashMixed.groupValues[1].toDoubleOrNull() ?: return null
            val num = dashMixed.groupValues[2].toDoubleOrNull() ?: return null
            val den = dashMixed.groupValues[3].toDoubleOrNull()?.takeIf { it != 0.0 } ?: return null
            return whole + (num / den)
        }
        return null
    }

    private fun boardStockKey(material: String, width: Double, source: BoardStockSource): String {
        val materialKey = material.trim().replace(Regex("""\s+"""), " ").uppercase(Locale.US)
        val widthKey = BigDecimal.valueOf(if (width == -0.0) 0.0 else width).stripTrailingZeros().toPlainString()
        return "$materialKey|$widthKey|${source.name}"
    }

    private fun normalizeKey(value: String): String {
        return value.trim().lowercase().replace(Regex("\\s+"), " ")
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

    private fun computeStaticSignature(jobDir: File): Long {
        var hash = 1125899906842597L
        fun mix(value: Long) {
            hash = (hash * 31L) xor value
        }
        mix(jobDir.name.hashCode().toLong())
        val deploymentGate = File(jobDir, ".metadata/deployment_gate.json")
        mix(signatureForFile(deploymentGate))

        val cabinetIndex = File(jobDir, ".metadata/cabinet_sheet_index.json")
        mix(signatureForFile(cabinetIndex))

        val hardwoodIndex = File(jobDir, ".metadata/hardwoods/cutlist_index.json")
        mix(signatureForFile(hardwoodIndex))
        val hardwoodRevision = File(jobDir, ".metadata/hardwoods/cutlist_revisions.json")
        mix(signatureForFile(hardwoodRevision))
        val manualBoard = File(jobDir, ".metadata/hardwoods/board_stock_manual.json")
        mix(signatureForFile(manualBoard))

        val rootPdfs = jobDir.listFiles()
            ?.filter { it.isFile && it.extension.equals("pdf", ignoreCase = true) && !it.name.contains(".sync-conflict-") }
            ?.sortedBy { it.name }
            .orEmpty()
        mix(rootPdfs.size.toLong())
        rootPdfs.forEach { file -> mix(signatureForFile(file)) }

        val cncDir = File(jobDir, "CNC")
        val cncPdfs = cncDir.listFiles()
            ?.filter { it.isFile && it.extension.equals("pdf", ignoreCase = true) && "ALL SHEETS" !in it.name && !it.name.contains(".sync-conflict-") }
            ?.sortedBy { it.name }
            .orEmpty()
        mix(cncPdfs.size.toLong())
        cncPdfs.forEach { file -> mix(signatureForFile(file)) }

        val cncMetadata = File(cncDir, ".metadata").listFiles()
            ?.filter { it.isFile && it.extension.equals("json", ignoreCase = true) && !it.name.contains(".sync-conflict-") }
            ?.sortedBy { it.name }
            .orEmpty()
        mix(cncMetadata.size.toLong())
        cncMetadata.forEach { file -> mix(signatureForFile(file)) }

        return hash
    }

    private fun computeTrackerSignature(jobDir: File): Long {
        var hash = 1469598103934665603L
        fun mix(value: Long) {
            hash = (hash xor value) * 1099511628211L
        }
        fun mixTrackerDir(dir: File) {
            val files = dir.listFiles()
                ?.filter {
                    it.isFile &&
                        it.extension.equals("json", ignoreCase = true) &&
                        !it.name.startsWith(".") && !it.name.contains(".sync-conflict-")
                }
                ?.sortedBy { it.name }
                .orEmpty()
            mix(files.size.toLong())
            files.forEach { file -> mix(signatureForFile(file)) }
        }
        mixTrackerDir(File(jobDir, "CNC/.tracker"))
        mixTrackerDir(File(jobDir, ".metadata/hardwoods/.tracker"))
        return hash
    }

    private fun sanitizeStaticJobData(data: StaticJobData): StaticJobData {
        val rawInfo = data.jobInfo
        val sanitizedJobInfo = UnifiedJobInfo(
            folderName = gsonNullable(rawInfo.folderName) ?: "",
            jobNumber = gsonNullable(rawInfo.jobNumber) ?: "",
            jobName = gsonNullable(rawInfo.jobName) ?: "",
            hiddenFromProduction = rawInfo.hiddenFromProduction,
            lineupPosition = rawInfo.lineupPosition,
            labels = gsonNullable(rawInfo.labels) ?: emptyList(),
            isPending = rawInfo.isPending,
            boardSection = rawInfo.boardSection
        )

        val cnc = data.cncJob
        val sanitizedCncJob = if (cnc != null) {
            Job(
                folderName = gsonNullable(cnc.folderName) ?: "",
                jobNumber = gsonNullable(cnc.jobNumber) ?: "",
                jobName = gsonNullable(cnc.jobName) ?: "",
                materials = gsonNullable(cnc.materials) ?: emptyList(),
                hiddenFromProduction = cnc.hiddenFromProduction,
                lineupPosition = cnc.lineupPosition,
                labels = gsonNullable(cnc.labels) ?: emptyList(),
                isPending = cnc.isPending,
                boardSection = cnc.boardSection
            )
        } else null

        val hardwood = data.hardwoodJob
        val sanitizedHardwoodJob = if (hardwood != null) {
            HardwoodJob(
                folderName = gsonNullable(hardwood.folderName) ?: "",
                jobNumber = gsonNullable(hardwood.jobNumber) ?: "",
                jobName = gsonNullable(hardwood.jobName) ?: "",
                index = hardwood.index,
                hiddenFromProduction = hardwood.hiddenFromProduction,
                lineupPosition = hardwood.lineupPosition,
                labels = gsonNullable(hardwood.labels) ?: emptyList(),
                isPending = hardwood.isPending,
                boardSection = hardwood.boardSection
            )
        } else null

        val assembly = data.assemblyJob
        val sanitizedAssemblyJob = if (assembly != null) {
            AssemblyJob(
                folderName = gsonNullable(assembly.folderName) ?: "",
                jobNumber = gsonNullable(assembly.jobNumber) ?: "",
                jobName = gsonNullable(assembly.jobName) ?: "",
                cabinetSheetIndex = assembly.cabinetSheetIndex,
                cncSummary = assembly.cncSummary,
                hardwoodsSummary = assembly.hardwoodsSummary,
                hiddenFromProduction = assembly.hiddenFromProduction,
                lineupPosition = assembly.lineupPosition,
                labels = gsonNullable(assembly.labels) ?: emptyList(),
                isPending = assembly.isPending,
                boardSection = assembly.boardSection
            )
        } else null

        val catalog = gsonNullable(data.pdfCatalog)
        val sanitizedPdfCatalog = JobPdfCatalog(
            deliverySheet = catalog?.deliverySheet,
            managedDocs = catalog?.managedDocs ?: emptyList(),
            otherDocs = catalog?.otherDocs ?: emptyList()
        )

        return data.copy(
            jobInfo = sanitizedJobInfo,
            cncJob = sanitizedCncJob,
            cncIssues = gsonNullable(data.cncIssues) ?: emptyList(),
            hardwoodJob = sanitizedHardwoodJob,
            assemblyJob = sanitizedAssemblyJob,
            pdfCatalog = sanitizedPdfCatalog,
            boardStockRows = gsonNullable(data.boardStockRows) ?: emptyList()
        )
    }

    private fun signatureForFile(file: File): Long {
        if (!file.exists() || !file.isFile) return 0L
        var sig = file.name.hashCode().toLong()
        sig = sig * 31 + file.length()
        sig = sig * 31 + file.lastModified()
        return sig
    }

    private fun sanitizeCabinetSheetIndex(index: CabinetSheetIndex?): CabinetSheetIndex {
        if (index == null) return CabinetSheetIndex()
        return CabinetSheetIndex(
            documents = sanitizeCabinetSheetIndexDocuments(index.documents),
            generatedAt = index.generatedAt
        )
    }

    private fun sanitizeCabinetSheetIndexDocuments(docs: CabinetSheetIndexDocuments?): CabinetSheetIndexDocuments {
        if (docs == null) return CabinetSheetIndexDocuments()
        return CabinetSheetIndexDocuments(
            assembly = sanitizeReferenceDocumentIndex(docs.assembly),
            plansElevations = sanitizeReferenceDocumentIndex(docs.plansElevations),
            delivery = sanitizeDeliveryDocumentIndex(docs.delivery)
        )
    }

    private fun sanitizeReferenceDocumentIndex(ref: ReferenceDocumentIndex?): ReferenceDocumentIndex {
        if (ref == null) return ReferenceDocumentIndex()
        return ReferenceDocumentIndex(
            pdfFilename = gsonNullable(ref.pdfFilename) ?: "",
            cabinetToPages = gsonNullable(ref.cabinetToPages) ?: emptyMap(),
            pageDetails = (gsonNullable(ref.pageDetails) ?: emptyMap()).mapValues { (_, detail) -> sanitizeCabinetPageDetail(detail) },
            mode = ref.mode,
            modeSource = ref.modeSource,
            sources = (gsonNullable(ref.sources) ?: emptyList()).map { sanitizeAssemblySourceDocumentIndex(it) },
            virtualCombined = ref.virtualCombined?.let { vc ->
                AssemblyVirtualCombinedIndex(
                    cabinetOrder = gsonNullable(vc.cabinetOrder) ?: emptyList(),
                    entriesByCabinet = (gsonNullable(vc.entriesByCabinet) ?: emptyMap()).mapValues { (_, list) ->
                        (gsonNullable(list) ?: emptyList()).map { ent ->
                            AssemblyVirtualEntry(
                                variant = gsonNullable(ent.variant) ?: "",
                                pdfFilename = gsonNullable(ent.pdfFilename) ?: "",
                                page = ent.page,
                                virtualPage = ent.virtualPage
                            )
                        }
                    },
                    cabinetToPages = gsonNullable(vc.cabinetToPages) ?: emptyMap(),
                    virtualPageToSource = (gsonNullable(vc.virtualPageToSource) ?: emptyMap()).mapValues { (_, sr) ->
                        AssemblyVirtualSourceRef(
                            variant = gsonNullable(sr.variant) ?: "",
                            pdfFilename = gsonNullable(sr.pdfFilename) ?: "",
                            page = sr.page,
                            cabinet = sr.cabinet
                        )
                    },
                    pageDetails = (gsonNullable(vc.pageDetails) ?: emptyMap()).mapValues { (_, detail) -> sanitizeCabinetPageDetail(detail) },
                    totalVirtualPages = vc.totalVirtualPages
                )
            }
        )
    }

    private fun sanitizeDeliveryDocumentIndex(del: DeliveryDocumentIndex?): DeliveryDocumentIndex {
        if (del == null) return DeliveryDocumentIndex()
        return DeliveryDocumentIndex(
            pdfFilename = gsonNullable(del.pdfFilename) ?: "",
            mode = del.mode,
            modeSource = del.modeSource
        )
    }

    private fun sanitizeAssemblySourceDocumentIndex(src: AssemblySourceDocumentIndex?): AssemblySourceDocumentIndex {
        if (src == null) return AssemblySourceDocumentIndex()
        return AssemblySourceDocumentIndex(
            variant = gsonNullable(src.variant) ?: "",
            pdfFilename = gsonNullable(src.pdfFilename) ?: "",
            cabinetToPages = gsonNullable(src.cabinetToPages) ?: emptyMap(),
            pageDetails = (gsonNullable(src.pageDetails) ?: emptyMap()).mapValues { (_, v) -> sanitizeCabinetPageDetail(v) }
        )
    }

    private fun sanitizeCabinetPageDetail(detail: CabinetPageDetail?): CabinetPageDetail {
        if (detail == null) return CabinetPageDetail()
        return CabinetPageDetail(
            cabinets = gsonNullable(detail.cabinets) ?: emptyList(),
            room = detail.room,
            wall = detail.wall,
            parts = (gsonNullable(detail.parts) ?: emptyList()).map { part ->
                AssemblySheetPart(
                    qty = part.qty,
                    width = part.width,
                    length = part.length,
                    description = gsonNullable(part.description) ?: "",
                    material = gsonNullable(part.material) ?: "",
                    sectionType = gsonNullable(part.sectionType) ?: "",
                    isPurchased = part.isPurchased
                )
            },
            sourceVariant = detail.sourceVariant,
            sourcePdfFilename = detail.sourcePdfFilename,
            sourcePage = detail.sourcePage
        )
    }

    private fun sanitizeHardwoodCutlistIndex(index: HardwoodCutlistIndex?): HardwoodCutlistIndex {
        if (index == null) return HardwoodCutlistIndex()
        return HardwoodCutlistIndex(
            generatedAt = index.generatedAt,
            documents = (gsonNullable(index.documents) ?: emptyList()).map { doc ->
                HardwoodDocumentIndex(
                    docType = gsonNullable(doc.docType) ?: HardwoodDocType.FACE_FRAME_CUT_LIST,
                    pdfFilename = gsonNullable(doc.pdfFilename) ?: "",
                    pageCount = doc.pageCount,
                    rows = (gsonNullable(doc.rows) ?: emptyList()).map { row ->
                        HardwoodCutlistRow(
                            rowId = gsonNullable(row.rowId) ?: "",
                            page = row.page,
                            rowOrdinal = row.rowOrdinal,
                            qty = row.qty,
                            material = row.material,
                            description = gsonNullable(row.description) ?: "",
                            width = gsonNullable(row.width) ?: "",
                            length = gsonNullable(row.length) ?: "",
                            unitType = gsonNullable(row.unitType) ?: "",
                            cabinets = gsonNullable(row.cabinets) ?: emptyList(),
                            rawCabinetText = gsonNullable(row.rawCabinetText) ?: ""
                        )
                    },
                    totals = (gsonNullable(doc.totals) ?: emptyList()).map { tot ->
                        HardwoodTotalsBlock(
                            page = tot.page,
                            sourcePages = gsonNullable(tot.sourcePages) ?: emptyList(),
                            material = tot.material,
                            widthValues = gsonNullable(tot.widthValues) ?: emptyList(),
                            lengthValues = gsonNullable(tot.lengthValues) ?: emptyList(),
                            ripsValues = gsonNullable(tot.ripsValues) ?: emptyList(),
                            unitType = gsonNullable(tot.unitType) ?: ""
                        )
                    }
                )
            }
        )
    }

    private fun sanitizeHardwoodRevisionHistory(hist: HardwoodRevisionHistory?): HardwoodRevisionHistory {
        if (hist == null) return HardwoodRevisionHistory()
        return HardwoodRevisionHistory(
            schemaVersion = hist.schemaVersion,
            updatedAt = hist.updatedAt,
            currentRevision = hist.currentRevision,
            revisions = (gsonNullable(hist.revisions) ?: emptyList()).map { rev ->
                HardwoodRevisionEntry(
                    revision = rev.revision,
                    kind = gsonNullable(rev.kind) ?: "DIFF",
                    timestamp = rev.timestamp,
                    added = (gsonNullable(rev.added) ?: emptyList()).map { sanitizeHardwoodRevisionRowSnapshot(it) },
                    removed = (gsonNullable(rev.removed) ?: emptyList()).map { sanitizeHardwoodRevisionRowSnapshot(it) },
                    modified = (gsonNullable(rev.modified) ?: emptyList()).map { mod ->
                        HardwoodRevisionModifiedEntry(
                            before = sanitizeHardwoodRevisionRowSnapshot(mod.before),
                            after = sanitizeHardwoodRevisionRowSnapshot(mod.after),
                            changedFields = gsonNullable(mod.changedFields) ?: emptyList()
                        )
                    }
                )
            },
            currentRowStates = (gsonNullable(hist.currentRowStates) ?: emptyList()).map { state ->
                HardwoodRowRevisionState(
                    docType = gsonNullable(state.docType) ?: "",
                    rowId = gsonNullable(state.rowId) ?: "",
                    latestRevision = state.latestRevision,
                    changedPendingRecut = state.changedPendingRecut
                )
            }
        )
    }

    private fun sanitizeHardwoodRevisionRowSnapshot(snap: HardwoodRevisionRowSnapshot?): HardwoodRevisionRowSnapshot {
        if (snap == null) return HardwoodRevisionRowSnapshot()
        return HardwoodRevisionRowSnapshot(
            docType = gsonNullable(snap.docType) ?: "",
            rowId = gsonNullable(snap.rowId) ?: "",
            page = snap.page,
            rowOrdinal = snap.rowOrdinal,
            qty = snap.qty,
            material = gsonNullable(snap.material) ?: "",
            description = gsonNullable(snap.description) ?: "",
            width = gsonNullable(snap.width) ?: "",
            length = gsonNullable(snap.length) ?: "",
            cabinets = gsonNullable(snap.cabinets) ?: emptyList()
        )
    }

    private fun sanitizeMaterialMetadata(metadata: MaterialMetadata?): MaterialMetadata {
        if (metadata == null) return MaterialMetadata()
        return MaterialMetadata(
            jobNumber = gsonNullable(metadata.jobNumber) ?: "",
            jobName = gsonNullable(metadata.jobName) ?: "",
            material = gsonNullable(metadata.material) ?: "",
            pdfFilename = gsonNullable(metadata.pdfFilename) ?: "",
            remakeLabel = metadata.remakeLabel,
            partGraphicsArchive = metadata.partGraphicsArchive,
            pages = (gsonNullable(metadata.pages) ?: emptyList()).map { page ->
                PageMetadata(
                    pageNumber = page.pageNumber,
                    sheetId = gsonNullable(page.sheetId) ?: "",
                    sheetFiles = gsonNullable(page.sheetFiles) ?: emptyList(),
                    sheetDimensions = page.sheetDimensions,
                    logicalSheetKey = page.logicalSheetKey,
                    isPartListContinuation = page.isPartListContinuation,
                    continuationHeadPage = page.continuationHeadPage,
                    trackingExcluded = page.trackingExcluded,
                    hiddenInApp = page.hiddenInApp,
                    partListSpansPages = page.partListSpansPages,
                    thumbnailPath = page.thumbnailPath,
                    parts = (gsonNullable(page.parts) ?: emptyList()).map { part ->
                        Part(
                            number = part.number,
                            width = part.width,
                            length = part.length,
                            name = gsonNullable(part.name) ?: "",
                            cabNumber = part.cabNumber,
                            room = gsonNullable(part.room) ?: "",
                            rotated = part.rotated,
                            graphicPath = part.graphicPath,
                            banding = part.banding
                        )
                    },
                    ocrBoxes = page.ocrBoxes?.mapValues { (_, list) ->
                        (gsonNullable(list) ?: emptyList()).map { box ->
                            OcrBoxMetadata(
                                left = box.left,
                                top = box.top,
                                right = box.right,
                                bottom = box.bottom
                            )
                        }
                    },
                    ocrSource = page.ocrSource,
                    ocrGeneratedAt = page.ocrGeneratedAt,
                    ocrVersion = page.ocrVersion,
                    remake = page.remake?.let { rm ->
                        RemakePageMetadata(
                            label = gsonNullable(rm.label) ?: "",
                            remadeParts = (gsonNullable(rm.remadeParts) ?: emptyList()).map { rp ->
                                RemadePartMetadata(
                                    partNumber = rp.partNumber,
                                    partName = gsonNullable(rp.partName) ?: "",
                                    sourcePdfFilename = rp.sourcePdfFilename,
                                    sourcePage = rp.sourcePage,
                                    sourcePartNumber = rp.sourcePartNumber
                                )
                            }
                        )
                    }
                )
            }
        )
    }
}
