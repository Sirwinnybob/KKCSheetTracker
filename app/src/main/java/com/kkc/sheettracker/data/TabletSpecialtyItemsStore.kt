package com.kkc.sheettracker.data

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.kkc.sheettracker.data.models.SpecialtyItemCategory
import com.kkc.sheettracker.data.models.SpecialtyStation
import com.kkc.sheettracker.data.models.TabletSpecialtyItem
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class TabletSpecialtyItemsStore(
    private val baseDir: File,
    val tabletId: String        // public so SpecialtyStateStore can expose it
) {
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val writeMutexByJob = ConcurrentHashMap<String, Mutex>()

    /** Returns items from ALL tablet files for this job, merged into one list. */
    fun loadAllItems(jobFolderName: String): List<TabletSpecialtyItem> {
        val dir = adminDir(jobFolderName)
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles()
            ?.filter { it.isFile && it.name.startsWith("tablet_items_") && it.extension.equals("json", ignoreCase = true) && !it.name.contains(".sync-conflict-") }
            ?.sortedBy { it.name.lowercase(Locale.US) }
            ?.flatMap { parseFile(it) }
            .orEmpty()
    }

    /** Saves (create or update by id) an item in this tablet's own file. */
    suspend fun saveItem(jobFolderName: String, item: TabletSpecialtyItem) {
        val mutex = writeMutexByJob.getOrPut(jobFolderName) { Mutex() }
        mutex.withLock {
            val existing = loadOwnItems(jobFolderName).toMutableList()
            val idx = existing.indexOfFirst { it.id == item.id }
            if (idx >= 0) existing[idx] = item else existing += item
            writeItems(jobFolderName, existing)
        }
    }

    /** Deletes an item from this tablet's own file. `itemId` may include the "tablet:" prefix. */
    suspend fun deleteItem(jobFolderName: String, itemId: String) {
        val rawId = itemId.removePrefix("tablet:")
        val mutex = writeMutexByJob.getOrPut(jobFolderName) { Mutex() }
        mutex.withLock {
            val updated = loadOwnItems(jobFolderName).filter { it.id != rawId }
            writeItems(jobFolderName, updated)
        }
    }

    // ── Private helpers ──────────────────────────────────────────────────────────

    private fun loadOwnItems(jobFolderName: String): List<TabletSpecialtyItem> {
        val file = ownFile(jobFolderName)
        if (!file.exists()) return emptyList()
        return parseFile(file)
    }

    private fun parseFile(file: File): List<TabletSpecialtyItem> {
        val raw = runCatching { file.readText() }.getOrNull() ?: return emptyList()
        val root = runCatching { JsonParser.parseString(raw) }.getOrNull() ?: return emptyList()
        val array = when {
            root.isJsonArray -> root.asJsonArray
            root.isJsonObject -> root.asJsonObject.getAsJsonArray("items")
            else -> null
        } ?: return emptyList()
        return array.mapNotNull { el -> parseItem(el as? JsonObject ?: return@mapNotNull null) }
    }

    private fun parseItem(obj: JsonObject): TabletSpecialtyItem? {
        val id = obj.getStr("id").takeIf { it.isNotBlank() } ?: return null
        val name = obj.getStr("name").takeIf { it.isNotBlank() } ?: return null
        val category = when (obj.getStr("category").uppercase(Locale.US)) {
            "TO_ORDER" -> SpecialtyItemCategory.TO_ORDER
            else -> SpecialtyItemCategory.CUSTOM
        }
        val stations = obj.get("stations")
            ?.takeIf { it.isJsonArray }
            ?.asJsonArray
            ?.mapNotNull { el ->
                runCatching { SpecialtyStation.valueOf(el.asString.trim().uppercase(Locale.US)) }.getOrNull()
            }
            .orEmpty()
        val cabinetNumbers = obj.get("cabinetNumbers")
            ?.takeIf { it.isJsonArray }
            ?.asJsonArray
            ?.mapNotNull { el ->
                runCatching { el.asString.trim().takeIf { it.isNotBlank() } }.getOrNull()
            }
            .orEmpty()
        return TabletSpecialtyItem(
            id = id,
            name = name,
            category = category,
            cabinetNumbers = cabinetNumbers,
            stations = stations,
            dimensions = obj.getNullStr("dimensions"),
            quantity = runCatching { obj.get("quantity")?.takeIf { !it.isJsonNull }?.asDouble }.getOrNull(),
            material = obj.getNullStr("material"),
            supplier = obj.getNullStr("supplier"),
            modelNumber = obj.getNullStr("modelNumber"),
            orderDate = obj.getNullStr("orderDate"),
            trackingNumber = obj.getNullStr("trackingNumber"),
            orderUrl = obj.getNullStr("orderUrl"),
            notes = obj.getNullStr("notes"),
            createdAt = obj.getStr("createdAt"),
            createdByDevice = obj.getStr("createdByDevice")
        )
    }

    private fun writeItems(jobFolderName: String, items: List<TabletSpecialtyItem>) {
        val array = JsonArray()
        items.forEach { item ->
            array.add(JsonObject().apply {
                addProperty("id", item.id)
                addProperty("name", item.name)
                addProperty("category", item.category.name)
                add("cabinetNumbers", JsonArray().also { arr -> item.cabinetNumbers.forEach { arr.add(it) } })
                add("stations", JsonArray().also { arr -> item.stations.forEach { arr.add(it.name) } })
                item.dimensions?.let { addProperty("dimensions", it) }
                item.quantity?.let { addProperty("quantity", it) }
                item.material?.let { addProperty("material", it) }
                item.supplier?.let { addProperty("supplier", it) }
                item.modelNumber?.let { addProperty("modelNumber", it) }
                item.orderDate?.let { addProperty("orderDate", it) }
                item.trackingNumber?.let { addProperty("trackingNumber", it) }
                item.orderUrl?.let { addProperty("orderUrl", it) }
                item.notes?.let { addProperty("notes", it) }
                addProperty("createdAt", item.createdAt)
                addProperty("createdByDevice", item.createdByDevice)
            })
        }
        atomicWrite(ownFile(jobFolderName), gson.toJson(array))
    }

    private fun atomicWrite(target: File, body: String) {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, "${target.name}.tmp-${System.nanoTime()}")
        temp.writeText(body)
        try {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun adminDir(jobFolderName: String) = File(baseDir, "$jobFolderName/.metadata/admin")
    private fun ownFile(jobFolderName: String) = File(adminDir(jobFolderName), "tablet_items_$tabletId.json")

    private fun JsonObject.getStr(key: String): String =
        runCatching { get(key)?.asString?.trim() }.getOrNull().orEmpty()
    private fun JsonObject.getNullStr(key: String): String? =
        runCatching { get(key)?.asString?.trim() }.getOrNull()?.takeIf { it.isNotBlank() }
}
