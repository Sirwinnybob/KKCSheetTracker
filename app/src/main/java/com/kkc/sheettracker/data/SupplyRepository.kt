package com.kkc.sheettracker.data

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.kkc.sheettracker.data.models.*
import java.io.File
import java.util.UUID

class SupplyRepository(private val basePath: String) {

    private val supplyDir get() = File(basePath, ".supply")
    private val itemsDir  get() = File(supplyDir, "items")
    private val statusDir get() = File(supplyDir, "status")
    private val commentsDir get() = File(supplyDir, "comments")
    private val gson = Gson()

    // ── Read helpers ──────────────────────────────────────────────────────────

    private inline fun <reified T> readJson(file: File): T? {
        if (!file.exists()) return null
        return runCatching { gson.fromJson(file.readText(), object : TypeToken<T>() {}.type) as T }.getOrNull()
    }

    // ── Status resolution ─────────────────────────────────────────────────────

    private fun resolveStatus(itemId: String): SupplyStatusRecord {
        val dir = statusDir
        if (!dir.exists()) return SupplyStatusRecord("IN STOCK")
        return dir.listFiles { f -> f.name.startsWith("$itemId.") && f.name.endsWith(".json") }
            ?.mapNotNull { readJson<SupplyStatusRecord>(it) }
            ?.maxByOrNull { it.at }
            ?: SupplyStatusRecord("IN STOCK")
    }

    private fun StoredSupplyItem.resolve(): SupplyItem {
        val s = resolveStatus(id)
        return SupplyItem(
            id = id, categoryId = categoryId, name = name,
            status = s.status, statusBy = s.by, statusAt = s.at,
            notes = notes, fields = fields, customFields = customFields,
            attachmentIds = attachmentIds, createdAt = createdAt, updatedAt = updatedAt
        )
    }

    // ── Public API ────────────────────────────────────────────────────────────

    fun getCategories(): List<SupplyCategory> =
        readJson<List<SupplyCategory>>(File(supplyDir, "categories.json")) ?: emptyList()

    fun getSchema(): List<SupplySchemaField> =
        readJson<List<SupplySchemaField>>(File(supplyDir, "schema.json")) ?: emptyList()

    fun getItems(): List<SupplyItem> {
        if (!itemsDir.exists()) return emptyList()
        return itemsDir.listFiles { f -> f.extension == "json" }
            ?.mapNotNull { readJson<StoredSupplyItem>(it)?.resolve() }
            ?: emptyList()
    }

    fun getItem(itemId: String): SupplyItem? =
        readJson<StoredSupplyItem>(File(itemsDir, "$itemId.json"))?.resolve()

    fun getComments(itemId: String): List<SupplyComment> {
        val dir = File(commentsDir, itemId)
        if (!dir.exists()) return emptyList()
        return dir.listFiles { f -> f.extension == "json" }
            ?.mapNotNull { readJson<SupplyComment>(it) }
            ?.sortedBy { it.createdAt }
            ?: emptyList()
    }

    // ── Tablet writes ─────────────────────────────────────────────────────────

    fun setStatus(itemId: String, status: String, by: String, tabletId: String) {
        statusDir.mkdirs()
        val file = File(statusDir, "$itemId.$tabletId.json")
        file.writeText(gson.toJson(SupplyStatusRecord(status, by, java.time.Instant.now().toString())))
    }

    fun addComment(itemId: String, author: String, text: String, tabletId: String): SupplyComment {
        val dir = File(commentsDir, itemId)
        dir.mkdirs()
        val id = UUID.randomUUID().toString()
        val comment = SupplyComment(id, author, text, java.time.Instant.now().toString())
        File(dir, "$id.json").writeText(gson.toJson(comment))
        return comment
    }

    // Attachment file path for display (local Syncthing-synced storage)
    fun attachmentPath(itemId: String, storedName: String): String =
        File(supplyDir, "attachments/$itemId/$storedName").absolutePath
}
