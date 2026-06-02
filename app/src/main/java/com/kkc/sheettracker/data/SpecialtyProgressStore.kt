package com.kkc.sheettracker.data

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.kkc.sheettracker.data.models.SpecialtyCompletionState
import com.kkc.sheettracker.data.models.SpecialtyItem
import com.kkc.sheettracker.data.models.SpecialtyItemAttachment
import com.kkc.sheettracker.data.models.SpecialtyItemCategory
import com.kkc.sheettracker.data.models.SpecialtyResolvedItem
import com.kkc.sheettracker.data.models.SpecialtyStation
import com.kkc.sheettracker.data.models.SpecialtyTrackerProgress
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SpecialtyProgressStore(
    private val baseDir: File,
    private val tabletId: String,
    private val readOnly: Boolean = false
) {
    private data class MergeCandidate(
        val completion: SpecialtyCompletionState,
        val timestampMs: Long,
        val hasValidTimestamp: Boolean,
        val sourceName: String,
        val sourceIndex: Int
    )
    private data class CompletionSeed(
        val itemId: String,
        val completionByKey: Map<String, SpecialtyCompletionState>,
        val sourceName: String,
        val sourceIndex: Int
    )

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val writeMutexByJob = ConcurrentHashMap<String, Mutex>()
    private val resolvedCacheByJob = ConcurrentHashMap<String, List<SpecialtyResolvedItem>>()
    private val _progressVersion = MutableStateFlow(0L)
    val progressVersion: StateFlow<Long> = _progressVersion.asStateFlow()

    companion object {
        const val ITEM_COMPLETION_KEY = "ITEM"
        private const val TRACKER_SCHEMA_V2 = 2
    }

    fun loadSpecialtyItems(jobFolderName: String): List<SpecialtyItem> {
        return loadMergedSpecialtyItems(jobFolderName)
    }

    fun loadResolvedItems(jobFolderName: String): List<SpecialtyResolvedItem> {
        resolvedCacheByJob[jobFolderName]?.let { cached -> return cached }
        val items = loadSpecialtyItems(jobFolderName)
        val merged = loadMergedCompletionByItem(jobFolderName)

        val resolved = items.map { item ->
            val mergedByKey = merged[item.id].orEmpty()
            val completionByKey = resolveCompletionByKey(item, mergedByKey)
            SpecialtyResolvedItem(
                item = item,
                completionByKey = completionByKey,
                isComplete = isItemComplete(item, completionByKey)
            )
        }
        resolvedCacheByJob[jobFolderName] = resolved
        return resolved
    }

    suspend fun setCompletion(
        jobFolderName: String,
        itemId: String,
        completionKey: String,
        completed: Boolean,
        completedBy: String = tabletId,
        completedAt: String = Instant.now().toString()
    ) {
        setCompletions(
            jobFolderName = jobFolderName,
            itemId = itemId,
            completionKeys = listOf(completionKey),
            completed = completed,
            completedBy = completedBy,
            completedAt = completedAt
        )
    }

    suspend fun setCompletions(
        jobFolderName: String,
        itemId: String,
        completionKeys: Collection<String>,
        completed: Boolean,
        completedBy: String = tabletId,
        completedAt: String = Instant.now().toString()
    ) {
        if (readOnly) return
        if (itemId.isBlank()) return
        if (completionKeys.isEmpty()) return

        val normalizedKeys = completionKeys
            .map { normalizeCompletionKey(it) }
            .distinct()
            .sortedWith(String.CASE_INSENSITIVE_ORDER)

        val mutex = writeMutexByJob.getOrPut(jobFolderName) { Mutex() }
        mutex.withLock {
            val current = loadLocalTrackerProgress(jobFolderName)
            val nextByItem = current.itemCompletions.toMutableMap()
            val nextByKey = nextByItem[itemId].orEmpty().toMutableMap()
            normalizedKeys.forEach { normalizedKey ->
                nextByKey[normalizedKey] = SpecialtyCompletionState(
                    completed = completed,
                    completedAt = completedAt,
                    completedBy = completedBy
                )
            }
            nextByItem[itemId] = nextByKey.toMap()

            val next = SpecialtyTrackerProgress(
                tabletId = current.tabletId.ifBlank { tabletId },
                schemaVersion = TRACKER_SCHEMA_V2,
                itemCompletions = nextByItem.toMap()
            )
            writeTrackerProgress(jobFolderName, next)
            invalidateJobCache(jobFolderName)
        }
    }

    suspend fun updateSpecialtyItemFields(
        jobFolderName: String,
        itemId: String,
        dimensions: String?,
        quantity: Int?,
        material: String?
    ) {
        if (readOnly) return
        if (itemId.isBlank()) return
        val mutex = writeMutexByJob.getOrPut(jobFolderName) { Mutex() }
        mutex.withLock {
            val file = specialtyItemsFile(jobFolderName)
            if (!file.exists() || !file.isFile) return@withLock
            val raw = runCatching { file.readText() }.getOrNull() ?: return@withLock
            val root = runCatching { JsonParser.parseString(raw) }.getOrNull() ?: return@withLock
            val itemsArray = when {
                root.isJsonArray -> root.asJsonArray
                root.isJsonObject -> root.asJsonObject.getAsJsonArray("items")
                else -> null
            } ?: return@withLock

            var modified = false
            itemsArray.forEach { element ->
                val obj = element as? JsonObject ?: return@forEach
                if (obj.getString("id") != itemId) return@forEach
                if (dimensions != null) obj.addProperty("dimensions", dimensions) else obj.remove("dimensions")
                if (quantity != null) obj.addProperty("quantity", quantity) else obj.remove("quantity")
                if (material != null) obj.addProperty("material", material) else obj.remove("material")
                modified = true
            }

            if (!modified) return@withLock
            val output: com.google.gson.JsonElement = if (root.isJsonObject) {
                root.asJsonObject.apply { add("items", itemsArray) }
            } else itemsArray
            atomicWrite(file, gson.toJson(output))
            invalidateJobCache(jobFolderName)
        }
    }

    fun invalidateJobCache(jobFolderName: String) {
        resolvedCacheByJob.remove(jobFolderName)
        bumpProgressVersion()
    }

    fun invalidateJobCaches(jobFolderNames: Collection<String>) {
        if (jobFolderNames.isEmpty()) return
        jobFolderNames.forEach { resolvedCacheByJob.remove(it) }
        bumpProgressVersion()
    }

    fun invalidateAllCaches() {
        resolvedCacheByJob.clear()
        bumpProgressVersion()
    }

    fun invalidateFromTrackerFile(trackerFile: File): Boolean {
        val trackerDir = trackerFile.parentFile ?: return false
        if (!trackerDir.name.equals(".tracker", ignoreCase = true)) return false
        val adminDir = trackerDir.parentFile ?: return false
        if (!adminDir.name.equals("admin", ignoreCase = true)) return false
        val metadataDir = adminDir.parentFile ?: return false
        if (!metadataDir.name.equals(".metadata", ignoreCase = true)) return false
        val jobDir = metadataDir.parentFile ?: return false
        if (jobDir.name.isBlank()) return false
        invalidateJobCache(jobDir.name)
        return true
    }

    private fun loadMergedCompletionByItem(jobFolderName: String): Map<String, Map<String, SpecialtyCompletionState>> {
        val items = loadSpecialtyItems(jobFolderName)
        val checklistSeeds = loadChecklistCompletionSeeds(jobFolderName, items)
        val files = trackerDir(jobFolderName)
            .listFiles()
            ?.filter { it.isFile && it.extension.equals("json", ignoreCase = true) && !it.name.startsWith(".") }
            ?.sortedBy { it.name.lowercase(Locale.US) }
            .orEmpty()

        val merged = mutableMapOf<String, MutableMap<String, MergeCandidate>>()
        checklistSeeds.forEach { seed ->
            val itemMap = merged.getOrPut(seed.itemId) { mutableMapOf() }
            seed.completionByKey.forEach { (rawKey, completion) ->
                val key = normalizeCompletionKey(rawKey)
                val candidate = MergeCandidate(
                    completion = completion,
                    timestampMs = parseTimestampMillis(completion.completedAt),
                    hasValidTimestamp = hasValidTimestamp(completion.completedAt),
                    sourceName = seed.sourceName,
                    sourceIndex = seed.sourceIndex
                )
                val existing = itemMap[key]
                if (existing == null || shouldReplace(existing, candidate)) {
                    itemMap[key] = candidate
                }
            }
        }

        files.forEachIndexed { sourceIndex, file ->
            val parsed = runCatching {
                parseTrackerProgress(file.readText(), fallbackTabletId = file.nameWithoutExtension)
            }.getOrNull() ?: return@forEachIndexed

            parsed.itemCompletions.forEach { (itemId, byKey) ->
                val itemMap = merged.getOrPut(itemId) { mutableMapOf() }
                byKey.forEach { (rawKey, completion) ->
                    val key = normalizeCompletionKey(rawKey)
                    val candidate = MergeCandidate(
                        completion = completion,
                        timestampMs = parseTimestampMillis(completion.completedAt),
                        hasValidTimestamp = hasValidTimestamp(completion.completedAt),
                        sourceName = parsed.tabletId,
                        sourceIndex = sourceIndex
                    )
                    val existing = itemMap[key]
                    if (existing == null || shouldReplace(existing, candidate)) {
                        itemMap[key] = candidate
                    }
                }
            }
        }

        return merged.mapValues { (_, byKey) -> byKey.mapValues { (_, candidate) -> candidate.completion } }
    }

    private fun shouldReplace(existing: MergeCandidate, candidate: MergeCandidate): Boolean {
        val old = existing
        val next = candidate
        if (next.hasValidTimestamp != old.hasValidTimestamp) {
            return next.hasValidTimestamp
        }
        if (next.timestampMs != old.timestampMs) return next.timestampMs > old.timestampMs
        val sourceCmp = next.sourceName.compareTo(old.sourceName, ignoreCase = true)
        if (sourceCmp != 0) return sourceCmp > 0
        return next.sourceIndex > old.sourceIndex
    }

    private fun parseSpecialtyItems(raw: String): List<SpecialtyItem> {
        val root = runCatching { JsonParser.parseString(raw) }.getOrNull() ?: return emptyList()
        val itemsArray = when {
            root.isJsonArray -> root.asJsonArray
            root.isJsonObject -> root.asJsonObject.getAsJsonArray("items")
            else -> null
        } ?: return emptyList()

        return itemsArray.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val id = obj.getString("id")
            val name = obj.getString("name")
            if (id.isBlank() || name.isBlank()) return@mapNotNull null

            val explicitStations = parseStations(obj.get("stations"))
            val stations = if (explicitStations.isNotEmpty()) explicitStations
                else parseModeStations(obj.get("modes"))
            SpecialtyItem(
                id = id,
                name = name,
                cabinetNumbers = obj.getFlexibleStringList("cabinetNumbers"),
                category = parseCategory(obj.getString("category")),
                stations = stations,
                supplier = obj.getNullableString("supplier"),
                model = obj.getFirstNonBlankString("model", "modelNumber"),
                orderDate = obj.getNullableString("orderDate"),
                tracking = obj.getFirstNonBlankString("tracking", "trackingNumber"),
                orderUrl = obj.getNullableString("orderUrl"),
                notes = obj.getNullableString("notes"),
                attachments = obj.getAttachments("attachments"),
                autoDetected = obj.getBoolean("autoDetected"),
                createdAt = obj.getNullableString("createdAt"),
                createdBy = obj.getNullableString("createdBy"),
                dimensions = obj.getNullableString("dimensions"),
                quantity = runCatching { obj.get("quantity")?.let { e -> if (e.isJsonPrimitive && e.asJsonPrimitive.isNumber) e.asInt else null } }.getOrNull(),
                material = obj.getNullableString("material")
            )
        }
    }

    private fun parseCategory(raw: String): SpecialtyItemCategory {
        return when (raw.trim().uppercase(Locale.US)) {
            SpecialtyItemCategory.TO_ORDER.name -> SpecialtyItemCategory.TO_ORDER
            else -> SpecialtyItemCategory.CUSTOM
        }
    }

    private fun parseStations(element: JsonElement?): List<SpecialtyStation> {
        if (element == null || !element.isJsonArray) return emptyList()
        val out = mutableListOf<SpecialtyStation>()
        element.asJsonArray.forEach { stationElement ->
            val key = stationElement.asStringOrNull()?.trim()?.uppercase(Locale.US).orEmpty()
            val station = runCatching { SpecialtyStation.valueOf(key) }.getOrNull() ?: return@forEach
            if (station !in out) out += station
        }
        return out
    }

    private fun loadLocalTrackerProgress(jobFolderName: String): SpecialtyTrackerProgress {
        val file = tabletFile(jobFolderName)
        if (!file.exists()) {
            return SpecialtyTrackerProgress(
                tabletId = tabletId,
                schemaVersion = TRACKER_SCHEMA_V2,
                itemCompletions = emptyMap()
            )
        }
        return runCatching {
            parseTrackerProgress(file.readText(), fallbackTabletId = tabletId)
        }.getOrElse {
            SpecialtyTrackerProgress(
                tabletId = tabletId,
                schemaVersion = TRACKER_SCHEMA_V2,
                itemCompletions = emptyMap()
            )
        }
    }

    private fun parseTrackerProgress(raw: String, fallbackTabletId: String): SpecialtyTrackerProgress {
        val root = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull() ?: return SpecialtyTrackerProgress(
            tabletId = fallbackTabletId,
            schemaVersion = TRACKER_SCHEMA_V2,
            itemCompletions = emptyMap()
        )

        val safeTabletId = root.getFirstNonBlankString("tabletId", "deviceId").ifBlank { fallbackTabletId }
        val schemaVersion = root.get("schemaVersion")?.asIntOrNull() ?: 1
        val completionsObj = root.get("completions") as? JsonObject

        val byItem = mutableMapOf<String, Map<String, SpecialtyCompletionState>>()
        completionsObj?.entrySet()?.forEach { (itemId, valueElement) ->
            val parsed = parseCompletionEntry(valueElement)
            if (parsed.isNotEmpty()) {
                byItem[itemId] = parsed
            }
        }

        return SpecialtyTrackerProgress(
            tabletId = safeTabletId,
            schemaVersion = schemaVersion,
            itemCompletions = byItem
        )
    }

    private fun parseCompletionEntry(element: JsonElement?): Map<String, SpecialtyCompletionState> {
        if (element == null || element.isJsonNull) {
            return mapOf(ITEM_COMPLETION_KEY to SpecialtyCompletionState(completed = false))
        }
        if (!element.isJsonObject) {
            return mapOf(ITEM_COMPLETION_KEY to parseCompletionState(element))
        }

        val obj = element.asJsonObject
        if (looksLikeCompletionState(obj)) {
            return mapOf(ITEM_COMPLETION_KEY to parseCompletionState(obj))
        }

        val out = mutableMapOf<String, SpecialtyCompletionState>()

        val completionObj = obj.get("completion")
        if (completionObj != null) {
            out[ITEM_COMPLETION_KEY] = parseCompletionState(completionObj)
        }

        val stationsObj = when {
            obj.has("stations") -> obj.get("stations") as? JsonObject
            obj.has("stationChecks") -> obj.get("stationChecks") as? JsonObject
            obj.has("checks") -> obj.get("checks") as? JsonObject
            obj.has("subChecks") -> obj.get("subChecks") as? JsonObject
            else -> null
        }
        stationsObj?.entrySet()?.forEach { (key, value) ->
            out[normalizeCompletionKey(key)] = parseCompletionState(value)
        }

        if (out.isNotEmpty()) return out

        obj.entrySet().forEach { (key, value) ->
            if (value == null) return@forEach
            if (!value.isJsonObject && !value.isJsonNull && !value.isJsonPrimitive) return@forEach
            out[normalizeCompletionKey(key)] = parseCompletionState(value)
        }

        return out
    }

    private fun looksLikeCompletionState(obj: JsonObject): Boolean {
        return obj.has("completed") || obj.has("completedAt") || obj.has("completedBy")
    }

    private fun parseCompletionState(element: JsonElement?): SpecialtyCompletionState {
        if (element == null || element.isJsonNull) {
            return SpecialtyCompletionState(completed = false)
        }
        if (element.isJsonPrimitive) {
            val primitive = element.asJsonPrimitive
            return when {
                primitive.isBoolean -> SpecialtyCompletionState(completed = primitive.asBoolean)
                primitive.isString -> {
                    val raw = primitive.asString.orEmpty().trim()
                    val parsedBool = parseLegacyBooleanString(raw)
                    when {
                        parsedBool != null -> SpecialtyCompletionState(completed = parsedBool)
                        hasValidTimestamp(raw) -> SpecialtyCompletionState(completed = true, completedAt = raw)
                        else -> SpecialtyCompletionState(completed = false)
                    }
                }
                else -> SpecialtyCompletionState(completed = false)
            }
        }

        val obj = element as? JsonObject ?: return SpecialtyCompletionState(completed = false)
        val completedAt = obj.getNullableString("completedAt")
        val explicitCompleted = obj.get("completed")?.asBooleanOrNull()
        val completed = explicitCompleted ?: !completedAt.isNullOrBlank()
        return SpecialtyCompletionState(
            completed = completed,
            completedAt = completedAt,
            completedBy = obj.getNullableString("completedBy")
        )
    }

    private fun resolveCompletionByKey(
        item: SpecialtyItem,
        mergedByKey: Map<String, SpecialtyCompletionState>
    ): Map<String, SpecialtyCompletionState> {
        return if (requiresStationSplit(item)) {
            val fallbackLegacy = mergedByKey[ITEM_COMPLETION_KEY]
            item.stations.associate { station ->
                val key = station.name
                key to (mergedByKey[key] ?: fallbackLegacy ?: SpecialtyCompletionState(completed = false))
            }
        } else {
            val stationFallback = item.stations.firstOrNull()?.let { station -> mergedByKey[station.name] }
            mapOf(
                ITEM_COMPLETION_KEY to (
                    mergedByKey[ITEM_COMPLETION_KEY] ?: stationFallback ?: SpecialtyCompletionState(completed = false)
                    )
            )
        }
    }

    private fun isItemComplete(item: SpecialtyItem, completionByKey: Map<String, SpecialtyCompletionState>): Boolean {
        return if (requiresStationSplit(item)) {
            item.stations.all { station -> completionByKey[station.name]?.completed == true }
        } else {
            completionByKey[ITEM_COMPLETION_KEY]?.completed == true
        }
    }

    private fun requiresStationSplit(item: SpecialtyItem): Boolean {
        return item.category == SpecialtyItemCategory.CUSTOM && item.stations.size >= 2
    }

    private fun parseTimestampMillis(raw: String?): Long {
        if (raw.isNullOrBlank()) return 0L
        return runCatching { Instant.parse(raw).toEpochMilli() }.getOrDefault(0L)
    }

    private fun hasValidTimestamp(raw: String?): Boolean {
        if (raw.isNullOrBlank()) return false
        return runCatching {
            Instant.parse(raw)
            true
        }.getOrDefault(false)
    }

    private fun parseLegacyBooleanString(raw: String): Boolean? {
        if (raw.isBlank()) return false
        return when (raw.trim().lowercase(Locale.US)) {
            "true", "1", "yes", "y", "on", "complete", "completed", "done" -> true
            "false", "0", "no", "n", "off", "incomplete", "uncomplete", "undone", "null", "none" -> false
            else -> null
        }
    }

    private fun normalizeCompletionKey(rawKey: String): String {
        val normalized = rawKey.trim().uppercase(Locale.US)
        return when (normalized) {
            "", "COMPLETION", "ITEM" -> ITEM_COMPLETION_KEY
            else -> normalized
        }
    }

    private fun bumpProgressVersion() {
        _progressVersion.value = _progressVersion.value + 1L
    }

    private fun writeTrackerProgress(jobFolderName: String, progress: SpecialtyTrackerProgress) {
        val root = JsonObject().apply {
            addProperty("tabletId", progress.tabletId)
            addProperty("schemaVersion", TRACKER_SCHEMA_V2)
            add("completions", JsonObject().also { completionsObj ->
                progress.itemCompletions.toSortedMap(String.CASE_INSENSITIVE_ORDER).forEach { (itemId, byKey) ->
                    completionsObj.add(itemId, JsonObject().apply {
                        byKey[ITEM_COMPLETION_KEY]?.let { completion ->
                            add("completion", completion.toJson())
                        }
                        val stationEntries = byKey
                            .filterKeys { normalizeCompletionKey(it) != ITEM_COMPLETION_KEY }
                            .toSortedMap(String.CASE_INSENSITIVE_ORDER)
                        if (stationEntries.isNotEmpty()) {
                            add("stations", JsonObject().also { stationsObj ->
                                stationEntries.forEach { (key, completion) ->
                                    stationsObj.add(normalizeCompletionKey(key), completion.toJson())
                                }
                            })
                        }
                    })
                }
            })
        }
        atomicWrite(tabletFile(jobFolderName), gson.toJson(root))
    }

    private fun SpecialtyCompletionState.toJson(): JsonObject {
        return JsonObject().apply {
            addProperty("completed", completed)
            if (!completedAt.isNullOrBlank()) addProperty("completedAt", completedAt)
            if (!completedBy.isNullOrBlank()) addProperty("completedBy", completedBy)
        }
    }

    private fun atomicWrite(target: File, body: String) {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, "${target.name}.tmp-${System.nanoTime()}")
        temp.writeText(body)

        try {
            Files.move(
                temp.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                temp.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
        }
    }

    private fun specialtyItemsFile(jobFolderName: String): File {
        return File(baseDir, "$jobFolderName/.metadata/admin/specialty_items.json")
    }
    private fun checklistFile(jobFolderName: String): File {
        return File(baseDir, "$jobFolderName/.metadata/admin/checklist.json")
    }

    private fun trackerDir(jobFolderName: String): File {
        return File(baseDir, "$jobFolderName/.metadata/admin/.tracker")
    }

    private fun tabletFile(jobFolderName: String): File {
        return File(trackerDir(jobFolderName), "$tabletId.json")
    }

    private fun JsonObject.getString(name: String): String {
        return get(name)?.asStringOrNull()?.trim().orEmpty()
    }

    private fun JsonObject.getNullableString(name: String): String? {
        return get(name)?.asStringOrNull()?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun JsonObject.getBoolean(name: String): Boolean {
        return get(name)?.asBooleanOrNull() ?: false
    }

    private fun JsonObject.getStringList(name: String): List<String> {
        val array = get(name)
        if (array == null || !array.isJsonArray) return emptyList()
        return array.asJsonArray
            .mapNotNull { element -> element.asStringOrNull()?.trim()?.takeIf { it.isNotBlank() } }
    }
    private fun JsonObject.getFlexibleStringList(name: String): List<String> {
        val raw = get(name) ?: return emptyList()
        if (!raw.isJsonArray) return emptyList()
        return raw.asJsonArray
            .mapNotNull { element ->
                when {
                    element.isJsonNull -> null
                    element.isJsonPrimitive -> element.asStringOrNull()?.trim()?.takeIf { it.isNotBlank() }
                    element.isJsonObject -> element.asJsonObject.getFirstNonBlankString("value", "id", "name")
                    else -> null
                }
            }
    }
    private fun JsonObject.getFirstNonBlankString(vararg names: String): String {
        names.forEach { name ->
            getNullableString(name)?.let { return it }
        }
        return ""
    }
    private fun JsonObject.getAttachments(name: String): List<SpecialtyItemAttachment> {
        val raw = get(name) ?: return emptyList()
        if (!raw.isJsonArray) return emptyList()
        return raw.asJsonArray.mapNotNull { element ->
            when {
                element.isJsonNull -> null
                element.isJsonPrimitive -> {
                    val str = element.asStringOrNull()?.trim()?.takeIf { it.isNotBlank() }
                        ?: return@mapNotNull null
                    SpecialtyItemAttachment(
                        id = str,
                        filename = str,
                        originalName = str,
                        mimeType = null
                    )
                }
                element.isJsonObject -> {
                    val obj = element.asJsonObject
                    val id = obj.getNullableString("id")?.trim()?.takeIf { it.isNotBlank() }
                        ?: return@mapNotNull null
                    val filename = obj.getFirstNonBlankString("filename", "storedName")
                    val originalName = obj.getFirstNonBlankString("originalName", "filename", "name", "id")
                    if (filename.isBlank() && originalName.isBlank()) return@mapNotNull null
                    SpecialtyItemAttachment(
                        id = id,
                        filename = filename.ifBlank { originalName },
                        originalName = originalName.ifBlank { filename },
                        mimeType = obj.getNullableString("mimeType")
                    )
                }
                else -> null
            }
        }
    }

    private fun loadMergedSpecialtyItems(jobFolderName: String): List<SpecialtyItem> {
        val specialtyItems = specialtyItemsFile(jobFolderName)
            .takeIf { it.exists() && it.isFile }
            ?.let { parseSpecialtyItems(it.readText()) }
            .orEmpty()
        val checklistItems = checklistFile(jobFolderName)
            .takeIf { it.exists() && it.isFile }
            ?.let { parseChecklistAsSpecialtyItems(it.readText()) }
            .orEmpty()
        val tabletItems = loadTabletItems(jobFolderName)

        val allItems = specialtyItems + checklistItems + tabletItems
        if (allItems.isEmpty()) return emptyList()

        return allItems
            .distinctBy { it.id }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }

    private fun loadTabletItems(jobFolderName: String): List<SpecialtyItem> {
        val dir = File(baseDir, "$jobFolderName/.metadata/admin")
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles()
            ?.filter { it.isFile && it.name.startsWith("tablet_items_") && it.extension.equals("json", ignoreCase = true) }
            ?.sortedBy { it.name.lowercase(Locale.US) }
            ?.flatMap { parseTabletItemsFile(it) }
            .orEmpty()
    }

    private fun parseTabletItemsFile(file: File): List<SpecialtyItem> {
        val raw = runCatching { file.readText() }.getOrNull() ?: return emptyList()
        val root = runCatching { JsonParser.parseString(raw) }.getOrNull() ?: return emptyList()
        val array = when {
            root.isJsonArray -> root.asJsonArray
            root.isJsonObject -> root.asJsonObject.getAsJsonArray("items")
            else -> null
        } ?: return emptyList()
        return array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val id = obj.getString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val name = obj.getString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            SpecialtyItem(
                id = "tablet:$id",
                name = name,
                cabinetNumbers = obj.getFlexibleStringList("cabinetNumbers"),
                category = parseCategory(obj.getString("category")),
                stations = parseStations(obj.get("stations")),
                supplier = obj.getNullableString("supplier"),
                model = obj.getFirstNonBlankString("modelNumber", "model"),
                orderDate = obj.getNullableString("orderDate"),
                tracking = obj.getFirstNonBlankString("trackingNumber", "tracking"),
                orderUrl = obj.getNullableString("orderUrl"),
                notes = obj.getNullableString("notes"),
                attachments = emptyList(),
                autoDetected = false,
                createdAt = obj.getNullableString("createdAt"),
                createdBy = obj.getNullableString("createdByDevice"),
                dimensions = obj.getNullableString("dimensions"),
                quantity = runCatching {
                    obj.get("quantity")?.let { e ->
                        if (e.isJsonPrimitive && e.asJsonPrimitive.isNumber) e.asInt else null
                    }
                }.getOrNull(),
                material = obj.getNullableString("material")
            )
        }
    }

    private fun parseChecklistAsSpecialtyItems(raw: String): List<SpecialtyItem> {
        val root = runCatching { JsonParser.parseString(raw) }.getOrNull() ?: return emptyList()
        val itemsArray = when {
            root.isJsonArray -> root.asJsonArray
            root.isJsonObject -> root.asJsonObject.getAsJsonArray("items")
            else -> null
        } ?: return emptyList()

        return itemsArray.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val checklistId = obj.getString("id")
            val name = obj.getFirstNonBlankString("text", "name")
            if (checklistId.isBlank() || name.isBlank()) return@mapNotNull null
            if (!isChecklistIncludedForSpecialty(obj.get("modes"))) return@mapNotNull null

            val category = parseCategory(obj.getFirstNonBlankString("category"))
            // Prefer explicit stations field (SAW, EDGE_BANDER, ASSEMBLY set by admin);
            // fall back to deriving stations from modes for legacy items with no stations field.
            val explicitStations = parseStations(obj.get("stations"))
            val stations = if (explicitStations.isNotEmpty()) explicitStations
                else parseModeStations(obj.get("modes"))

            SpecialtyItem(
                id = "checklist:$checklistId",
                name = name,
                cabinetNumbers = obj.getFlexibleStringList("cabinetNumbers"),
                category = category,
                stations = stations,
                supplier = obj.getNullableString("supplier"),
                model = obj.getFirstNonBlankString("model", "modelNumber"),
                orderDate = obj.getNullableString("orderDate"),
                tracking = obj.getFirstNonBlankString("tracking", "trackingNumber"),
                orderUrl = obj.getNullableString("orderUrl"),
                notes = obj.getNullableString("notes"),
                attachments = obj.getAttachments("attachments"),
                autoDetected = false,
                createdAt = obj.getNullableString("createdAt"),
                createdBy = obj.getNullableString("createdBy"),
                dimensions = obj.getNullableString("dimensions"),
                quantity = runCatching {
                    obj.get("quantity")?.let { e ->
                        if (e.isJsonPrimitive && e.asJsonPrimitive.isNumber) e.asInt else null
                    }
                }.getOrNull(),
                material = obj.getNullableString("material")
            )
        }
    }

    private fun isChecklistIncludedForSpecialty(modesElement: JsonElement?): Boolean {
        if (modesElement == null || modesElement.isJsonNull) return true
        if (!modesElement.isJsonArray) return true
        val modes = modesElement.asJsonArray
            .mapNotNull { it.asStringOrNull()?.trim()?.uppercase(Locale.US) }
        if (modes.isEmpty()) return true
        return "SPECIALTY" in modes
    }

    private fun parseModeStations(modesElement: JsonElement?): List<SpecialtyStation> {
        if (modesElement == null || !modesElement.isJsonArray) return emptyList()
        val out = mutableListOf<SpecialtyStation>()
        modesElement.asJsonArray.forEach { modeElement ->
            val key = modeElement.asStringOrNull()?.trim()?.uppercase(Locale.US).orEmpty()
            val station = when (key) {
                "CNC" -> SpecialtyStation.CNC
                "HARDWOODS" -> SpecialtyStation.HARDWOODS
                "ASSEMBLY" -> SpecialtyStation.ASSEMBLY
                "SPECIALTY" -> SpecialtyStation.SPECIALTY
                else -> null
            } ?: return@forEach
            if (station !in out) out += station
        }
        return out
    }

    private fun loadChecklistCompletionSeeds(
        jobFolderName: String,
        specialtyItems: List<SpecialtyItem>
    ): List<CompletionSeed> {
        val file = checklistFile(jobFolderName)
        if (!file.exists() || !file.isFile) return emptyList()
        val root = runCatching { JsonParser.parseString(file.readText()) }.getOrNull() ?: return emptyList()
        val itemsArray = when {
            root.isJsonArray -> root.asJsonArray
            root.isJsonObject -> root.asJsonObject.getAsJsonArray("items")
            else -> null
        } ?: return emptyList()

        val specialtyByChecklistId = specialtyItems
            .filter { it.id.startsWith("checklist:", ignoreCase = true) }
            .associateBy { it.id.removePrefix("checklist:") }

        return itemsArray.mapIndexedNotNull { index, element ->
            val obj = element as? JsonObject ?: return@mapIndexedNotNull null
            val checklistId = obj.getString("id")
            if (checklistId.isBlank()) return@mapIndexedNotNull null
            if (!isChecklistIncludedForSpecialty(obj.get("modes"))) return@mapIndexedNotNull null
            val mapped = specialtyByChecklistId[checklistId] ?: return@mapIndexedNotNull null

            val completedAt = obj.getNullableString("completedAt")
            val completedBy = obj.getNullableString("completedBy")
            val completion = SpecialtyCompletionState(
                completed = !completedAt.isNullOrBlank(),
                completedAt = completedAt,
                completedBy = completedBy
            )
            CompletionSeed(
                itemId = mapped.id,
                completionByKey = mapOf(ITEM_COMPLETION_KEY to completion),
                sourceName = "checklist",
                sourceIndex = Int.MIN_VALUE + index
            )
        }
    }

    private fun JsonElement.asStringOrNull(): String? {
        return runCatching {
            if (isJsonNull) null
            else if (isJsonPrimitive) {
                val primitive = asJsonPrimitive
                when {
                    primitive.isString -> primitive.asString
                    primitive.isNumber -> primitive.asNumber.toString()
                    primitive.isBoolean -> primitive.asBoolean.toString()
                    else -> null
                }
            } else {
                null
            }
        }.getOrNull()
    }

    private fun JsonElement.asBooleanOrNull(): Boolean? {
        return runCatching {
            if (isJsonNull || !isJsonPrimitive) return@runCatching null
            val primitive = asJsonPrimitive
            when {
                primitive.isBoolean -> primitive.asBoolean
                primitive.isString -> parseLegacyBooleanString(primitive.asString)
                primitive.isNumber -> primitive.asInt != 0
                else -> null
            }
        }.getOrNull()
    }

    private fun JsonElement.asIntOrNull(): Int? {
        return runCatching {
            if (isJsonNull || !isJsonPrimitive) return@runCatching null
            asInt
        }.getOrNull()
    }
}
