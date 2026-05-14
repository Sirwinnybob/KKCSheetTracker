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
import com.kkc.sheettracker.data.unified.UnifiedMetadataEngine
import com.kkc.sheettracker.data.unified.UnifiedMetadataEngineRegistry
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
import java.math.BigDecimal
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.Locale

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
    private val cacheByJob = ConcurrentHashMap<String, JobCache>()

    private fun engine(): UnifiedMetadataEngine {
        val existing = unifiedEngine
        if (existing != null) return existing
        return UnifiedMetadataEngineRegistry.getOrCreate(
            baseDir = baseDir,
            isDebugBuild = BuildConfig.DEBUG
        ).also { unifiedEngine = it }
    }

    private fun loadCutlistIndexFromSnapshot(jobFolderName: String): HardwoodCutlistIndex? {
        return engine().getHardwoodsSnapshot(jobFolderName)?.job?.index
    }

    private fun bumpProgressVersion() {
        _progressVersion.value = _progressVersion.value + 1L
    }

    fun invalidateJobCache(jobFolderName: String) {
        cacheByJob.remove(jobFolderName)
        bumpProgressVersion()
    }

    fun invalidateJobCaches(jobFolderNames: Collection<String>) {
        if (jobFolderNames.isEmpty()) return
        jobFolderNames.forEach { cacheByJob.remove(it) }
        bumpProgressVersion()
    }

    fun invalidateAllCaches() {
        cacheByJob.clear()
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
    private fun tabletFile(jobFolderName: String): File = File(trackerDir(jobFolderName), "$tabletId.json")
    private fun boardStockMigrationMarkerFile(jobFolderName: String): File =
        File(trackerDir(jobFolderName), ".board_stock_migration_${tabletId}.json")
    private fun boardStockCanonicalMigrationMarkerFile(jobFolderName: String): File =
        File(trackerDir(jobFolderName), ".board_stock_canonical_migration_${tabletId}.json")

    private fun loadTabletProgress(jobFolderName: String): HardwoodTabletProgress {
        val file = tabletFile(jobFolderName)
        if (!file.exists()) return HardwoodTabletProgress(tabletId = tabletId)
        return try {
            val parsed = gson.fromJson(file.readText(), HardwoodTabletProgress::class.java)
            sanitizeProgress(parsed, fallbackTabletId = tabletId) ?: HardwoodTabletProgress(tabletId = tabletId)
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
        if (readOnly) return
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
        return readProgressFromDir(trackerDir(jobFolderName))
    }

    private fun readProgressFromDir(dir: File): List<HardwoodTabletProgress> {
        if (!dir.exists()) return emptyList()
        return dir.listFiles()
            ?.filter {
                it.isFile &&
                    it.extension.equals("json", ignoreCase = true) &&
                    !it.name.startsWith(".")
            }
            ?.mapNotNull { file ->
                runCatching { gson.fromJson(file.readText(), HardwoodTabletProgress::class.java) }
                    .getOrNull()
                    ?.let { sanitizeProgress(it, fallbackTabletId = file.nameWithoutExtension) }
            }
            ?: emptyList()
    }

    private fun loadAllActions(jobFolderName: String): List<HardwoodTrackerAction> {
        return loadAllProgress(jobFolderName)
            .flatMap { it.actions.orEmpty() }
            .sortedBy { it.timestamp }
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
            totalsKey = (action.totalsKey as String?)?.trim()?.takeIf { it.isNotBlank() },
            action = safeAction,
            value = action.value,
            timestamp = (action.timestamp as String?).orEmpty()
        )
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
        val delta = normalizedTarget - current
        if (delta == 0) return
        appendAction(
            jobFolderName = jobFolderName,
            docType = "BOARD_STOCK",
            rowId = "",
            totalsKey = key,
            action = HardwoodTrackerActions.ADD_TOTALS_RIP10_DONE_COUNT,
            value = delta
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
        return synchronized(cacheByJob) {
            cacheByJob[jobFolderName] ?: buildJobCache(jobFolderName).also { cacheByJob[jobFolderName] = it }
        }
    }

    private fun buildJobCache(jobFolderName: String): JobCache {
        if (!readOnly) {
            migrateLegacyTotalsKeysIfNeeded(jobFolderName)
            migrateBoardStockKeysToCanonicalIfNeeded(jobFolderName)
        }
        val allProgress = loadAllProgress(jobFolderName)
        val lookup = loadCutlistLookup(jobFolderName)
        val allActions = allProgress
            .flatMap { it.actions.orEmpty() }
            .mapNotNull { action -> compactAction(action, lookup) }
            .sortedBy { it.timestamp }
        val localProgress = allProgress.firstOrNull { it.tabletId == tabletId }
            ?: HardwoodTabletProgress(tabletId = tabletId)
        val localActions = localProgress.actions
            .mapNotNull { action -> compactAction(action, lookup) }
            .sortedBy { it.timestamp }
            .toMutableList()
        if (localActions != localProgress.actions) {
            saveTabletProgress(jobFolderName, localProgress.copy(actions = localActions))
        }

        val cache = JobCache(
            localActions = localActions,
            rowProgressMap = mutableMapOf(),
            skippedCabinetMap = mutableMapOf(),
            totalsRip10Map = mutableMapOf()
        )
        allActions.forEach { applyActionToCache(cache, it) }
        return cache
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
        val current = loadTabletProgress(jobFolderName)
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
        if (changed > 0) saveTabletProgress(jobFolderName, current.copy(actions = migratedActions))
        writeMigrationMarker(jobFolderName, migratedCount = changed)
    }

    private fun migrateBoardStockKeysToCanonicalIfNeeded(jobFolderName: String) {
        val marker = boardStockCanonicalMigrationMarkerFile(jobFolderName)
        if (marker.exists()) return
        val current = loadTabletProgress(jobFolderName)
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
        if (changed > 0) saveTabletProgress(jobFolderName, current.copy(actions = migratedActions))
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
