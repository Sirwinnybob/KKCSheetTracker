package com.kkc.sheettracker.data

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
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

/** CNC op-name mapping mirroring Ready Jobs Watcher's tracker_action_stream.py _CNC_OP_MAP.
 * Top-level (not private) so tests in this package can decode ndjson events directly. */
internal fun cncOpForAction(action: String): String = when (action) {
    "complete" -> "set_complete_true"
    "uncomplete" -> "set_complete_false"
    "skip" -> "set_skipped_true"
    "unskip" -> "set_skipped_false"
    "bad_part" -> "set_bad_part_true"
    "unbad_part" -> "set_bad_part_false"
    "bad_part_submitted" -> "bad_part_submitted"
    "view" -> "view"
    else -> action
}

internal fun cncActionForOp(op: String): String = when (op) {
    "set_complete_true" -> "complete"
    "set_complete_false" -> "uncomplete"
    "set_skipped_true" -> "skip"
    "set_skipped_false" -> "unskip"
    "set_bad_part_true" -> "bad_part"
    "set_bad_part_false" -> "unbad_part"
    "bad_part_submitted" -> "bad_part_submitted"
    "view" -> "view"
    else -> op
}

internal fun cncTrackerActionToEvent(action: TrackerAction): TrackerEvent {
    val payload = JsonObject()
    payload.addProperty("file", action.file)
    payload.addProperty("page", action.page)
    action.part?.let { payload.addProperty("part", it) }
    action.fileFingerprint?.let { payload.addProperty("fileFingerprint", it) }
    payload.addProperty("timestamp", action.timestamp)
    action.reNested?.let { payload.addProperty("reNested", it) }
    return TrackerEvent(
        op = cncOpForAction(action.action),
        payload = payload,
        wallTime = action.timestamp,
        lamport = TrackerLamportClock.next()
    )
}

internal fun decodeCncTrackerEvent(event: JsonObject): TrackerAction? = runCatching {
    val payload = event.getAsJsonObject("payload") ?: return@runCatching null
    val file = payload.get("file")?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotBlank() } ?: return@runCatching null
    val page = payload.get("page")?.takeIf { !it.isJsonNull }?.asInt ?: return@runCatching null
    val opField = event.get("op")?.takeIf { !it.isJsonNull }?.asString ?: return@runCatching null
    val action = cncActionForOp(opField)
    val timestamp = payload.get("timestamp")?.takeIf { !it.isJsonNull }?.asString
        ?: event.get("wallTime")?.takeIf { !it.isJsonNull }?.asString
        ?: ""
    // AUD-08: preserve the Lamport counter and event id so replay uses the same total order
    // as the watcher instead of discarding them and sorting on timestamp alone.
    val lamport = event.get("lamport")?.takeIf { !it.isJsonNull }?.asLong ?: 0L
    val eventId = event.get("eventId")?.takeIf { !it.isJsonNull }?.asString ?: ""
    TrackerAction(
        file = file,
        page = page,
        part = payload.get("part")?.takeIf { !it.isJsonNull }?.asInt,
        action = action,
        timestamp = timestamp,
        fileFingerprint = payload.get("fileFingerprint")?.takeIf { !it.isJsonNull }?.asString,
        reNested = payload.get("reNested")?.takeIf { !it.isJsonNull }?.asBoolean,
        lamport = lamport,
        eventId = eventId
    )
}.getOrNull()

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
    var badPartsHasFingerprint: Boolean = false,
    val renestedByFingerprint: MutableMap<String, Boolean> = mutableMapOf(),
    var renestedLegacy: Boolean = false,
    var renestedHasFingerprint: Boolean = false
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
    private val indexOperationLock = Any()
    private val writeLockByJob = ConcurrentHashMap<String, Any>()
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
        synchronized(indexOperationLock) {
            synchronized(indexLock) {
                jobIndexes.remove(jobFolderName)
            }
        }
        bumpProgressVersion()
    }

    fun invalidateJobIndexes(jobFolderNames: Collection<String>) {
        if (jobFolderNames.isEmpty()) return
        synchronized(indexOperationLock) {
            synchronized(indexLock) {
                jobFolderNames.forEach { jobIndexes.remove(it) }
            }
        }
        bumpProgressVersion()
    }

    fun invalidateAllIndexes() {
        synchronized(indexOperationLock) {
            synchronized(indexLock) {
                jobIndexes.clear()
            }
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

    private fun eventsFile(jobFolderName: String): File {
        return File(trackerDir(jobFolderName), "events/$tabletId.ndjson")
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
            val parsed = gson.fromJson(file.readText(), DraftBadPartState::class.java)
            sanitizeDraftBadPartState(parsed, tabletId)
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

    private fun trackerAction(
        pdfFilename: String,
        page: Int,
        action: String,
        fileFingerprint: String,
        part: Int? = null
    ) = TrackerAction(
        file = pdfFilename,
        page = page,
        part = part,
        action = action,
        timestamp = Instant.now().toString(),
        fileFingerprint = fileFingerprint
    )

    private fun appendAction(
        jobFolderName: String,
        pdfFilename: String,
        page: Int,
        action: String,
        fileFingerprint: String,
        part: Int? = null
    ) {
        if (readOnly) return
        appendActions(jobFolderName, listOf(trackerAction(pdfFilename, page, action, fileFingerprint, part)))
    }

    /**
     * Appends multiple actions as individual ndjson lines to this tablet's own event stream
     * (never rewriting prior lines -- see METADATA_AUDIT.md R-01), instead of the old
     * load-whole/rewrite-whole-file pattern. Callers that emit several actions at once
     * (markSheetComplete, resolveBadPartsOnSheet, resolveSpecificBadParts) still get one
     * coalesced index update, so the change monitor sees one version bump rather than many.
     *
     * Intentional tradeoff: append-only per-line durability replaces the old whole-file atomic
     * rewrite. That rewrite pattern (load-all → mutate → rewrite-all) is exactly what caused the
     * bugs R-01 fixes (lost concurrent peer writes, torn whole-file snapshots). With per-line
     * appends, a partial batch (e.g. entry 3 of 5 throws) leaves the successfully-appended events
     * durably recorded — which is correct for an event log — and we still bump the version for
     * those landed entries so listeners see them. The original exception still propagates.
     */
    private fun appendActions(jobFolderName: String, entries: List<TrackerAction>) {
        if (readOnly || entries.isEmpty()) return
        val writeLock = writeLockByJob.getOrPut(jobFolderName) { Any() }
        var appendedAny = false
        try {
            synchronized(indexOperationLock) {
                synchronized(writeLock) {
                    val file = eventsFile(jobFolderName)
                    entries.forEach { entry ->
                        appendTrackerEvent(file, cncTrackerActionToEvent(entry))
                        applyActionToIndex(jobFolderName, entry)
                        appendedAny = true
                    }
                }
            }
        } finally {
            if (appendedAny) bumpProgressVersion()
        }
    }

    fun loadAllProgress(jobFolderName: String): List<TabletProgress> {
        val dir = trackerDir(jobFolderName)
        if (!dir.exists()) return emptyList()

        val legacyProgress = dir.listFiles()
            ?.filter { it.isFile && it.extension.equals("json", ignoreCase = true) && !it.name.startsWith(".") && !it.name.contains(".sync-conflict-") }
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

        val eventsDir = File(dir, "events")
        val ndjsonProgress = eventsDir.listFiles()
            ?.filter { it.isFile && it.extension.equals("ndjson", ignoreCase = true) && !it.name.startsWith(".") && !it.name.contains(".sync-conflict-") }
            ?.map { file ->
                TabletProgress(
                    tabletId = file.nameWithoutExtension,
                    actions = readTrackerEvents(file).mapNotNull { decodeCncTrackerEvent(it) }
                )
            }
            ?: emptyList()

        val merged = linkedMapOf<String, MutableList<TrackerAction>>()
        (legacyProgress + ndjsonProgress).forEach { progress ->
            merged.getOrPut(progress.tabletId) { mutableListOf() }.addAll(progress.actions)
        }
        return merged.map { (id, actions) -> TabletProgress(tabletId = id, actions = actions) }
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
            fileFingerprint = action.fileFingerprint?.trim(),
            reNested = action.reNested,
            lamport = action.lamport,
            // Pre-AUD-08 legacy JSON (consolidated.json, older per-tablet snapshots) has no
            // eventId key at all; Gson leaves this non-null field as a raw null for such files
            // instead of applying the "" default, so it must be defensively cast like the other
            // fields above rather than passed straight into TrackerAction's non-null parameter.
            eventId = (action.eventId as String?).orEmpty()
        )
    }

    // Cache freshness relies on explicit invalidation (TrackerChangeMonitor's FileObserver/poll
    // for other tablets' writes, applyActionToIndex for this tablet's own writes) rather than
    // re-validating against disk on every read — re-listing the tracker dir on every page lookup
    // was the dominant cost in dashboard/job-browser derivation on networked job boards.
    private fun ensureJobIndex(jobFolderName: String): JobProgressIndex {
        synchronized(indexOperationLock) {
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
    }

    private fun buildJobIndex(jobFolderName: String): JobProgressIndex {
        val startedAt = System.currentTimeMillis()
        val allActions = loadAllProgress(jobFolderName)
            .flatMap { it.actions }
            .sortedWith(TRACKER_TOTAL_ORDER)

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
                    if (value) {
                        entry.skippedLegacy = false
                        entry.renestedLegacy = false
                    }
                } else {
                    entry.completeHasFingerprint = true
                    entry.completeByFingerprint[fp] = value
                    if (value) {
                        entry.skippedByFingerprint[fp] = false
                        entry.renestedByFingerprint[fp] = false
                    }
                }
            }
            "skip", "unskip" -> {
                val value = action.action == "skip"
                val renestedValue = value && (action.reNested == true)
                if (fp == null) {
                    entry.skippedLegacy = value
                    entry.renestedLegacy = renestedValue
                } else {
                    entry.skippedHasFingerprint = true
                    entry.skippedByFingerprint[fp] = value
                    entry.renestedHasFingerprint = true
                    entry.renestedByFingerprint[fp] = renestedValue
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

    private fun resolveRenested(entry: SheetIndexEntry?, fileFingerprint: String): Boolean {
        if (entry == null) return false
        return if (entry.renestedHasFingerprint) {
            entry.renestedByFingerprint[fileFingerprint] ?: false
        } else {
            entry.renestedLegacy
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
        val actions = mutableListOf<TrackerAction>()
        if (isSheetSkipped(jobFolderName, pdfFilename, page, fileFingerprint)) {
            actions += trackerAction(pdfFilename, page, "unskip", fileFingerprint)
        }
        actions += trackerAction(pdfFilename, page, "complete", fileFingerprint)
        val draftParts = getDraftBadParts(jobFolderName, pdfFilename, page, fileFingerprint)
        draftParts.forEach { part ->
            actions += trackerAction(pdfFilename, page, "bad_part", fileFingerprint, part = part)
        }
        appendActions(jobFolderName, actions)
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

    fun markSheetRenested(jobFolderName: String, pdfFilename: String, page: Int, fileFingerprint: String) {
        if (readOnly) return
        appendActions(
            jobFolderName,
            listOf(
                TrackerAction(
                    file = pdfFilename,
                    page = page,
                    action = "skip",
                    timestamp = Instant.now().toString(),
                    fileFingerprint = fileFingerprint,
                    reNested = true
                )
            )
        )
    }

    fun unmarkSheetRenested(jobFolderName: String, pdfFilename: String, page: Int, fileFingerprint: String) {
        if (readOnly) return
        appendActions(
            jobFolderName,
            listOf(
                TrackerAction(
                    file = pdfFilename,
                    page = page,
                    action = "unskip",
                    timestamp = Instant.now().toString(),
                    fileFingerprint = fileFingerprint,
                    reNested = false
                )
            )
        )
    }

    fun isSheetRenested(jobFolderName: String, pdfFilename: String, page: Int, fileFingerprint: String): Boolean {
        val entry = resolveSheetEntry(jobFolderName, pdfFilename, page)
        return resolveRenested(entry, fileFingerprint)
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
        val index = ensureJobIndex(jobFolderName)
        val allActions = synchronized(indexLock) { index.allActions.toList() }
            .filter { it.file == pdfFilename && (it.fileFingerprint ?: "") == fileFingerprint }
            .sortedWith(TRACKER_TOTAL_ORDER)
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
        val index = ensureJobIndex(jobFolderName)
        val allActions = synchronized(indexLock) { index.allActions.toList() }
            .filter { it.file == pdfFilename && (it.fileFingerprint ?: "") == fileFingerprint }
            .sortedWith(TRACKER_TOTAL_ORDER)
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
        appendActions(
            jobFolderName,
            committed.map { partNumber ->
                trackerAction(pdfFilename, page, "unbad_part", fileFingerprint, part = partNumber)
            }
        )
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
        appendActions(
            jobFolderName,
            targets.map { partNumber ->
                trackerAction(pdfFilename, page, "unbad_part", fileFingerprint, part = partNumber)
            }
        )
        return targets.size
    }

    fun getSheetStatus(jobFolderName: String, pdfFilename: String, page: Int, fileFingerprint: String): SheetStatus {
        val entry = resolveSheetEntry(jobFolderName, pdfFilename, page)
        val isComplete = resolveComplete(entry, fileFingerprint)
        val isSkipped = resolveSkipped(entry, fileFingerprint)
        val isRenested = resolveRenested(entry, fileFingerprint)
        val hasCommittedBadParts = isComplete && resolveCommittedBadParts(entry, fileFingerprint).isNotEmpty()

        return when {
            hasCommittedBadParts -> SheetStatus.HAS_BAD_PARTS
            isComplete -> SheetStatus.COMPLETE
            isRenested -> SheetStatus.RE_NESTED
            isSkipped -> SheetStatus.SKIPPED
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
        var reNested = 0

        val index = ensureJobIndex(jobFolderName)
        val visiblePages = getMaterialTrackablePages(material)
        for (page in visiblePages) {
            val entry = index.sheets[SheetKey(material.pdfFilename, page)]
            val isComplete = resolveComplete(entry, material.fileFingerprint)
            val isSkipped = resolveSkipped(entry, material.fileFingerprint)
            val isRenested = resolveRenested(entry, material.fileFingerprint)
            val hasBad = isComplete && resolveCommittedBadParts(entry, material.fileFingerprint).isNotEmpty()

            when {
                hasBad -> {
                    complete++
                    bad++
                }
                isComplete -> complete++
                isRenested -> reNested++
                isSkipped -> skipped++
                else -> notStarted++
            }
        }

        return StatusCounts(
            total = visiblePages.size,
            complete = complete,
            bad = bad,
            skipped = skipped,
            notStarted = notStarted,
            reNested = reNested
        )
    }

    fun getJobStatusCounts(jobFolderName: String, materials: List<Material>): StatusCounts {
        var total = 0
        var complete = 0
        var bad = 0
        var skipped = 0
        var notStarted = 0

        var reNested = 0
        val index = ensureJobIndex(jobFolderName)
        materials.forEach { material ->
            for (page in getMaterialTrackablePages(material)) {
                val entry = index.sheets[SheetKey(material.pdfFilename, page)]
                val isComplete = resolveComplete(entry, material.fileFingerprint)
                val isSkipped = resolveSkipped(entry, material.fileFingerprint)
                val isRenested = resolveRenested(entry, material.fileFingerprint)
                val hasBad = isComplete && resolveCommittedBadParts(entry, material.fileFingerprint).isNotEmpty()

                if (isRenested) {
                    reNested++
                    continue
                }

                total++
                when {
                    hasBad -> {
                        complete++
                        bad++
                    }
                    isComplete -> complete++
                    isSkipped -> skipped++
                    else -> notStarted++
                }
            }
        }

        return StatusCounts(total, complete, bad, skipped, notStarted, reNested)
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
        val ownActions = readTrackerEvents(eventsFile(jobFolderName)).mapNotNull { decodeCncTrackerEvent(it) }
        val touches = mutableMapOf<String, MaterialTouchEntry>()
        ownActions.sortedWith(TRACKER_TOTAL_ORDER).forEach { action ->
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
            val raw = gson.fromJson(file.readText(), OcrPageCache::class.java).let { sanitizeOcrPageCache(it) }
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

    private fun <T> gsonNullable(value: T): T? = value

    private fun sanitizeDraftBadPartState(state: DraftBadPartState?, defaultTabletId: String): DraftBadPartState {
        if (state == null) return DraftBadPartState(defaultTabletId)
        return DraftBadPartState(
            tabletId = gsonNullable(state.tabletId) ?: defaultTabletId,
            entries = (gsonNullable(state.entries) ?: emptyList()).map { entry ->
                DraftBadPartEntry(
                    file = gsonNullable(entry.file) ?: "",
                    page = entry.page,
                    fileFingerprint = gsonNullable(entry.fileFingerprint) ?: "",
                    parts = gsonNullable(entry.parts) ?: emptyList()
                )
            }
        )
    }

    private fun sanitizeOcrPageCache(cache: OcrPageCache?): OcrPageCache {
        if (cache == null) return OcrPageCache()
        return OcrPageCache(
            boxes = (gsonNullable(cache.boxes) ?: emptyMap()).mapValues { (_, list) ->
                (gsonNullable(list) ?: emptyList()).mapNotNull { box ->
                    gsonNullable(box)?.let { OcrBox(it.left, it.top, it.right, it.bottom) }
                }
            },
            savedAt = gsonNullable(cache.savedAt) ?: ""
        )
    }
}
