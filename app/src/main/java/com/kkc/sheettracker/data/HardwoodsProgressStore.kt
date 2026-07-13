package com.kkc.sheettracker.data

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.kkc.sheettracker.BuildConfig
import com.kkc.sheettracker.data.models.HardwoodCutlistIndex
import com.kkc.sheettracker.data.models.HardwoodDocSummary
import com.kkc.sheettracker.data.models.HardwoodDocumentIndex
import com.kkc.sheettracker.data.models.HardwoodJob
import com.kkc.sheettracker.data.models.HardwoodDocType
import com.kkc.sheettracker.data.models.HardwoodJobSummary
import com.kkc.sheettracker.data.models.HardwoodRowProgress
import com.kkc.sheettracker.data.models.HardwoodStatusCounts
import com.kkc.sheettracker.data.models.HardwoodTabletProgress
import com.kkc.sheettracker.data.models.HardwoodTotalsTallyKey
import com.kkc.sheettracker.data.models.HardwoodTrackerActions
import com.kkc.sheettracker.data.models.HardwoodTrackerAction
import com.kkc.sheettracker.data.models.HardwoodInkStroke
import com.kkc.sheettracker.data.models.HardwoodTabletMarkup
import com.kkc.sheettracker.data.unified.UnifiedMetadataEngine
import com.kkc.sheettracker.data.unified.UnifiedMetadataEngineRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.math.BigDecimal
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.Locale

internal fun hardwoodsTrackerActionToEvent(action: HardwoodTrackerAction): TrackerEvent {
    val payload = com.google.gson.JsonObject()
    payload.addProperty("docType", action.docType)
    payload.addProperty("rowId", action.rowId)
    action.totalsKey?.let { payload.addProperty("totalsKey", it) }
    action.value?.let { payload.addProperty("value", it) }
    payload.addProperty("timestamp", action.timestamp)
    return if (action.eventId.isNotBlank()) {
        TrackerEvent(
            op = action.action,
            payload = payload,
            wallTime = action.timestamp,
            lamport = action.lamport,
            eventId = action.eventId
        )
    } else {
        TrackerEvent(
            op = action.action,
            payload = payload,
            wallTime = action.timestamp,
            lamport = action.lamport
        )
    }
}

internal fun decodeHardwoodsTrackerEvent(event: com.google.gson.JsonObject): HardwoodTrackerAction? {
    return runCatching {
        val payload = event.getAsJsonObject("payload") ?: return@runCatching null
        val docType = payload.get("docType")?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotBlank() } ?: return@runCatching null
        val rowId = payload.get("rowId")?.takeIf { !it.isJsonNull }?.asString ?: return@runCatching null
        val action = event.get("op")?.takeIf { !it.isJsonNull }?.asString ?: return@runCatching null
        val timestamp = payload.get("timestamp")?.takeIf { !it.isJsonNull }?.asString
            ?: event.get("wallTime")?.takeIf { !it.isJsonNull }?.asString
            ?: ""
        HardwoodTrackerAction(
            docType = docType,
            rowId = rowId,
            totalsKey = payload.get("totalsKey")?.takeIf { !it.isJsonNull }?.asString,
            action = action,
            value = payload.get("value")?.takeIf { !it.isJsonNull }?.asInt,
            timestamp = timestamp,
            lamport = event.get("lamport")?.takeIf { !it.isJsonNull }?.asLong ?: 0L,
            // AUD-08: preserve event id for the shared total order.
            eventId = event.get("eventId")?.takeIf { !it.isJsonNull }?.asString ?: ""
        )
    }.getOrNull()
}

private data class HardwoodRowKey(
    val docType: String,
    val rowId: String
)

private data class CutlistLookup(
    val rows: Set<HardwoodRowKey>,
    val cabinetsByRow: Map<HardwoodRowKey, Set<String>>
)

class HardwoodsProgressStore(
    private val baseDir: File,
    private val tabletId: String,
    private val readOnly: Boolean = false,
    private var unifiedEngine: UnifiedMetadataEngine? = null
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
    private val cacheOperationLock = Any()
    private val cacheByJob = ConcurrentHashMap<String, JobCache>()
    private val pendingLocalActionsByJob = ConcurrentHashMap<String, List<HardwoodTrackerAction>>()

    private fun engine(): UnifiedMetadataEngine {
        val existing = unifiedEngine
        if (existing != null) return existing
        return UnifiedMetadataEngineRegistry.getOrCreate(
            baseDir = baseDir,
            isDebugBuild = BuildConfig.DEBUG
        ).also { unifiedEngine = it }
    }

    private fun loadCutlistIndexFromSnapshot(jobFolderName: String): HardwoodCutlistIndex? {
        engine().getHardwoodsSnapshot(jobFolderName)?.job?.index?.let { return it }
        val indexFile = File(baseDir, "$jobFolderName/.metadata/hardwoods/cutlist_index.json")
        if (!indexFile.exists()) return null
        return runCatching {
            gson.fromJson(indexFile.readText(), HardwoodCutlistIndex::class.java)
        }.getOrNull()
    }

    private fun bumpProgressVersion() {
        _progressVersion.value = _progressVersion.value + 1L
    }

    fun invalidateJobCache(jobFolderName: String) {
        synchronized(cacheOperationLockFor(jobFolderName)) {
            cacheByJob.remove(jobFolderName)
        }
        bumpProgressVersion()
    }

    fun invalidateJobCaches(jobFolderNames: Collection<String>) {
        if (jobFolderNames.isEmpty()) return
        jobFolderNames.forEach { jobFolderName ->
            synchronized(cacheOperationLockFor(jobFolderName)) {
                cacheByJob.remove(jobFolderName)
            }
        }
        bumpProgressVersion()
    }

    fun invalidateAllCaches() {
        synchronized(cacheOperationLock) {
            cacheByJob.clear()
        }
        bumpProgressVersion()
    }

    fun invalidateFromTrackerFile(trackerFile: File): Boolean {
        val trackerDir = trackerFile.parentFile ?: return false
        if (!trackerDir.name.equals(".tracker", ignoreCase = true)) return false
        val hardwoodsDir = trackerDir.parentFile ?: return false
        if (!hardwoodsDir.name.equals("hardwoods", ignoreCase = true)) return false
        val metadataDir = hardwoodsDir.parentFile ?: return false
        if (!metadataDir.name.equals(".metadata", ignoreCase = true)) return false
        val jobDir = metadataDir.parentFile ?: return false
        if (jobDir.name.isBlank()) return false
        invalidateJobCache(jobDir.name)
        return true
    }

    private fun trackerDir(jobFolderName: String): File = File(baseDir, "$jobFolderName/.metadata/hardwoods/.tracker")
    private fun tabletEventsFile(jobFolderName: String): File = File(trackerDir(jobFolderName), "events/$tabletId.ndjson")
    private fun boardStockMigrationMarkerFile(jobFolderName: String): File =
        File(trackerDir(jobFolderName), ".board_stock_migration_${tabletId}.json")
    private fun boardStockCanonicalMigrationMarkerFile(jobFolderName: String): File =
        File(trackerDir(jobFolderName), ".board_stock_canonical_migration_${tabletId}.json")

    private fun legacyTabletBlobFile(jobFolderName: String): File = File(trackerDir(jobFolderName), "$tabletId.json")

    /** One-time upgrade read: this tablet's own progress from BOTH the legacy <tabletId>.json blob
     * and the ndjson stream, merged. Used only by the migration routines so they still see
     * pre-upgrade history that predates the ndjson switch. */
    private fun loadOwnProgressIncludingLegacy(jobFolderName: String): HardwoodTabletProgress {
        val actions = mutableListOf<HardwoodTrackerAction>()
        val legacy = legacyTabletBlobFile(jobFolderName)
        if (legacy.exists()) {
            runCatching { gson.fromJson(legacy.readText(), HardwoodTabletProgress::class.java) }
                .getOrNull()
                ?.let { sanitizeProgress(it, fallbackTabletId = tabletId) }
                ?.let { actions.addAll(it.actions) }
        }
        actions.addAll(loadTabletProgress(jobFolderName).actions)  // ndjson
        return HardwoodTabletProgress(tabletId = tabletId, actions = actions)
    }

    private fun loadTabletProgress(jobFolderName: String): HardwoodTabletProgress {
        val file = tabletEventsFile(jobFolderName)
        if (!file.exists()) return HardwoodTabletProgress(tabletId = tabletId)
        val actions = readTrackerEvents(file).mapNotNull { decodeHardwoodsTrackerEvent(it) }
        return sanitizeProgress(HardwoodTabletProgress(tabletId = tabletId, actions = actions), fallbackTabletId = tabletId)
            ?: HardwoodTabletProgress(tabletId = tabletId)
    }

    // CROSS-PROGRAM: this file (`events/<tabletId>.ndjson` under `.metadata/hardwoods/.tracker/`)
    // is Syncthing-replicated and read by peer tablets (readProgressFromDir/loadAllProgress above)
    // and by Ready Jobs Watcher's tracker_action_stream.py union reader. Written atomically (temp
    // file + Files.move ATOMIC_MOVE via atomicWriteFile) so a concurrent reader never observes a
    // torn write. Unlike ProgressStore.kt's true per-line append, this rewrites the whole stream
    // from the in-memory action list on every persist -- correct here because this tablet is still
    // the sole writer of its own file (no cross-process race), and it preserves compatibility with
    // the existing migration routines that rewrite historical actions in place. See
    // METADATA_AUDIT.md R-01.
    private fun saveTabletProgress(jobFolderName: String, progress: HardwoodTabletProgress) {
        val dir = trackerDir(jobFolderName)
        dir.mkdirs()
        val body = progress.actions.joinToString("") { action ->
            encodeTrackerEventLine(hardwoodsTrackerActionToEvent(action)) + "\n"
        }
        atomicWriteFile(tabletEventsFile(jobFolderName), body)
    }

    private fun appendAction(
        jobFolderName: String,
        docType: String,
        rowId: String,
        action: String,
        value: Int? = null,
        totalsKey: String? = null
    ) {
        if (readOnly) return
        val next = HardwoodTrackerAction(
            docType = docType,
            rowId = rowId,
            totalsKey = totalsKey,
            action = action,
            value = value,
            timestamp = Instant.now().toString(),
            lamport = TrackerLamportClock.next()
        )
        val snapshot = runCatching {
            synchronized(cacheOperationLockFor(jobFolderName)) {
                val cache = ensureJobCache(jobFolderName)
                // Guard the cache's mutable maps with the JobCache instance monitor so this write
                // cannot interleave with a concurrent reader snapshot (getRowProgressMap etc.).
                synchronized(cache) {
                    cache.localActions.add(next)
                    applyActionToCache(cache, next)
                    cache.localActions.toList()
                }.also { pendingLocalActionsByJob[jobFolderName] = it }
            }
        }.getOrElse {
            val merged = synchronized(cacheOperationLockFor(jobFolderName)) {
                val localActions = pendingLocalActionsByJob[jobFolderName] ?: loadTabletProgress(jobFolderName).actions
                (localActions + next).also { pendingLocalActionsByJob[jobFolderName] = it }
            }
            saveLocalActionsSync(jobFolderName, merged)
            bumpProgressVersion()
            return
        }
        persistLocalActionsAsync(jobFolderName, snapshot, publishPending = false)
        bumpProgressVersion()
    }

    private fun loadAllProgress(jobFolderName: String): List<HardwoodTabletProgress> {
        return readProgressFromDir(trackerDir(jobFolderName))
    }

    // Merges two on-disk representations of per-tablet progress under trackerDir:
    //   1. Legacy single-blob `<tabletId>.json` files -- older tablets/peers not yet migrated.
    //   2. `events/<tabletId>.ndjson` -- the current format written by saveTabletProgress().
    // Mirrors ProgressStore.kt's loadAllProgress() merge pattern (see METADATA_AUDIT.md R-01).
    private fun readProgressFromDir(dir: File): List<HardwoodTabletProgress> {
        if (!dir.exists()) return emptyList()
        val legacyProgress = dir.listFiles()
            ?.filter {
                it.isFile &&
                    it.extension.equals("json", ignoreCase = true) &&
                    !it.name.startsWith(".") &&
                    !it.name.contains(".sync-conflict-") &&
                    !it.name.endsWith(".markup.json", ignoreCase = true)
            }
            ?.mapNotNull { file ->
                runCatching { gson.fromJson(file.readText(), HardwoodTabletProgress::class.java) }
                    .getOrNull()
                    ?.let { sanitizeProgress(it, fallbackTabletId = file.nameWithoutExtension) }
            }
            ?: emptyList()

        val eventsDir = File(dir, "events")
        val ndjsonProgress = eventsDir.listFiles()
            ?.filter {
                it.isFile &&
                    it.extension.equals("ndjson", ignoreCase = true) &&
                    !it.name.startsWith(".") &&
                    !it.name.contains(".sync-conflict-")
            }
            ?.mapNotNull { file ->
                sanitizeProgress(
                    HardwoodTabletProgress(
                        tabletId = file.nameWithoutExtension,
                        actions = readTrackerEvents(file).mapNotNull { decodeHardwoodsTrackerEvent(it) }
                    ),
                    fallbackTabletId = file.nameWithoutExtension
                )
            }
            ?: emptyList()

        val merged = linkedMapOf<String, MutableList<HardwoodTrackerAction>>()
        (legacyProgress + ndjsonProgress).forEach { progress ->
            merged.getOrPut(progress.tabletId) { mutableListOf() }.addAll(progress.actions)
        }
        return merged.map { (id, actions) -> HardwoodTabletProgress(tabletId = id, actions = actions) }
    }

    private fun tabletMarkupFile(jobFolderName: String): File = File(trackerDir(jobFolderName), "$tabletId.markup.json")

    fun loadTabletMarkup(jobFolderName: String): HardwoodTabletMarkup {
        val file = tabletMarkupFile(jobFolderName)
        if (!file.exists()) return HardwoodTabletMarkup(tabletId = tabletId)
        return try {
            gson.fromJson(file.readText(), HardwoodTabletMarkup::class.java) ?: HardwoodTabletMarkup(tabletId = tabletId)
        } catch (_: Exception) {
            HardwoodTabletMarkup(tabletId = tabletId)
        }
    }

    fun saveTabletMarkup(
        jobFolderName: String,
        strokes: List<HardwoodInkStroke>,
        deletedStrokeIds: List<String>
    ) {
        if (readOnly) return
        val writeMutex = writeMutexByJob.getOrPut(jobFolderName) { Mutex() }
        ioScope.launch {
            writeMutex.withLock {
                val dir = trackerDir(jobFolderName)
                dir.mkdirs()
                val markup = HardwoodTabletMarkup(
                    tabletId = tabletId,
                    strokes = strokes,
                    deletedStrokeIds = deletedStrokeIds
                )
                val destFile = tabletMarkupFile(jobFolderName)
                try {
                    atomicWriteFile(destFile, gson.toJson(markup))
                    _progressVersion.value++
                } catch (e: Exception) {
                    android.util.Log.e("HardwoodsProgressStore", "Error saving markup: ${e.localizedMessage}")
                }
            }
        }
    }

    fun getActiveStrokes(jobFolderName: String): List<HardwoodInkStroke> {
        val dir = trackerDir(jobFolderName)
        if (!dir.exists()) return emptyList()
        val allMarkup = dir.listFiles()
            ?.filter {
                it.isFile &&
                    it.name.endsWith(".markup.json", ignoreCase = true) &&
                    !it.name.startsWith(".") &&
                    !it.name.contains(".sync-conflict-")
            }
            ?.mapNotNull { file ->
                runCatching { gson.fromJson(file.readText(), HardwoodTabletMarkup::class.java) }
                    .getOrNull()
            }
            .orEmpty()

        val allDeletedIds = allMarkup.flatMap { it.deletedStrokeIds.orEmpty() }.toSet()
        return allMarkup.flatMap { it.strokes.orEmpty() }
            .filter { it.id !in allDeletedIds }
    }

    private fun loadAllActions(jobFolderName: String): List<HardwoodTrackerAction> {
        return loadAllProgress(jobFolderName)
            .flatMap { it.actions.orEmpty() }
            .sortedWith(HARDWOOD_TRACKER_TOTAL_ORDER)
    }

    private fun sanitizeProgress(
        progress: HardwoodTabletProgress?,
        fallbackTabletId: String
    ): HardwoodTabletProgress? {
        progress ?: return null
        val safeTabletId = (progress.tabletId as String?).orEmpty().ifBlank { fallbackTabletId }
        val safeActions = (progress.actions as? List<*>).orEmpty()
            .mapNotNull { it as? HardwoodTrackerAction }
            .mapNotNull { sanitizeAction(it) }
        return HardwoodTabletProgress(
            tabletId = safeTabletId,
            actions = safeActions
        )
    }

    private fun sanitizeAction(action: HardwoodTrackerAction): HardwoodTrackerAction? {
        val safeAction = (action.action as String?).orEmpty().trim()
        if (safeAction.isBlank()) return null
        return HardwoodTrackerAction(
            docType = (action.docType as String?).orEmpty(),
            rowId = (action.rowId as String?).orEmpty(),
            totalsKey = action.totalsKey?.trim()?.takeIf { it.isNotBlank() },
            action = safeAction,
            value = action.value,
            timestamp = (action.timestamp as String?).orEmpty(),
            lamport = action.lamport,
            eventId = action.eventId
        )
    }

    fun getRowProgressMap(jobFolderName: String): Map<Pair<String, String>, HardwoodRowProgress> {
        val cache = ensureJobCache(jobFolderName)
        return synchronized(cache) { cache.rowProgressMap.toMap() }
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

    fun incrementDoneCount(jobFolderName: String, docType: String, rowId: String, qty: Int) {
        changeDoneCount(jobFolderName, docType, rowId, qty, delta = 1)
    }

    fun decrementDoneCount(jobFolderName: String, docType: String, rowId: String, qty: Int) {
        changeDoneCount(jobFolderName, docType, rowId, qty, delta = -1)
    }

    private fun changeDoneCount(jobFolderName: String, docType: String, rowId: String, qty: Int, delta: Int) {
        appendComputedAction(
            jobFolderName = jobFolderName,
            docType = docType,
            rowId = rowId,
            action = HardwoodTrackerActions.SET_DONE_COUNT
        ) { cache ->
            val key = docType to rowId
            val current = cache.rowProgressMap[key] ?: HardwoodRowProgress()
            val next = normalized(qty, current, proposedDone = current.doneCount + delta)
            next.doneCount.takeIf { it != current.doneCount }
        }
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
        val cache = ensureJobCache(jobFolderName)
        return synchronized(cache) { cache.skippedCabinetMap.mapValues { it.value.toSet() } }
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
        val cache = ensureJobCache(jobFolderName)
        return synchronized(cache) { cache.totalsRip10Map.toMap() }
    }

    fun getBoardStockRipDone(
        jobFolderName: String,
        material: String,
        normalizedWidth: Double,
        source: String
    ): Int {
        val key = makeBoardStockTallyKey(material, normalizedWidth, source)
        return getTotalsRip10DoneMap(jobFolderName)[key] ?: 0
    }

    fun makeBoardStockRipSkipKey(material: String, normalizedWidth: Double, source: String): String {
        val widthPart = stableBoardStockWidthString(normalizedWidth)
        return "board_stock_skip|${normalizeBoardStockMaterial(material)}|$widthPart|${normalizeBoardStockSource(source)}"
    }

    fun makeBoardStockMaterialSkipKey(material: String): String {
        return "board_stock_material_skip|${normalizeBoardStockMaterial(material)}"
    }

    fun makeBoardStockMaterialSkipKey(material: String, source: String): String {
        return "board_stock_material_skip|${normalizeBoardStockMaterial(material)}|${normalizeBoardStockSource(source)}"
    }

    fun isBoardStockRipSkipped(
        jobFolderName: String,
        material: String,
        normalizedWidth: Double,
        source: String
    ): Boolean {
        val key = makeBoardStockRipSkipKey(material, normalizedWidth, source)
        return (getTotalsRip10DoneMap(jobFolderName)[key] ?: 0) > 0
    }

    fun setBoardStockRipSkipped(
        jobFolderName: String,
        material: String,
        normalizedWidth: Double,
        source: String,
        skipped: Boolean
    ) {
        val key = makeBoardStockRipSkipKey(material, normalizedWidth, source)
        appendAction(
            jobFolderName = jobFolderName,
            docType = "BOARD_STOCK_SKIP",
            rowId = "",
            totalsKey = key,
            action = HardwoodTrackerActions.SET_TOTALS_RIP10_DONE_COUNT,
            value = if (skipped) 1 else 0
        )
    }

    fun isBoardStockMaterialSkipped(jobFolderName: String, material: String): Boolean {
        val key = makeBoardStockMaterialSkipKey(material)
        return (getTotalsRip10DoneMap(jobFolderName)[key] ?: 0) > 0
    }

    fun isBoardStockMaterialSkipped(jobFolderName: String, material: String, source: String): Boolean {
        val totals = getTotalsRip10DoneMap(jobFolderName)
        val sourceKey = makeBoardStockMaterialSkipKey(material, source)
        return if (totals.containsKey(sourceKey)) {
            (totals[sourceKey] ?: 0) > 0
        } else {
            val legacyKey = makeBoardStockMaterialSkipKey(material)
            (totals[legacyKey] ?: 0) > 0
        }
    }

    fun setBoardStockMaterialSkipped(jobFolderName: String, material: String, skipped: Boolean) {
        val key = makeBoardStockMaterialSkipKey(material)
        appendAction(
            jobFolderName = jobFolderName,
            docType = "BOARD_STOCK_SKIP",
            rowId = "",
            totalsKey = key,
            action = HardwoodTrackerActions.SET_TOTALS_RIP10_DONE_COUNT,
            value = if (skipped) 1 else 0
        )
    }

    fun setBoardStockMaterialSkipped(jobFolderName: String, material: String, source: String, skipped: Boolean) {
        val key = makeBoardStockMaterialSkipKey(material, source)
        appendAction(
            jobFolderName = jobFolderName,
            docType = "BOARD_STOCK_SKIP",
            rowId = "",
            totalsKey = key,
            action = HardwoodTrackerActions.SET_TOTALS_RIP10_DONE_COUNT,
            value = if (skipped) 1 else 0
        )
    }

    fun setBoardStockRipDone(
        jobFolderName: String,
        material: String,
        normalizedWidth: Double,
        source: String,
        doneCount: Int
    ) {
        val key = makeBoardStockTallyKey(material, normalizedWidth, source)
        val normalizedTarget = doneCount.coerceAtLeast(0)
        val current = getTotalsRip10DoneMap(jobFolderName)[key] ?: 0
        if (current == normalizedTarget) return
        appendAction(
            jobFolderName = jobFolderName,
            docType = "BOARD_STOCK",
            rowId = "",
            totalsKey = key,
            action = HardwoodTrackerActions.SET_TOTALS_RIP10_DONE_COUNT,
            value = normalizedTarget
        )
    }

    fun incrementBoardStockRipDone(
        jobFolderName: String,
        material: String,
        normalizedWidth: Double,
        source: String,
        maxCount: Int
    ) {
        changeBoardStockRipDone(jobFolderName, material, normalizedWidth, source, maxCount, delta = 1)
    }

    fun decrementBoardStockRipDone(
        jobFolderName: String,
        material: String,
        normalizedWidth: Double,
        source: String,
        maxCount: Int
    ) {
        changeBoardStockRipDone(jobFolderName, material, normalizedWidth, source, maxCount, delta = -1)
    }

    private fun changeBoardStockRipDone(
        jobFolderName: String,
        material: String,
        normalizedWidth: Double,
        source: String,
        maxCount: Int,
        delta: Int
    ) {
        val key = makeBoardStockTallyKey(material, normalizedWidth, source)
        changeTotalsDone(
            jobFolderName = jobFolderName,
            docType = "BOARD_STOCK",
            totalsKey = key,
            maxCount = maxCount,
            delta = delta
        )
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
        if (current == normalizedTarget) return
        appendAction(
            jobFolderName = jobFolderName,
            docType = docType,
            rowId = "",
            totalsKey = key,
            action = HardwoodTrackerActions.SET_TOTALS_RIP10_DONE_COUNT,
            value = normalizedTarget
        )
    }

    fun summarizeDocument(jobFolderName: String, document: HardwoodDocumentIndex): HardwoodDocSummary {
        return summarizeDocument(getRowProgressMap(jobFolderName), document)
    }

    private fun summarizeDocument(
        states: Map<Pair<String, String>, HardwoodRowProgress>,
        document: HardwoodDocumentIndex
    ): HardwoodDocSummary {
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
        // Fetch the row-progress map once per job; summarizeDocument was previously
        // re-snapshotting it for every document (O(docs) full map copies per summary).
        val states = getRowProgressMap(job.folderName)
        val docs = index.documents.map { summarizeDocument(states, it) }
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

    fun getLocalLastTouchedAtMs(jobFolderName: String): Long {
        val progress = loadTabletProgress(jobFolderName)
        return progress.actions
            .mapNotNull { action ->
                runCatching { java.time.Instant.parse(action.timestamp).toEpochMilli() }.getOrNull()
            }
            .maxOrNull() ?: 0L
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
        return synchronized(cacheOperationLockFor(jobFolderName)) {
            cacheByJob[jobFolderName] ?: buildJobCache(jobFolderName).also { cacheByJob[jobFolderName] = it }
        }
    }

    private fun cacheOperationLockFor(jobFolderName: String): Any = cacheOperationLock

    private fun buildJobCache(jobFolderName: String): JobCache {
        if (!readOnly) {
            migrateLegacyTotalsKeysIfNeeded(jobFolderName)
            migrateBoardStockKeysToCanonicalIfNeeded(jobFolderName)
        }
        val pendingLocalActions = pendingLocalActionsByJob[jobFolderName]
        val allProgress = mergePendingLocalActions(
            progress = loadAllProgress(jobFolderName),
            pendingLocalActions = pendingLocalActions
        )
        val lookup = loadCutlistLookup(jobFolderName)
        val allActions = allProgress
            .flatMap { it.actions.orEmpty() }
            .mapNotNull { action -> compactAction(action, lookup) }
            .sortedWith(HARDWOOD_TRACKER_TOTAL_ORDER)
        val localProgress = allProgress.firstOrNull { it.tabletId == tabletId }
            ?: HardwoodTabletProgress(tabletId = tabletId)
        val localActions = localProgress.actions
            .mapNotNull { action -> compactAction(action, lookup) }
            .sortedWith(HARDWOOD_TRACKER_TOTAL_ORDER)
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

    private fun mergePendingLocalActions(
        progress: List<HardwoodTabletProgress>,
        pendingLocalActions: List<HardwoodTrackerAction>?
    ): List<HardwoodTabletProgress> {
        if (pendingLocalActions == null) return progress
        val local = HardwoodTabletProgress(tabletId = tabletId, actions = pendingLocalActions)
        var replaced = false
        val merged = progress.map {
            if (it.tabletId == tabletId) {
                replaced = true
                local
            } else {
                it
            }
        }
        return if (replaced) merged else merged + local
    }

    private fun loadCutlistLookup(jobFolderName: String): CutlistLookup? {
        val index = loadCutlistIndexFromSnapshot(jobFolderName) ?: return null
        val rows = mutableSetOf<HardwoodRowKey>()
        val cabinetsByRow = mutableMapOf<HardwoodRowKey, Set<String>>()
        index.documents.forEach { doc ->
            val docKey = doc.docType.name
            doc.rows.forEach { row ->
                val rowKey = HardwoodRowKey(docType = docKey, rowId = row.rowId)
                rows += rowKey
                val cabinets = row.cabinets
                    .mapNotNull { normalizeCabinetToken(it) }
                    .toSet()
                cabinetsByRow[rowKey] = cabinets
            }
        }
        if (rows.isEmpty()) return null

        return CutlistLookup(
            rows = rows,
            cabinetsByRow = cabinetsByRow
        )
    }

    private fun normalizeCabinetToken(value: String): String? {
        val normalized = value.trim().uppercase(Locale.US)
        return normalized.ifBlank { null }
    }

    private fun compactAction(action: HardwoodTrackerAction, lookup: CutlistLookup?): HardwoodTrackerAction? {
        if (lookup == null) return action
        val isRowScoped = action.action == HardwoodTrackerActions.SET_DONE_COUNT ||
            action.action == HardwoodTrackerActions.SET_BAD_COUNT ||
            action.action == HardwoodTrackerActions.SET_SKIPPED ||
            action.action == HardwoodTrackerActions.CLEAR_SKIPPED
        if (!isRowScoped) return action
        decodeCabinetSkipRowId(action.rowId)?.let { decoded ->
            val rowKey = HardwoodRowKey(docType = action.docType, rowId = decoded.first)
            if (rowKey !in lookup.rows) return null
            val cabinet = normalizeCabinetToken(decoded.second) ?: return null
            val validCabinets = lookup.cabinetsByRow[rowKey].orEmpty()
            if (cabinet !in validCabinets) return null
            return action.copy(rowId = encodeCabinetSkipRowId(decoded.first, cabinet))
        }
        if (action.rowId.isNotBlank()) {
            val rowKey = HardwoodRowKey(docType = action.docType, rowId = action.rowId)
            if (rowKey !in lookup.rows) return null
        }
        return action
    }

    private fun migrateLegacyTotalsKeysIfNeeded(jobFolderName: String) {
        val marker = boardStockMigrationMarkerFile(jobFolderName)
        if (marker.exists()) return
        val current = loadOwnProgressIncludingLegacy(jobFolderName)
        if (current.actions.isEmpty()) {
            writeMigrationMarker(jobFolderName, migratedCount = 0)
            return
        }
        val migratedActions = current.actions.map { action ->
            val legacy = action.totalsKey?.takeIf { isLegacyTotalsKey(it) } ?: return@map action
            val boardKey = legacyToBoardStockKey(jobFolderName, legacy) ?: return@map action
            action.copy(totalsKey = boardKey)
        }
        val changed = migratedActions.zip(current.actions).count { (next, prev) -> next.totalsKey != prev.totalsKey }
        // Always fold the (possibly-migrated) full set into ndjson, THEN retire the legacy blob --
        // even when changed == 0 -- so pre-upgrade legacy history is preserved in ndjson before the
        // <tabletId>.json is deleted. Deleting keeps readProgressFromDir's legacy+ndjson union
        // disjoint (no double-count). See METADATA_AUDIT.md R-01 upgrade-path fix.
        saveLocalActionsSync(jobFolderName, migratedActions)
        runCatching { legacyTabletBlobFile(jobFolderName).delete() }
        writeMigrationMarker(jobFolderName, migratedCount = changed)
    }

    private fun migrateBoardStockKeysToCanonicalIfNeeded(jobFolderName: String) {
        val marker = boardStockCanonicalMigrationMarkerFile(jobFolderName)
        if (marker.exists()) return
        val current = loadOwnProgressIncludingLegacy(jobFolderName)
        if (current.actions.isEmpty()) {
            writeCanonicalMigrationMarker(jobFolderName, migratedCount = 0)
            return
        }
        val migratedActions = current.actions.map { action ->
            val key = action.totalsKey ?: return@map action
            val canonical = canonicalizeBoardStockTotalsKey(key) ?: return@map action
            if (canonical == key) action else action.copy(totalsKey = canonical)
        }
        val changed = migratedActions.zip(current.actions).count { (next, prev) -> next.totalsKey != prev.totalsKey }
        // Always fold the (possibly-migrated) full set into ndjson, THEN retire the legacy blob --
        // even when changed == 0 -- so pre-upgrade legacy history is preserved in ndjson before the
        // <tabletId>.json is deleted. Deleting keeps readProgressFromDir's legacy+ndjson union
        // disjoint (no double-count). See METADATA_AUDIT.md R-01 upgrade-path fix.
        saveLocalActionsSync(jobFolderName, migratedActions)
        runCatching { legacyTabletBlobFile(jobFolderName).delete() }
        writeCanonicalMigrationMarker(jobFolderName, migratedCount = changed)
    }

    private fun writeMigrationMarker(jobFolderName: String, migratedCount: Int) {
        val marker = boardStockMigrationMarkerFile(jobFolderName)
        marker.parentFile?.mkdirs()
        val payload = mapOf(
            "tabletId" to tabletId,
            "migratedCount" to migratedCount,
            "migratedAt" to Instant.now().toString()
        )
        runCatching { marker.writeText(gson.toJson(payload)) }
    }

    private fun writeCanonicalMigrationMarker(jobFolderName: String, migratedCount: Int) {
        val marker = boardStockCanonicalMigrationMarkerFile(jobFolderName)
        marker.parentFile?.mkdirs()
        val payload = mapOf(
            "tabletId" to tabletId,
            "migratedCount" to migratedCount,
            "migratedAt" to Instant.now().toString()
        )
        runCatching { marker.writeText(gson.toJson(payload)) }
    }

    private fun isLegacyTotalsKey(key: String): Boolean {
        val parts = key.split("|")
        if (parts.size != 3) return false
        return parts[1].toIntOrNull() != null && parts[2].toIntOrNull() != null
    }

    private fun legacyToBoardStockKey(jobFolderName: String, legacyKey: String): String? {
        val parts = legacyKey.split("|")
        if (parts.size != 3) return null
        val docType = runCatching { HardwoodDocType.valueOf(parts[0]) }.getOrNull() ?: return null
        val blockIndex = parts[1].toIntOrNull() ?: return null
        val lineIndex = parts[2].toIntOrNull() ?: return null
        val index = loadCutlistIndexFromSnapshot(jobFolderName) ?: return null
        val doc = index.documents.firstOrNull { it.docType == docType } ?: return null
        val block = doc.totals.getOrNull(blockIndex) ?: return null
        val material = block.material.orEmpty().trim()
        val widthRaw = block.widthValues.getOrNull(lineIndex).orEmpty()
        val normalizedWidth = widthRaw.trim().toDoubleOrNull() ?: return null
        val source = when (docType) {
            HardwoodDocType.FACE_FRAME_CUT_LIST -> "FRAME"
            HardwoodDocType.NAILER_CUT_LIST -> "NAILER"
            HardwoodDocType.DOOR_CUT_LIST -> "DOOR"
            else -> return null
        }
        return makeBoardStockTallyKey(material, normalizedWidth, source)
    }

    private fun canonicalizeBoardStockTotalsKey(key: String): String? {
        val parts = key.split("|")
        if (parts.isEmpty()) return null
        return when (parts[0]) {
            "board_stock" -> {
                if (parts.size != 4) return null
                val width = parts[2].trim().toDoubleOrNull() ?: return null
                makeBoardStockTallyKey(parts[1], width, parts[3])
            }
            "board_stock_skip" -> {
                if (parts.size != 4) return null
                val width = parts[2].trim().toDoubleOrNull() ?: return null
                makeBoardStockRipSkipKey(parts[1], width, parts[3])
            }
            "board_stock_material_skip" -> {
                when (parts.size) {
                    2 -> makeBoardStockMaterialSkipKey(parts[1])
                    3 -> makeBoardStockMaterialSkipKey(parts[1], parts[2])
                    else -> return null
                }
            }
            else -> null
        }
    }

    private fun normalizeBoardStockMaterial(value: String): String {
        return value.trim().replace(Regex("""\s+"""), " ").uppercase(Locale.US)
    }

    private fun normalizeBoardStockSource(value: String): String {
        return value.trim().replace(Regex("""\s+"""), " ").uppercase(Locale.US)
    }

    private fun stableBoardStockWidthString(value: Double): String {
        val normalized = if (value == -0.0) 0.0 else value
        return BigDecimal.valueOf(normalized).stripTrailingZeros().toPlainString()
    }

    fun makeBoardStockTallyKey(material: String, normalizedWidth: Double, source: String): String {
        val widthPart = stableBoardStockWidthString(normalizedWidth)
        return "board_stock|${normalizeBoardStockMaterial(material)}|$widthPart|${normalizeBoardStockSource(source)}"
    }

    // ── Admin board stock (server-entered items) ─────────────────────────────

    fun makeAdminBoardStockTallyKey(material: String, itemId: String): String =
        "admin_board_stock|${normalizeBoardStockMaterial(material)}|$itemId|ADMIN"

    fun makeAdminBoardStockSkipKey(material: String, itemId: String): String =
        "admin_board_stock_skip|${normalizeBoardStockMaterial(material)}|$itemId|ADMIN"

    private fun makeAdminBoardStockMaterialSkipKey(material: String): String =
        "admin_board_stock_material_skip|${normalizeBoardStockMaterial(material)}|ADMIN"

    fun isAdminBoardStockMaterialSkipped(jobFolderName: String, material: String): Boolean =
        (getTotalsRip10DoneMap(jobFolderName)[makeAdminBoardStockMaterialSkipKey(material)] ?: 0) > 0

    fun setAdminBoardStockMaterialSkipped(jobFolderName: String, material: String, skipped: Boolean) {
        appendAction(
            jobFolderName = jobFolderName,
            docType = "BOARD_STOCK_SKIP",
            rowId = "",
            totalsKey = makeAdminBoardStockMaterialSkipKey(material),
            action = HardwoodTrackerActions.SET_TOTALS_RIP10_DONE_COUNT,
            value = if (skipped) 1 else 0
        )
    }

    fun setAdminBoardStockDone(jobFolderName: String, material: String, itemId: String, doneCount: Int) {
        val key = makeAdminBoardStockTallyKey(material, itemId)
        val normalizedTarget = doneCount.coerceAtLeast(0)
        val current = getTotalsRip10DoneMap(jobFolderName)[key] ?: 0
        if (current == normalizedTarget) return
        appendAction(
            jobFolderName = jobFolderName,
            docType = "BOARD_STOCK",
            rowId = "",
            totalsKey = key,
            action = HardwoodTrackerActions.SET_TOTALS_RIP10_DONE_COUNT,
            value = normalizedTarget
        )
    }

    fun incrementAdminBoardStockDone(jobFolderName: String, material: String, itemId: String, maxCount: Int) {
        changeAdminBoardStockDone(jobFolderName, material, itemId, maxCount, delta = 1)
    }

    fun decrementAdminBoardStockDone(jobFolderName: String, material: String, itemId: String, maxCount: Int) {
        changeAdminBoardStockDone(jobFolderName, material, itemId, maxCount, delta = -1)
    }

    private fun changeAdminBoardStockDone(
        jobFolderName: String,
        material: String,
        itemId: String,
        maxCount: Int,
        delta: Int
    ) {
        val key = makeAdminBoardStockTallyKey(material, itemId)
        changeTotalsDone(
            jobFolderName = jobFolderName,
            docType = "BOARD_STOCK",
            totalsKey = key,
            maxCount = maxCount,
            delta = delta
        )
    }

    fun setAdminBoardStockSkipped(jobFolderName: String, material: String, itemId: String, skipped: Boolean) {
        appendAction(
            jobFolderName = jobFolderName,
            docType = "BOARD_STOCK_SKIP",
            rowId = "",
            totalsKey = makeAdminBoardStockSkipKey(material, itemId),
            action = HardwoodTrackerActions.SET_TOTALS_RIP10_DONE_COUNT,
            value = if (skipped) 1 else 0
        )
    }

    private fun changeTotalsDone(
        jobFolderName: String,
        docType: String,
        totalsKey: String,
        maxCount: Int,
        delta: Int
    ) {
        val clampedMax = maxCount.coerceAtLeast(0)
        appendComputedAction(
            jobFolderName = jobFolderName,
            docType = docType,
            rowId = "",
            totalsKey = totalsKey,
            action = HardwoodTrackerActions.SET_TOTALS_RIP10_DONE_COUNT
        ) { cache ->
            val current = cache.totalsRip10Map[totalsKey] ?: 0
            val next = (current + delta).coerceIn(0, clampedMax)
            next.takeIf { it != current }
        }
    }

    private fun appendComputedAction(
        jobFolderName: String,
        docType: String,
        rowId: String,
        action: String,
        totalsKey: String? = null,
        computeValue: (JobCache) -> Int?
    ) {
        if (readOnly) return
        val snapshot = runCatching {
            synchronized(cacheOperationLockFor(jobFolderName)) {
                val cache = ensureJobCache(jobFolderName)
                synchronized(cache) {
                    val value = computeValue(cache) ?: return
                    val next = HardwoodTrackerAction(
                        docType = docType,
                        rowId = rowId,
                        totalsKey = totalsKey,
                        action = action,
                        value = value,
                        timestamp = Instant.now().toString(),
                        lamport = TrackerLamportClock.next()
                    )
                    cache.localActions.add(next)
                    applyActionToCache(cache, next)
                    cache.localActions.toList()
                }.also { pendingLocalActionsByJob[jobFolderName] = it }
            }
        }.getOrElse {
            appendComputedActionFromDisk(jobFolderName, docType, rowId, totalsKey, action, computeValue)
                ?: return
        }
        persistLocalActionsAsync(jobFolderName, snapshot, publishPending = false)
        bumpProgressVersion()
    }

    private fun appendComputedActionFromDisk(
        jobFolderName: String,
        docType: String,
        rowId: String,
        totalsKey: String?,
        action: String,
        computeValue: (JobCache) -> Int?
    ): List<HardwoodTrackerAction>? {
        return synchronized(cacheOperationLockFor(jobFolderName)) {
            val pendingLocalActions = pendingLocalActionsByJob[jobFolderName]
            val progress = mergePendingLocalActions(
                progress = loadAllProgress(jobFolderName),
                pendingLocalActions = pendingLocalActions
            )
            val fallbackCache = JobCache(
                localActions = mutableListOf(),
                rowProgressMap = mutableMapOf(),
                skippedCabinetMap = mutableMapOf(),
                totalsRip10Map = mutableMapOf()
            )
            progress
                .flatMap { it.actions.orEmpty() }
                .sortedWith(HARDWOOD_TRACKER_TOTAL_ORDER)
                .forEach { applyActionToCache(fallbackCache, it) }
            val value = computeValue(fallbackCache) ?: return@synchronized null
            val next = HardwoodTrackerAction(
                docType = docType,
                rowId = rowId,
                totalsKey = totalsKey,
                action = action,
                value = value,
                timestamp = Instant.now().toString(),
                lamport = TrackerLamportClock.next()
            )
            val localActions = pendingLocalActions ?: loadTabletProgress(jobFolderName).actions
            (localActions + next).also { pendingLocalActionsByJob[jobFolderName] = it }
        }
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

    private fun persistLocalActionsAsync(
        jobFolderName: String,
        actions: List<HardwoodTrackerAction>,
        publishPending: Boolean = true
    ) {
        if (publishPending) pendingLocalActionsByJob[jobFolderName] = actions
        val writeMutex = writeMutexByJob.getOrPut(jobFolderName) { Mutex() }
        ioScope.launch {
            writeMutex.withLock {
                if (pendingLocalActionsByJob[jobFolderName] != actions) return@withLock
                saveTabletProgress(
                    jobFolderName,
                    HardwoodTabletProgress(
                        tabletId = tabletId,
                        actions = actions
                    )
                )
                pendingLocalActionsByJob.remove(jobFolderName, actions)
            }
        }
    }

    private fun saveLocalActionsSync(jobFolderName: String, actions: List<HardwoodTrackerAction>) {
        pendingLocalActionsByJob[jobFolderName] = actions
        val writeMutex = writeMutexByJob.getOrPut(jobFolderName) { Mutex() }
        runBlocking {
            writeMutex.withLock {
                if (pendingLocalActionsByJob[jobFolderName] != actions) return@withLock
                saveTabletProgress(
                    jobFolderName,
                    HardwoodTabletProgress(
                        tabletId = tabletId,
                        actions = actions
                    )
                )
                pendingLocalActionsByJob.remove(jobFolderName, actions)
            }
        }
    }

    /**
     * Blocks the calling thread until all pending async disk writes have completed.
     * Only intended for use in test/migration contexts (e.g. batch-sync JUnit test)
     * where the JVM may exit before the IO coroutines finish writing tracker files.
     * Do NOT call from production UI paths — use the async flow normally.
     */
    fun awaitPendingWrites() {
        runBlocking {
            val scopeJob = ioScope.coroutineContext[kotlinx.coroutines.Job]
            scopeJob?.children?.toList()?.forEach { it.join() }
        }
    }
}
