package com.kkc.sheettracker.data

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.kkc.sheettracker.data.models.Material
import com.kkc.sheettracker.data.models.SheetStatus
import com.kkc.sheettracker.data.models.StatusCounts
import com.kkc.sheettracker.data.models.TabletProgress
import com.kkc.sheettracker.data.models.TrackerAction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.time.Instant
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred

private data class DraftBadPartEntry(
    val file: String,
    val page: Int,
    val fileFingerprint: String,
    val parts: List<Int> = emptyList()
)

private data class DraftBadPartState(
    val tabletId: String,
    val entries: List<DraftBadPartEntry> = emptyList()
)

private data class OcrBox(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
)

private data class OcrPageCache(
    val boxes: Map<String, List<OcrBox>> = emptyMap(),
    val savedAt: String = ""
)

private data class SheetKey(
    val file: String,
    val page: Int
)

private data class SheetIndexEntry(
    val completeByFingerprint: MutableMap<String, Boolean> = mutableMapOf(),
    var completeLegacy: Boolean = false,
    var completeHasFingerprint: Boolean = false,
    val skippedByFingerprint: MutableMap<String, Boolean> = mutableMapOf(),
    var skippedLegacy: Boolean = false,
    var skippedHasFingerprint: Boolean = false,
    val badPartsByFingerprint: MutableMap<String, MutableMap<Int, Boolean>> = mutableMapOf(),
    val badPartsLegacy: MutableMap<Int, Boolean> = mutableMapOf(),
    var badPartsHasFingerprint: Boolean = false
)

private data class MaterialTouchEntry(
    var lastTouchedPage: Int = 1,
    var lastTouchedAtMs: Long = 0L,
    var lastTouchedTimestamp: String = ""
)

private data class JobProgressIndex(
    var actionCount: Int,
    val sheets: MutableMap<SheetKey, SheetIndexEntry>,
    val materialTouches: MutableMap<String, MaterialTouchEntry>,
    val allActions: MutableList<TrackerAction>
)

data class PreparedPageKey(
    val jobFolderName: String,
    val pdfFilename: String,
    val page: Int,
    val fileFingerprint: String
)

data class PreparedPageEntry(
    val diagramBitmap: Bitmap,
    val createdAt: Long,
    val source: String
)

enum class PreparedStateInvalidationReason {
    IdentityChanged,
    FingerprintChanged,
    MemoryPressure,
    ManualRefresh,
    PathChanged
}

data class MaterialLastTouch(
    val page: Int,
    val touchedAtMs: Long,
    val timestamp: String
)

class ProgressStore(
    private val baseDir: File,
    private val tabletId: String,
    private val localStateDir: File,
    private val readOnly: Boolean = false
) {

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val ocrMemCache = ConcurrentHashMap<String, Map<Int, List<Rect>>>()
    private val preparedPageCache = mutableMapOf<PreparedPageKey, PreparedPageEntry>()
    private val preparedPageOrder = ArrayDeque<PreparedPageKey>()
    private val preparedPageInFlight = mutableMapOf<PreparedPageKey, CompletableDeferred<Bitmap?>>()
    private val preparedPageLock = Any()
    private val indexLock = Any()
    private val jobIndexes = mutableMapOf<String, JobProgressIndex>()
    private val draftStateCache = mutableMapOf<String, Pair<Long, DraftBadPartState>>()
    private val _progressVersion = MutableStateFlow(0L)
    val progressVersion: StateFlow<Long> = _progressVersion.asStateFlow()

    @Volatile
    var onSheetStatusChangedListener: ((jobFolderName: String, pdfFilename: String, page: Int, fileFingerprint: String, isComplete: Boolean) -> Unit)? = null

    private companion object {
        private const val PREPARED_CACHE_MAX_ENTRIES = 24
    }

    init {
        localStateDir.mkdirs()
    }

    private fun bumpProgressVersion() {
        _progressVersion.value = _progressVersion.value + 1L
    }

    fun invalidateJobIndex(jobFolderName: String) {
        synchronized(indexLock) {
            jobIndexes.remove(jobFolderName)
        }
        bumpProgressVersion()
    }

    fun invalidateJobIndexes(jobFolderNames: Collection<String>) {
        if (jobFolderNames.isEmpty()) return
        synchronized(indexLock) {
            jobFolderNames.forEach { jobIndexes.remove(it) }
        }
        bumpProgressVersion()
    }

    fun invalidateAllIndexes() {
        synchronized(indexLock) {
            jobIndexes.clear()
        }
        bumpProgressVersion()
    }

    fun invalidateFromTrackerFile(trackerFile: File): Boolean {
        val trackerDir = trackerFile.parentFile ?: return false
        if (!trackerDir.name.equals(".tracker", ignoreCase = true)) return false
        val cncDir = trackerDir.parentFile ?: return false
        if (!cncDir.name.equals("CNC", ignoreCase = true)) return false
        val jobDir = cncDir.parentFile ?: return false
        if (jobDir.name.isBlank()) return false
        invalidateJobIndex(jobDir.name)
        return true
    }

    private fun trackerDir(jobFolderName: String): File {
        return File(baseDir, "$jobFolderName/CNC/.tracker")
    }

    private fun tabletFile(jobFolderName: String): File {
        return File(trackerDir(jobFolderName), "$tabletId.json")
    }

    private fun draftDir(jobFolderName: String): File {
        return File(localStateDir, "drafts/$jobFolderName")
    }

    private fun draftFile(jobFolderName: String): File {
        return File(draftDir(jobFolderName), "$tabletId.json")
    }

    private fun ocrCacheFile(
        jobFolderName: String,
        pdfFilename: String,
        page: Int,
        fileFingerprint: String
    ): File {
        val safePdf = safeName(pdfFilename)
        val safeFp = safeName(fileFingerprint)
        return File(localStateDir, "ocr/$jobFolderName/$safePdf/$safeFp/p$page.json")
    }

    private fun ocrCacheKey(
        jobFolderName: String,
        pdfFilename: String,
        page: Int,
        fileFingerprint: String
    ): String = "$jobFolderName|$pdfFilename|$page|$fileFingerprint"

    private fun safeName(value: String): String = value.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private fun loadTabletProgress(jobFolderName: String): TabletProgress {
        val file = tabletFile(jobFolderName)
        if (!file.exists()) return TabletProgress(tabletId)
        return try {
            sanitizeProgress(
                gson.fromJson(file.readText(), TabletProgress::class.java),
                fallbackTabletId = tabletId
            ) ?: TabletProgress(tabletId)
        } catch (_: Exception) {
            TabletProgress(tabletId)
        }
    }

    private fun saveTabletProgress(jobFolderName: String, progress: TabletProgress) {
        val dir = trackerDir(jobFolderName)
        dir.mkdirs()
        tabletFile(jobFolderName).writeText(gson.toJson(progress))
    }

    private fun loadDraftState(jobFolderName: String): DraftBadPartState {
        val file = draftFile(jobFolderName)
        if (!file.exists()) {
            synchronized(indexLock) { draftStateCache.remove(jobFolderName) }
            return DraftBadPartState(tabletId)
        }
        val mtime = file.lastModified()
        synchronized(indexLock) {
            val cached = draftStateCache[jobFolderName]
            if (cached != null && cached.first == mtime) return cached.second
        }
        val state = try {
            gson.fromJson(file.readText(), DraftBadPartState::class.java)
        } catch (_: Exception) {
            DraftBadPartState(tabletId)
        }
        synchronized(indexLock) { draftStateCache[jobFolderName] = mtime to state }
        return state
    }

    private fun saveDraftState(jobFolderName: String, state: DraftBadPartState) {
        val dir = draftDir(jobFolderName)
        dir.mkdirs()
        val file = draftFile(jobFolderName)
        file.writeText(gson.toJson(state))
        synchronized(indexLock) { draftStateCache[jobFolderName] = file.lastModified() to state }
    }

    private fun appendAction(
        jobFolderName: String,
        pdfFilename: String,
        page: Int,
        action: String,
        fileFingerprint: String,
        part: Int? = null
    ) {
        if (readOnly) return
        val progress = loadTabletProgress(jobFolderName)
        val entry = TrackerAction(
            file = pdfFilename,
            page = page,
            part = part,
            action = action,
            timestamp = Instant.now().toString(),
            fileFingerprint = fileFingerprint
        )
        saveTabletProgress(jobFolderName, progress.copy(actions = progress.actions + entry))
        applyActionToIndex(jobFolderName, entry)
        bumpProgressVersion()
    }

    fun loadAllProgress(jobFolderName: String): List<TabletProgress> {
        val dir = trackerDir(jobFolderName)
        if (!dir.exists()) return emptyList()

        return dir.listFiles()
            ?.filter { it.isFile && it.extension.equals("json", ignoreCase = true) && !it.name.startsWith(".") }
            ?.mapNotNull { file ->
                try {
                    sanitizeProgress(
                        gson.fromJson(file.readText(), TabletProgress::class.java),
                        fallbackTabletId = file.nameWithoutExtension
                    )
                } catch (_: Exception) {
                    Log.w("KKC_PROGRESS", "Skipping malformed tracker file: ${file.absolutePath}")
                    null
                }
            }
            ?: emptyList()
    }

    private fun sanitizeProgress(progress: TabletProgress?, fallbackTabletId: String): TabletProgress? {
        progress ?: return null
        val safeTabletId = (progress.tabletId as String?).orEmpty().ifBlank { fallbackTabletId }
        val safeActions = (progress.actions as? List<*>).orEmpty()
            .mapNotNull { it as? TrackerAction }
            .mapNotNull { sanitizeAction(it) }
        return TabletProgress(
            tabletId = safeTabletId,
            actions = safeActions
        )
    }

    private fun sanitizeAction(action: TrackerAction): TrackerAction? {
        val safeFile = (action.file as String?).orEmpty().trim()
        val safeAction = (action.action as String?).orEmpty().trim()
        val safeTimestamp = (action.timestamp as String?).orEmpty().trim()
        if (safeFile.isEmpty() || safeAction.isEmpty()) return null
        return TrackerAction(
            file = safeFile,
            page = action.page,
            part = action.part,
            action = safeAction,
            timestamp = safeTimestamp,
            fileFingerprint = action.fileFingerprint?.trim()
        )
    }

    // Cache freshness relies on explicit invalidation (TrackerChangeMonitor's FileObserver/poll
    // for other tablets' writes, applyActionToIndex for this tablet's own writes) rather than
    // re-validating against disk on every read — re-listing the tracker dir on every page lookup
    // was the dominant cost in dashboard/job-browser derivation on networked job boards.
    private fun ensureJobIndex(jobFolderName: String): JobProgressIndex {
        synchronized(indexLock) {
            jobIndexes[jobFolderName]?.let { return it }
        }

        val built = buildJobIndex(jobFolderName)
        synchronized(indexLock) {
            val current = jobIndexes[jobFolderName]
            if (current == null) {
                jobIndexes[jobFolderName] = built
                bumpProgressVersion()
                return built
            }
            return current
        }
    }

    private fun buildJobIndex(jobFolderName: String): JobProgressIndex {
        val startedAt = System.currentTimeMillis()
        val allActions = loadAllProgress(jobFolderName)
            .flatMap { it.actions }
            .sortedBy { it.timestamp }

        val sheets = mutableMapOf<SheetKey, SheetIndexEntry>()
        val materialTouches = mutableMapOf<String, MaterialTouchEntry>()
        allActions.forEach { action -> applyActionToSheets(sheets, materialTouches, action) }

        Log.i(
            "KKC_PROGRESS",
            "Built index job=$jobFolderName actions=${allActions.size} sheets=${sheets.size} in ${System.currentTimeMillis() - startedAt}ms"
        )

        return JobProgressIndex(
            actionCount = allActions.size,
            sheets = sheets,
            materialTouches = materialTouches,
            allActions = allActions.toMutableList()
        )
    }

    private fun applyActionToIndex(jobFolderName: String, action: TrackerAction) {
        synchronized(indexLock) {
            val index = jobIndexes[jobFolderName] ?: return
            applyActionToSheets(index.sheets, index.materialTouches, action)
            index.actionCount += 1
            index.allActions += action
        }
    }

    private fun parseTimestampMillis(timestamp: String?): Long {
        if (timestamp.isNullOrBlank()) return 0L
        return try {
            Instant.parse(timestamp).toEpochMilli()
        } catch (_: Exception) {
            0L
        }
    }

    private fun applyActionToSheets(
        sheets: MutableMap<SheetKey, SheetIndexEntry>,
        materialTouches: MutableMap<String, MaterialTouchEntry>,
        action: TrackerAction
    ) {
        val entry = sheets.getOrPut(SheetKey(action.file, action.page)) { SheetIndexEntry() }
        val fp = action.fileFingerprint?.takeIf { it.isNotBlank() }
        val touchedAtMs = parseTimestampMillis(action.timestamp)
        val touchEntry = materialTouches.getOrPut(action.file) { MaterialTouchEntry() }
        if (touchedAtMs >= touchEntry.lastTouchedAtMs) {
            touchEntry.lastTouchedAtMs = touchedAtMs
            touchEntry.lastTouchedPage = action.page.coerceAtLeast(1)
            touchEntry.lastTouchedTimestamp = action.timestamp
        }

        when (action.action) {
            "view" -> Unit
            "complete", "uncomplete" -> {
                val value = action.action == "complete"
                if (fp == null) {
                    entry.completeLegacy = value
                } else {
                    entry.completeHasFingerprint = true
                    entry.completeByFingerprint[fp] = value
                }
            }
            "skip", "unskip" -> {
                val value = action.action == "skip"
                if (fp == null) {
                    entry.skippedLegacy = value
                } else {
                    entry.skippedHasFingerprint = true
                    entry.skippedByFingerprint[fp] = value
                }
            }
            "bad_part", "unbad_part" -> {
                val partNum = action.part ?: return
                val value = action.action == "bad_part"
                if (fp == null) {
                    entry.badPartsLegacy[partNum] = value
                } else {
                    entry.badPartsHasFingerprint = true
                    val partMap = entry.badPartsByFingerprint.getOrPut(fp) { mutableMapOf() }
                    partMap[partNum] = value
                }
            }
            "bad_part_submitted" -> Unit // reporting marker only — touch timestamp already updated above
        }
    }

    private fun resolveSheetEntry(
        jobFolderName: String,
        pdfFilename: String,
        page: Int
    ): SheetIndexEntry? {
        val index = ensureJobIndex(jobFolderName)
        return index.sheets[SheetKey(pdfFilename, page)]
    }

    private fun resolveComplete(entry: SheetIndexEntry?, fileFingerprint: String): Boolean {
        if (entry == null) return false
        return if (entry.completeHasFingerprint) {
            entry.completeByFingerprint[fileFingerprint] ?: false
        } else {
            entry.completeLegacy
        }
    }

    private fun resolveSkipped(entry: SheetIndexEntry?, fileFingerprint: String): Boolean {
        if (entry == null) return false
        return if (entry.skippedHasFingerprint) {
            entry.skippedByFingerprint[fileFingerprint] ?: false
        } else {
            entry.skippedLegacy
        }
    }

    private fun resolveCommittedBadParts(entry: SheetIndexEntry?, fileFingerprint: String): Set<Int> {
        if (entry == null) return emptySet()
        val raw = if (entry.badPartsHasFingerprint) {
            entry.badPartsByFingerprint[fileFingerprint] ?: emptyMap()
        } else {
            entry.badPartsLegacy
        }
        return raw.filterValues { it }.keys
    }

    fun markSheetComplete(jobFolderName: String, pdfFilename: String, page: Int, fileFingerprint: String) {
        if (readOnly) return
        if (isSheetSkipped(jobFolderName, pdfFilename, page, fileFingerprint)) {
            appendAction(jobFolderName, pdfFilename, page, "unskip", fileFingerprint)
        }
        appendAction(jobFolderName, pdfFilename, page, "complete", fileFingerprint)
        val draftParts = getDraftBadParts(jobFolderName, pdfFilename, page, fileFingerprint)
        draftParts.forEach { part ->
            appendAction(jobFolderName, pdfFilename, page, "bad_part", fileFingerprint, part = part)
        }
        clearDraftBadParts(jobFolderName, pdfFilename, page, fileFingerprint)
        onSheetStatusChangedListener?.invoke(jobFolderName, pdfFilename, page, fileFingerprint, true)
    }

    fun unmarkSheetComplete(jobFolderName: String, pdfFilename: String, page: Int, fileFingerprint: String) {
        if (readOnly) return
        appendAction(jobFolderName, pdfFilename, page, "uncomplete", fileFingerprint)
        onSheetStatusChangedListener?.invoke(jobFolderName, pdfFilename, page, fileFingerprint, false)
    }

    fun markSheetSkipped(jobFolderName: String, pdfFilename: String, page: Int, fileFingerprint: String) {
        if (readOnly) return
        appendAction(jobFolderName, pdfFilename, page, "skip", fileFingerprint)
    }

    fun unmarkSheetSkipped(jobFolderName: String, pdfFilename: String, page: Int, fileFingerprint: String) {
        if (readOnly) return
        appendAction(jobFolderName, pdfFilename, page, "unskip", fileFingerprint)
    }

    fun markSheetViewed(jobFolderName: String, pdfFilename: String, page: Int, fileFingerprint: String) {
        if (readOnly) return
        appendAction(jobFolderName, pdfFilename, page, "view", fileFingerprint)
    }

    fun isSheetComplete(jobFolderName: String, pdfFilename: String, page: Int, fileFingerprint: String): Boolean {
        val entry = resolveSheetEntry(jobFolderName, pdfFilename, page)
        return resolveComplete(entry, fileFingerprint)
    }

    fun isSheetSkipped(jobFolderName: String, pdfFilename: String, page: Int, fileFingerprint: String): Boolean {
        val entry = resolveSheetEntry(jobFolderName, pdfFilename, page)
        return resolveSkipped(entry, fileFingerprint)
    }

    fun getDraftBadParts(
        jobFolderName: String,
        pdfFilename: String,
        page: Int,
        fileFingerprint: String
    ): Set<Int> {
        val state = loadDraftState(jobFolderName)
        return state.entries.firstOrNull {
            it.file == pdfFilename && it.page == page && it.fileFingerprint == fileFingerprint
        }?.parts?.toSet() ?: emptySet()
    }

    fun clearDraftBadParts(
        jobFolderName: String,
        pdfFilename: String,
        page: Int,
        fileFingerprint: String
    ) {
        if (readOnly) return
        val state = loadDraftState(jobFolderName)
        val next = state.copy(
            entries = state.entries.filterNot {
                it.file == pdfFilename && it.page == page && it.fileFingerprint == fileFingerprint
            }
        )
        saveDraftState(jobFolderName, next)
        bumpProgressVersion()
    }

    private fun toggleDraftBadPart(
        jobFolderName: String,
        pdfFilename: String,
        page: Int,
        fileFingerprint: String,
        partNumber: Int
    ) {
        val state = loadDraftState(jobFolderName)
        val idx = state.entries.indexOfFirst {
            it.file == pdfFilename && it.page == page && it.fileFingerprint == fileFingerprint
        }
        val existingParts = if (idx >= 0) state.entries[idx].parts.toMutableSet() else mutableSetOf()
        if (partNumber in existingParts) existingParts.remove(partNumber) else existingParts.add(partNumber)

        val nextEntries = state.entries.toMutableList()
        if (idx >= 0) {
            if (existingParts.isEmpty()) {
                nextEntries.removeAt(idx)
            } else {
                nextEntries[idx] = nextEntries[idx].copy(parts = existingParts.sorted())
            }
        } else if (existingParts.isNotEmpty()) {
            nextEntries += DraftBadPartEntry(
                file = pdfFilename,
                page = page,
                fileFingerprint = fileFingerprint,
                parts = existingParts.sorted()
            )
        }
        saveDraftState(jobFolderName, state.copy(entries = nextEntries))
        bumpProgressVersion()
    }

    fun toggleBadPart(jobFolderName: String, pdfFilename: String, page: Int, fileFingerprint: String, partNumber: Int) {
        if (readOnly) return
        if (isSheetComplete(jobFolderName, pdfFilename, page, fileFingerprint)) {
            val isBad = isPartBad(jobFolderName, pdfFilename, page, fileFingerprint, partNumber)
            appendAction(
                jobFolderName = jobFolderName,
                pdfFilename = pdfFilename,
                page = page,
                action = if (isBad) "unbad_part" else "bad_part",
                fileFingerprint = fileFingerprint,
                part = partNumber
            )
        } else {
            toggleDraftBadPart(jobFolderName, pdfFilename, page, fileFingerprint, partNumber)
        }
    }

    fun isPartBad(
        jobFolderName: String,
        pdfFilename: String,
        page: Int,
        fileFingerprint: String,
        partNumber: Int
    ): Boolean {
        val committed = getCommittedBadParts(jobFolderName, pdfFilename, page, fileFingerprint)
        val drafts = getDraftBadParts(jobFolderName, pdfFilename, page, fileFingerprint)
        return partNumber in committed || partNumber in drafts
    }

    private fun getCommittedBadParts(
        jobFolderName: String,
        pdfFilename: String,
        page: Int,
        fileFingerprint: String
    ): Set<Int> {
        val entry = resolveSheetEntry(jobFolderName, pdfFilename, page)
        return resolveCommittedBadParts(entry, fileFingerprint)
    }

    fun getBadParts(
        jobFolderName: String,
        pdfFilename: String,
        page: Int,
        fileFingerprint: String,
        includeDraft: Boolean = true
    ): Set<Int> {
        val committed = getCommittedBadParts(jobFolderName, pdfFilename, page, fileFingerprint)
        if (!includeDraft) return committed
        return committed + getDraftBadParts(jobFolderName, pdfFilename, page, fileFingerprint)
    }

    /**
     * Returns the count of bad parts on [pdfFilename]/[fileFingerprint] that have not yet been
     * submitted for engineer notification. Includes both committed bad_part actions (no matching
     * bad_part_submitted) AND draft bad parts (marked bad but sheet not yet completed), so the
     * "Report Bad Parts" button appears as soon as any part is marked bad.
     */
    fun getPendingBadPartsForMaterial(
        jobFolderName: String,
        pdfFilename: String,
        fileFingerprint: String
    ): Int {
        // Committed pending: bad_part actions with no corresponding bad_part_submitted.
        // Reuses the cached job index's action list instead of re-reading/re-parsing the
        // tracker directory from scratch for every material.
        val allActions = synchronized(indexLock) { ensureJobIndex(jobFolderName).allActions.toList() }
            .filter { it.file == pdfFilename && (it.fileFingerprint ?: "") == fileFingerprint }
            .sortedBy { it.timestamp }
        val pending = mutableSetOf<Pair<Int, Int>>() // (page, partNumber)
        for (action in allActions) {
            val partNum = action.part ?: continue
            val pair = Pair(action.page, partNum)
            when (action.action) {
                "bad_part" -> pending.add(pair)
                "unbad_part" -> pending.remove(pair)
                "bad_part_submitted" -> pending.remove(pair)
            }
        }
        // Draft pending: bad parts marked on incomplete sheets (not yet in tracker JSON)
        val draftCount = loadDraftState(jobFolderName).entries
            .filter { it.file == pdfFilename && it.fileFingerprint == fileFingerprint }
            .sumOf { it.parts.size }
        return pending.size + draftCount
    }

    /**
     * Appends a "bad_part_submitted" action for every currently-pending bad part on
     * [pdfFilename]/[fileFingerprint], signalling the Ready Jobs Watcher to alert the engineer.
     */
    fun submitPendingBadParts(
        jobFolderName: String,
        pdfFilename: String,
        fileFingerprint: String
    ) {
        if (readOnly) return
        val allActions = synchronized(indexLock) { ensureJobIndex(jobFolderName).allActions.toList() }
            .filter { it.file == pdfFilename && (it.fileFingerprint ?: "") == fileFingerprint }
            .sortedBy { it.timestamp }
        val pending = mutableSetOf<Pair<Int, Int>>() // (page, partNumber)
        for (action in allActions) {
            val partNum = action.part ?: continue
            val pair = Pair(action.page, partNum)
            when (action.action) {
                "bad_part" -> pending.add(pair)
                "unbad_part" -> pending.remove(pair)
                "bad_part_submitted" -> pending.remove(pair)
            }
        }
        for ((page, partNum) in pending.sortedWith(compareBy({ it.first }, { it.second }))) {
            appendAction(
                jobFolderName = jobFolderName,
                pdfFilename = pdfFilename,
                page = page,
                action = "bad_part_submitted",
                fileFingerprint = fileFingerprint,
                part = partNum
            )
        }
    }

    fun resolveBadPartsOnSheet(
        jobFolderName: String,
        pdfFilename: String,
        page: Int,
        fileFingerprint: String
    ) {
        if (readOnly) return
        val committed = getCommittedBadParts(jobFolderName, pdfFilename, page, fileFingerprint)
        committed.forEach { partNumber ->
            appendAction(
                jobFolderName = jobFolderName,
                pdfFilename = pdfFilename,
                page = page,
                action = "unbad_part",
                fileFingerprint = fileFingerprint,
                part = partNumber
            )
        }
        clearDraftBadParts(jobFolderName, pdfFilename, page, fileFingerprint)
    }

    fun resolveSpecificBadParts(
        jobFolderName: String,
        pdfFilename: String,
        page: Int,
        fileFingerprint: String,
        partNumbers: Set<Int>
    ): Int {
        if (readOnly) return 0
        if (partNumbers.isEmpty()) return 0
        val committed = getCommittedBadParts(jobFolderName, pdfFilename, page, fileFingerprint)
        val targets = committed.intersect(partNumbers)
        targets.forEach { partNumber ->
            appendAction(
                jobFolderName = jobFolderName,
                pdfFilename = pdfFilename,
                page = page,
                action = "unbad_part",
                fileFingerprint = fileFingerprint,
                part = partNumber
            )
        }
        return targets.size
    }

    fun getSheetStatus(jobFolderName: String, pdfFilename: String, page: Int, fileFingerprint: String): SheetStatus {
        val entry = resolveSheetEntry(jobFolderName, pdfFilename, page)
        val isComplete = resolveComplete(entry, fileFingerprint)
        val isSkipped = resolveSkipped(entry, fileFingerprint)
        val hasCommittedBadParts = isComplete && resolveCommittedBadParts(entry, fileFingerprint).isNotEmpty()

        return when {
            hasCommittedBadParts -> SheetStatus.HAS_BAD_PARTS
            isSkipped -> SheetStatus.SKIPPED
            isComplete -> SheetStatus.COMPLETE
            else -> SheetStatus.NOT_STARTED
        }
    }

    fun getMaterialTrackablePages(material: Material): List<Int> {
        val metadataPages = material.metadata?.pages.orEmpty()
        val visibleFromMetadata = metadataPages
            .filterNot { it.hiddenInApp || it.trackingExcluded || it.isPartListContinuation }
            .mapNotNull { page ->
                val number = page.pageNumber
                number.takeIf { it in 1..material.pageCount }
            }
            .distinct()
            .sorted()
        return if (visibleFromMetadata.isNotEmpty()) visibleFromMetadata else (1..material.pageCount).toList()
    }

    fun getMaterialStatusCounts(jobFolderName: String, material: Material): StatusCounts {
        var complete = 0
        var bad = 0
        var skipped = 0
        var notStarted = 0

        val index = ensureJobIndex(jobFolderName)
        val visiblePages = getMaterialTrackablePages(material)
        for (page in visiblePages) {
            val entry = index.sheets[SheetKey(material.pdfFilename, page)]
            val isComplete = resolveComplete(entry, material.fileFingerprint)
            val isSkipped = resolveSkipped(entry, material.fileFingerprint)
            val hasBad = isComplete && resolveCommittedBadParts(entry, material.fileFingerprint).isNotEmpty()

            when {
                hasBad -> {
                    complete++
                    bad++
                }
                isSkipped -> skipped++
                isComplete -> complete++
                else -> notStarted++
            }
        }

        return StatusCounts(
            total = visiblePages.size,
            complete = complete,
            bad = bad,
            skipped = skipped,
            notStarted = notStarted
        )
    }

    fun getJobStatusCounts(jobFolderName: String, materials: List<Material>): StatusCounts {
        var total = 0
        var complete = 0
        var bad = 0
        var skipped = 0
        var notStarted = 0

        val index = ensureJobIndex(jobFolderName)
        materials.forEach { material ->
            for (page in getMaterialTrackablePages(material)) {
                total++
                val entry = index.sheets[SheetKey(material.pdfFilename, page)]
                val isComplete = resolveComplete(entry, material.fileFingerprint)
                val isSkipped = resolveSkipped(entry, material.fileFingerprint)
                val hasBad = isComplete && resolveCommittedBadParts(entry, material.fileFingerprint).isNotEmpty()

                when {
                    hasBad -> {
                        complete++
                        bad++
                    }
                    isSkipped -> skipped++
                    isComplete -> complete++
                    else -> notStarted++
                }
            }
        }

        return StatusCounts(total, complete, bad, skipped, notStarted)
    }

    fun getMaterialLastTouches(jobFolderName: String): Map<String, MaterialLastTouch> {
        val index = ensureJobIndex(jobFolderName)
        return index.materialTouches.mapValues { (_, touch) ->
            MaterialLastTouch(
                page = touch.lastTouchedPage.coerceAtLeast(1),
                touchedAtMs = touch.lastTouchedAtMs,
                timestamp = touch.lastTouchedTimestamp
            )
        }
    }

    /**
     * Returns last-touch data for this tablet only — used for per-tablet
     * "recent jobs" lists so each device shows its own history.
     */
    fun getLocalMaterialLastTouches(jobFolderName: String): Map<String, MaterialLastTouch> {
        val progress = loadTabletProgress(jobFolderName)
        val touches = mutableMapOf<String, MaterialTouchEntry>()
        progress.actions.sortedBy { it.timestamp }.forEach { action ->
            val ms = parseTimestampMillis(action.timestamp)
            val entry = touches.getOrPut(action.file) { MaterialTouchEntry() }
            if (ms >= entry.lastTouchedAtMs) {
                entry.lastTouchedAtMs = ms
                entry.lastTouchedPage = action.page.coerceAtLeast(1)
                entry.lastTouchedTimestamp = action.timestamp
            }
        }
        return touches.mapValues { (_, touch) ->
            MaterialLastTouch(
                page = touch.lastTouchedPage.coerceAtLeast(1),
                touchedAtMs = touch.lastTouchedAtMs,
                timestamp = touch.lastTouchedTimestamp
            )
        }
    }

    fun hasOcrCache(jobFolderName: String, pdfFilename: String, page: Int, fileFingerprint: String): Boolean {
        val key = ocrCacheKey(jobFolderName, pdfFilename, page, fileFingerprint)
        if (ocrMemCache.containsKey(key)) return true
        return ocrCacheFile(jobFolderName, pdfFilename, page, fileFingerprint).exists()
    }

    fun getOcrCache(jobFolderName: String, pdfFilename: String, page: Int, fileFingerprint: String): Map<Int, List<Rect>>? {
        val key = ocrCacheKey(jobFolderName, pdfFilename, page, fileFingerprint)
        ocrMemCache[key]?.let { return it }

        val file = ocrCacheFile(jobFolderName, pdfFilename, page, fileFingerprint)
        if (!file.exists()) return null
        return try {
            val raw = gson.fromJson(file.readText(), OcrPageCache::class.java)
            val parsed = raw.boxes.mapNotNull { (k, v) ->
                val num = k.toIntOrNull() ?: return@mapNotNull null
                num to v.map { Rect(it.left, it.top, it.right, it.bottom) }
            }.toMap()
            ocrMemCache[key] = parsed
            parsed
        } catch (_: Exception) {
            null
        }
    }

    fun saveOcrCache(
        jobFolderName: String,
        pdfFilename: String,
        page: Int,
        fileFingerprint: String,
        boxes: Map<Int, List<Rect>>
    ) {
        val key = ocrCacheKey(jobFolderName, pdfFilename, page, fileFingerprint)
        ocrMemCache[key] = boxes

        val file = ocrCacheFile(jobFolderName, pdfFilename, page, fileFingerprint)
        file.parentFile?.mkdirs()
        val data = OcrPageCache(
            boxes = boxes.mapKeys { it.key.toString() }.mapValues { (_, rects) ->
                rects.map { OcrBox(it.left, it.top, it.right, it.bottom) }
            },
            savedAt = Instant.now().toString()
        )
        file.writeText(gson.toJson(data))
    }

    fun getPreparedPageEntry(key: PreparedPageKey): PreparedPageEntry? {
        synchronized(preparedPageLock) {
            val existing = preparedPageCache[key] ?: return null
            preparedPageOrder.remove(key)
            preparedPageOrder.addLast(key)
            return existing
        }
    }

    suspend fun getOrPrepareDiagramBitmap(
        key: PreparedPageKey,
        source: String,
        producer: suspend () -> Bitmap?
    ): Bitmap? {
        getPreparedPageEntry(key)?.let { entry ->
            Log.d("KKC_PREPARED_STATE", "prewarm_reused key=$key source=$source")
            return entry.diagramBitmap
        }

        val deferred: CompletableDeferred<Bitmap?>
        val isOwner: Boolean
        synchronized(preparedPageLock) {
            getPreparedPageEntry(key)?.let { entry ->
                Log.d("KKC_PREPARED_STATE", "prewarm_reused key=$key source=$source")
                return entry.diagramBitmap
            }
            val inFlight = preparedPageInFlight[key]
            if (inFlight != null) {
                deferred = inFlight
                isOwner = false
            } else {
                deferred = CompletableDeferred()
                preparedPageInFlight[key] = deferred
                isOwner = true
            }
        }

        if (!isOwner) {
            Log.d("KKC_PREPARED_STATE", "prewarm_reused key=$key source=${source}_inflight")
            return deferred.await()
        }

        Log.d("KKC_PREPARED_STATE", "prewarm_started key=$key source=$source")
        return try {
            val produced = producer()
            if (produced != null) {
                putPreparedPageEntry(
                    key = key,
                    entry = PreparedPageEntry(
                        diagramBitmap = produced,
                        createdAt = System.currentTimeMillis(),
                        source = source
                    )
                )
            }
            deferred.complete(produced)
            produced
        } catch (t: Throwable) {
            deferred.completeExceptionally(t)
            throw t
        } finally {
            synchronized(preparedPageLock) {
                preparedPageInFlight.remove(key)
            }
        }
    }

    fun invalidatePreparedPage(
        key: PreparedPageKey,
        reason: PreparedStateInvalidationReason
    ) {
        synchronized(preparedPageLock) {
            preparedPageCache.remove(key)
            preparedPageOrder.remove(key)
            preparedPageInFlight.remove(key)
        }
        Log.d("KKC_PREPARED_STATE", "prewarm_invalidated_reason=$reason key=$key")
    }

    fun invalidatePreparedPagesForDocument(
        jobFolderName: String,
        pdfFilename: String,
        reason: PreparedStateInvalidationReason
    ) {
        synchronized(preparedPageLock) {
            val keys = preparedPageCache.keys.filter {
                it.jobFolderName == jobFolderName && it.pdfFilename == pdfFilename
            }
            keys.forEach {
                preparedPageCache.remove(it)
                preparedPageOrder.remove(it)
                preparedPageInFlight.remove(it)
            }
        }
        Log.d(
            "KKC_PREPARED_STATE",
            "prewarm_invalidated_reason=$reason scope=document job=$jobFolderName pdf=$pdfFilename"
        )
    }

    fun invalidatePreparedPagesForJob(
        jobFolderName: String,
        reason: PreparedStateInvalidationReason
    ) {
        synchronized(preparedPageLock) {
            val keys = preparedPageCache.keys.filter { it.jobFolderName == jobFolderName }
            keys.forEach {
                preparedPageCache.remove(it)
                preparedPageOrder.remove(it)
                preparedPageInFlight.remove(it)
            }
        }
        Log.d("KKC_PREPARED_STATE", "prewarm_invalidated_reason=$reason scope=job job=$jobFolderName")
    }

    fun invalidateAllPreparedPages(reason: PreparedStateInvalidationReason) {
        synchronized(preparedPageLock) {
            preparedPageCache.clear()
            preparedPageOrder.clear()
            preparedPageInFlight.clear()
        }
        Log.d("KKC_PREPARED_STATE", "prewarm_invalidated_reason=$reason scope=all")
    }

    private fun putPreparedPageEntry(
        key: PreparedPageKey,
        entry: PreparedPageEntry
    ) {
        synchronized(preparedPageLock) {
            preparedPageCache[key] = entry
            preparedPageOrder.remove(key)
            preparedPageOrder.addLast(key)
            while (preparedPageOrder.size > PREPARED_CACHE_MAX_ENTRIES) {
                val stale = preparedPageOrder.removeFirst()
                preparedPageCache.remove(stale)
                Log.d(
                    "KKC_PREPARED_STATE",
                    "prewarm_invalidated_reason=${PreparedStateInvalidationReason.MemoryPressure} key=$stale"
                )
            }
        }
    }

    fun pruneLocalStateForJob(jobFolderName: String, materials: List<Material>) {
        if (readOnly) return
        val validFingerprintsByPdf = materials.associate { it.pdfFilename to it.fileFingerprint }
        // This method runs from app-state derivation; precomputing the safe-name map avoids
        // scanning every PDF key for every OCR directory (O(D*M) -> O(D+M)).
        val originalPdfBySafeName = mutableMapOf<String, String>()
        validFingerprintsByPdf.keys.forEach { originalPdf ->
            originalPdfBySafeName.putIfAbsent(safeName(originalPdf), originalPdf)
        }

        val draft = loadDraftState(jobFolderName)
        val filteredDraftEntries = draft.entries.filter { entry ->
            validFingerprintsByPdf[entry.file] == entry.fileFingerprint
        }
        if (filteredDraftEntries.size != draft.entries.size) {
            saveDraftState(jobFolderName, draft.copy(entries = filteredDraftEntries))
            bumpProgressVersion()
        }

        val ocrJobDir = File(localStateDir, "ocr/$jobFolderName")
        if (ocrJobDir.exists()) {
            ocrJobDir.listFiles()?.forEach { pdfDir ->
                if (!pdfDir.isDirectory) return@forEach
                val originalPdf = originalPdfBySafeName[pdfDir.name]
                if (originalPdf == null) {
                    pdfDir.deleteRecursively()
                    return@forEach
                }
                val validFpSafe = safeName(validFingerprintsByPdf.getValue(originalPdf))
                pdfDir.listFiles()?.forEach { fpDir ->
                    if (fpDir.isDirectory && fpDir.name != validFpSafe) {
                        fpDir.deleteRecursively()
                    }
                }
            }
        }

        val validKeys = mutableSetOf<String>()
        materials.forEach { material ->
            (1..material.pageCount).forEach { page ->
                validKeys += ocrCacheKey(jobFolderName, material.pdfFilename, page, material.fileFingerprint)
            }
        }
        ocrMemCache.keys
            .filter { it.startsWith("$jobFolderName|") && it !in validKeys }
            .forEach { stale -> ocrMemCache.remove(stale) }

        synchronized(preparedPageLock) {
            val validPreparedKeys = mutableSetOf<PreparedPageKey>()
            materials.forEach { material ->
                (1..material.pageCount).forEach { page ->
                    validPreparedKeys += PreparedPageKey(
                        jobFolderName = jobFolderName,
                        pdfFilename = material.pdfFilename,
                        page = page,
                        fileFingerprint = material.fileFingerprint
                    )
                }
            }
            val stale = preparedPageCache.keys.filter {
                it.jobFolderName == jobFolderName && it !in validPreparedKeys
            }
            stale.forEach {
                preparedPageCache.remove(it)
                preparedPageOrder.remove(it)
                preparedPageInFlight.remove(it)
                Log.d(
                    "KKC_PREPARED_STATE",
                    "prewarm_invalidated_reason=${PreparedStateInvalidationReason.FingerprintChanged} key=$it"
                )
            }
        }
    }
}
