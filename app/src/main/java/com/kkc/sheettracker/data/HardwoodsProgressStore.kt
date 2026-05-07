package com.kkc.sheettracker.data

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.kkc.sheettracker.data.models.HardwoodCutlistIndex
import com.kkc.sheettracker.data.models.HardwoodDocSummary
import com.kkc.sheettracker.data.models.HardwoodDocumentIndex
import com.kkc.sheettracker.data.models.HardwoodJob
import com.kkc.sheettracker.data.models.HardwoodJobSummary
import com.kkc.sheettracker.data.models.HardwoodRowProgress
import com.kkc.sheettracker.data.models.HardwoodStatusCounts
import com.kkc.sheettracker.data.models.HardwoodTabletProgress
import com.kkc.sheettracker.data.models.HardwoodTotalsTallyKey
import com.kkc.sheettracker.data.models.HardwoodTrackerActions
import com.kkc.sheettracker.data.models.HardwoodTrackerAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

private data class HardwoodRowKey(
    val docType: String,
    val rowId: String
)

class HardwoodsProgressStore(
    private val baseDir: File,
    private val tabletId: String
) {
    private data class JobCache(
        val localActions: MutableList<HardwoodTrackerAction>,
        val rowProgressMap: MutableMap<Pair<String, String>, HardwoodRowProgress>,
        val skippedCabinetMap: MutableMap<Pair<String, String>, MutableSet<String>>,
        val totalsRip10Map: MutableMap<String, Int>
    )

    private val cabinetSkipMarker = "|@cab:"
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val _progressVersion = MutableStateFlow(0L)
    val progressVersion: StateFlow<Long> = _progressVersion.asStateFlow()
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeMutexByJob = ConcurrentHashMap<String, Mutex>()
    private val cacheByJob = ConcurrentHashMap<String, JobCache>()

    private fun bumpProgressVersion() {
        _progressVersion.value = _progressVersion.value + 1L
    }

    private fun trackerDir(jobFolderName: String): File =
        File(baseDir, "$jobFolderName/.metadata/hardwoods/tracker")

    // Legacy path — only for reading existing data; no new writes go here.
    private fun legacyTrackerDir(jobFolderName: String): File =
        File(baseDir, "$jobFolderName/Hardwoods/.tracker")

    private fun tabletFile(jobFolderName: String): File = File(trackerDir(jobFolderName), "$tabletId.json")

    private fun loadTabletProgress(jobFolderName: String): HardwoodTabletProgress {
        val file = tabletFile(jobFolderName)
        if (file.exists()) {
            return try {
                gson.fromJson(file.readText(), HardwoodTabletProgress::class.java)
            } catch (_: Exception) {
                HardwoodTabletProgress(tabletId = tabletId)
            }
        }
        // Fall back to legacy location on first use after migration.
        val legacy = File(legacyTrackerDir(jobFolderName), "$tabletId.json")
        if (!legacy.exists()) return HardwoodTabletProgress(tabletId = tabletId)
        return try {
            gson.fromJson(legacy.readText(), HardwoodTabletProgress::class.java)
        } catch (_: Exception) {
            HardwoodTabletProgress(tabletId = tabletId)
        }
    }

    private fun saveTabletProgress(jobFolderName: String, progress: HardwoodTabletProgress) {
        val dir = trackerDir(jobFolderName)
        dir.mkdirs()
        tabletFile(jobFolderName).writeText(gson.toJson(progress))
    }

    private fun appendAction(
        jobFolderName: String,
        docType: String,
        rowId: String,
        action: String,
        value: Int? = null,
        totalsKey: String? = null
    ) {
        val next = HardwoodTrackerAction(
            docType = docType,
            rowId = rowId,
            totalsKey = totalsKey,
            action = action,
            value = value,
            timestamp = Instant.now().toString()
        )
        val snapshot = runCatching {
            val cache = ensureJobCache(jobFolderName)
            cache.localActions.add(next)
            applyActionToCache(cache, next)
            cache.localActions.toList()
        }.getOrElse {
            val current = loadTabletProgress(jobFolderName)
            val merged = current.actions + next
            saveTabletProgress(jobFolderName, current.copy(actions = merged))
            bumpProgressVersion()
            return
        }
        persistLocalActionsAsync(jobFolderName, snapshot)
        bumpProgressVersion()
    }

    private fun loadAllProgress(jobFolderName: String): List<HardwoodTabletProgress> {
        fun readDir(dir: File): List<HardwoodTabletProgress> {
            if (!dir.exists()) return emptyList()
            return dir.listFiles()
                ?.filter { it.isFile && it.extension.equals("json", ignoreCase = true) }
                ?.mapNotNull { file ->
                    try {
                        gson.fromJson(file.readText(), HardwoodTabletProgress::class.java)
                    } catch (_: Exception) {
                        null
                    }
                }
                ?: emptyList()
        }

        val current = readDir(trackerDir(jobFolderName))
        val legacy = readDir(legacyTrackerDir(jobFolderName))
        if (legacy.isEmpty()) return current

        // Merge: current entries take precedence for tablets also present in legacy.
        val currentTabletIds = current.map { it.tabletId }.toSet()
        return current + legacy.filter { it.tabletId !in currentTabletIds }
    }

    private fun loadAllActions(jobFolderName: String): List<HardwoodTrackerAction> {
        return loadAllProgress(jobFolderName)
            .flatMap { it.actions }
            .sortedBy { it.timestamp }
    }

    fun getRowProgressMap(jobFolderName: String): Map<Pair<String, String>, HardwoodRowProgress> {
        return ensureJobCache(jobFolderName).rowProgressMap.toMap()
    }

    fun getRowProgress(jobFolderName: String, docType: String, rowId: String): HardwoodRowProgress {
        return getRowProgressMap(jobFolderName)[docType to rowId] ?: HardwoodRowProgress()
    }

    private fun normalized(
        qty: Int,
        current: HardwoodRowProgress,
        proposedDone: Int = current.doneCount,
        proposedBad: Int = current.badCount,
        proposedSkipped: Boolean = current.skipped
    ): HardwoodRowProgress {
        val clampedQty = qty.coerceAtLeast(0)
        var done = proposedDone.coerceIn(0, clampedQty)
        var bad = proposedBad.coerceIn(0, clampedQty)
        if (done + bad > clampedQty) {
            val overflow = done + bad - clampedQty
            if (done >= bad) {
                done = (done - overflow).coerceAtLeast(0)
            } else {
                bad = (bad - overflow).coerceAtLeast(0)
            }
        }
        return HardwoodRowProgress(
            doneCount = done,
            badCount = bad,
            skipped = proposedSkipped
        )
    }

    fun setDoneCount(jobFolderName: String, docType: String, rowId: String, qty: Int, doneCount: Int) {
        val current = getRowProgress(jobFolderName, docType, rowId)
        val next = normalized(qty, current, proposedDone = doneCount)
        appendAction(
            jobFolderName = jobFolderName,
            docType = docType,
            rowId = rowId,
            action = HardwoodTrackerActions.SET_DONE_COUNT,
            value = next.doneCount
        )
    }

    fun setBadCount(jobFolderName: String, docType: String, rowId: String, qty: Int, badCount: Int) {
        val current = getRowProgress(jobFolderName, docType, rowId)
        val next = normalized(qty, current, proposedBad = badCount)
        appendAction(
            jobFolderName = jobFolderName,
            docType = docType,
            rowId = rowId,
            action = HardwoodTrackerActions.SET_BAD_COUNT,
            value = next.badCount
        )
    }

    fun setSkipped(jobFolderName: String, docType: String, rowId: String, skipped: Boolean) {
        appendAction(
            jobFolderName = jobFolderName,
            docType = docType,
            rowId = rowId,
            action = if (skipped) HardwoodTrackerActions.SET_SKIPPED else HardwoodTrackerActions.CLEAR_SKIPPED
        )
    }

    fun setCabinetSkipped(jobFolderName: String, docType: String, rowId: String, cabinet: String, skipped: Boolean) {
        val cab = cabinet.trim()
        if (cab.isEmpty()) return
        appendAction(
            jobFolderName = jobFolderName,
            docType = docType,
            rowId = encodeCabinetSkipRowId(rowId, cab),
            action = if (skipped) HardwoodTrackerActions.SET_SKIPPED else HardwoodTrackerActions.CLEAR_SKIPPED
        )
    }

    fun getSkippedCabinetMap(jobFolderName: String): Map<Pair<String, String>, Set<String>> {
        val source = ensureJobCache(jobFolderName).skippedCabinetMap
        return source.mapValues { it.value.toSet() }
    }

    fun makeTotalsRip10LineKey(docType: String, blockIndex: Int, lineIndex: Int): String {
        val normalized = HardwoodTotalsTallyKey(
            docType = docType,
            blockIndex = blockIndex.coerceAtLeast(0),
            lineIndex = lineIndex.coerceAtLeast(0)
        )
        return normalized.stableKey
    }

    fun getTotalsRip10DoneMap(jobFolderName: String): Map<String, Int> {
        return ensureJobCache(jobFolderName).totalsRip10Map.toMap()
    }

    fun getTotalsRip10Done(
        jobFolderName: String,
        docType: String,
        blockIndex: Int,
        lineIndex: Int
    ): Int {
        val key = makeTotalsRip10LineKey(docType, blockIndex, lineIndex)
        return getTotalsRip10DoneMap(jobFolderName)[key] ?: 0
    }

    fun setTotalsRip10Done(
        jobFolderName: String,
        docType: String,
        blockIndex: Int,
        lineIndex: Int,
        doneCount: Int
    ) {
        val normalizedTarget = doneCount.coerceAtLeast(0)
        val key = makeTotalsRip10LineKey(docType, blockIndex, lineIndex)
        val current = getTotalsRip10DoneMap(jobFolderName)[key] ?: 0
        val delta = normalizedTarget - current
        if (delta == 0) return
        appendAction(
            jobFolderName = jobFolderName,
            docType = docType,
            rowId = "",
            totalsKey = key,
            action = HardwoodTrackerActions.ADD_TOTALS_RIP10_DONE_COUNT,
            value = delta
        )
    }

    fun summarizeDocument(jobFolderName: String, document: HardwoodDocumentIndex): HardwoodDocSummary {
        val states = getRowProgressMap(jobFolderName)
        var totalPieces = 0
        var donePieces = 0
        var badPieces = 0
        var skippedPieces = 0

        for (row in document.rows) {
            val qty = row.qty.coerceAtLeast(0)
            val raw = states[document.docType.name to row.rowId] ?: HardwoodRowProgress()
            val state = normalized(qty, raw)
            totalPieces += qty
            if (state.skipped) {
                skippedPieces += qty
                continue
            }
            donePieces += state.doneCount
            badPieces += state.badCount
        }

        return HardwoodDocSummary(
            docType = document.docType,
            pdfFilename = document.pdfFilename,
            rowCount = document.rows.size,
            counts = HardwoodStatusCounts(
                totalPieces = totalPieces,
                donePieces = donePieces,
                badPieces = badPieces,
                skippedPieces = skippedPieces
            )
        )
    }

    fun summarizeJob(job: HardwoodJob): HardwoodJobSummary {
        val index: HardwoodCutlistIndex = job.index ?: HardwoodCutlistIndex()
        val docs = index.documents.map { summarizeDocument(job.folderName, it) }
        val total = docs.sumOf { it.counts.totalPieces }
        val done = docs.sumOf { it.counts.donePieces }
        val bad = docs.sumOf { it.counts.badPieces }
        val skipped = docs.sumOf { it.counts.skippedPieces }
        return HardwoodJobSummary(
            job = job,
            counts = HardwoodStatusCounts(
                totalPieces = total,
                donePieces = done,
                badPieces = bad,
                skippedPieces = skipped
            ),
            documents = docs
        )
    }

    private fun encodeCabinetSkipRowId(rowId: String, cabinet: String): String {
        return "$rowId$cabinetSkipMarker$cabinet"
    }

    private fun decodeCabinetSkipRowId(value: String): Pair<String, String>? {
        val idx = value.lastIndexOf(cabinetSkipMarker)
        if (idx <= 0) return null
        val rowId = value.substring(0, idx)
        val cab = value.substring(idx + cabinetSkipMarker.length)
        if (rowId.isBlank() || cab.isBlank()) return null
        return rowId to cab
    }

    private fun ensureJobCache(jobFolderName: String): JobCache {
        cacheByJob[jobFolderName]?.let { return it }
        return synchronized(cacheByJob) {
            cacheByJob[jobFolderName] ?: buildJobCache(jobFolderName).also { cacheByJob[jobFolderName] = it }
        }
    }

    private fun buildJobCache(jobFolderName: String): JobCache {
        val allProgress = loadAllProgress(jobFolderName)
        val allActions = allProgress
            .flatMap { it.actions }
            .sortedBy { it.timestamp }
        val localActions = (allProgress.firstOrNull { it.tabletId == tabletId }?.actions.orEmpty())
            .sortedBy { it.timestamp }
            .toMutableList()

        val cache = JobCache(
            localActions = localActions,
            rowProgressMap = mutableMapOf(),
            skippedCabinetMap = mutableMapOf(),
            totalsRip10Map = mutableMapOf()
        )
        allActions.forEach { applyActionToCache(cache, it) }
        return cache
    }

    private fun applyActionToCache(cache: JobCache, action: HardwoodTrackerAction) {
        decodeCabinetSkipRowId(action.rowId)?.let { decoded ->
            val key = action.docType to decoded.first
            val set = cache.skippedCabinetMap.getOrPut(key) { mutableSetOf() }
            when (action.action) {
                HardwoodTrackerActions.SET_SKIPPED -> set += decoded.second
                HardwoodTrackerActions.CLEAR_SKIPPED -> set -= decoded.second
            }
            return
        }

        if (action.rowId.isNotBlank()) {
            val key = action.docType to action.rowId
            val current = cache.rowProgressMap[key] ?: HardwoodRowProgress()
            val next = when (action.action) {
                HardwoodTrackerActions.SET_DONE_COUNT -> current.copy(doneCount = (action.value ?: 0).coerceAtLeast(0))
                HardwoodTrackerActions.SET_BAD_COUNT -> current.copy(badCount = (action.value ?: 0).coerceAtLeast(0))
                HardwoodTrackerActions.SET_SKIPPED -> current.copy(skipped = true)
                HardwoodTrackerActions.CLEAR_SKIPPED -> current.copy(skipped = false)
                else -> null
            }
            if (next != null) cache.rowProgressMap[key] = next
        }

        val totalsKey = action.totalsKey?.takeIf { it.isNotBlank() }
            ?: action.rowId.takeIf { it.isNotBlank() }
        if (totalsKey != null) {
            when (action.action) {
                HardwoodTrackerActions.ADD_TOTALS_RIP10_DONE_COUNT -> {
                    val delta = action.value ?: 0
                    val current = cache.totalsRip10Map[totalsKey] ?: 0
                    cache.totalsRip10Map[totalsKey] = (current + delta).coerceAtLeast(0)
                }
                HardwoodTrackerActions.SET_TOTALS_RIP10_DONE_COUNT -> {
                    cache.totalsRip10Map[totalsKey] = (action.value ?: 0).coerceAtLeast(0)
                }
            }
        }
    }

    private fun persistLocalActionsAsync(jobFolderName: String, actions: List<HardwoodTrackerAction>) {
        val writeMutex = writeMutexByJob.getOrPut(jobFolderName) { Mutex() }
        ioScope.launch {
            writeMutex.withLock {
                saveTabletProgress(
                    jobFolderName,
                    HardwoodTabletProgress(
                        tabletId = tabletId,
                        actions = actions
                    )
                )
            }
        }
    }
}
