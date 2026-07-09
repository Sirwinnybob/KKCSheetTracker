package com.kkc.sheettracker.data

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.kkc.sheettracker.data.models.*
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

class SupplyRepository(private val basePath: String) {

    private val supplyDir get() = File(basePath, ".supply")
    private val itemsDir  get() = File(supplyDir, "items")
    private val statusDir get() = File(supplyDir, "status")
    private val commentsDir get() = File(supplyDir, "comments")
    private val gson = Gson()

    // Guards the categories.json whole-list read-modify-write (createCategory) against
    // same-process races. See METADATA_AUDIT.md H-07 — this does not protect against
    // cross-process/cross-host races (Hours Tracker backend, another tablet); that is a
    // larger overlay-pattern redesign tracked separately (R-03), not attempted here.
    private val categoryWriteLock = Any()

    // CROSS-PROGRAM: see METADATA_AUDIT.md H-07. `.supply/items/<id>.json` and
    // `.supply/categories.json` are also written by the Hours Tracker backend
    // (backend/routes/supply_store.py, atomic+locked). Every tablet write to those files must
    // go through this helper (temp file + Files.move ATOMIC_MOVE) so a concurrent reader —
    // the backend, a peer tablet via Syncthing, or this app's own getItems()/getCategories() —
    // never observes a truncated/partial JSON file. Mirrors HardwoodsProgressStore.atomicWrite
    // / ProgressStore.atomicWrite (see METADATA_AUDIT.md H-02).
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

    // ── Read helpers ──────────────────────────────────────────────────────────

    private inline fun <reified T> readJson(file: File): T? {
        if (!file.exists()) return null
        return runCatching { gson.fromJson(file.readText(), object : TypeToken<T>() {}.type) as T }.getOrNull()
    }

    // ── Status resolution ─────────────────────────────────────────────────────

    private fun resolveStatus(itemId: String): SupplyStatusRecord {
        val dir = statusDir
        if (!dir.exists()) return SupplyStatusRecord("IN STOCK")
        return resolveStatusFrom(itemId, dir.listFiles()?.toList().orEmpty())
    }

    // Resolve a single item's status from an already-listed set of status files.
    // Lets getItems() list the status directory once instead of once per item
    // (was O(items) directory listings — an N+1 on the networked supply drive).
    private fun resolveStatusFrom(itemId: String, statusFiles: List<File>): SupplyStatusRecord {
        return statusFiles
            .filter { it.name.startsWith("$itemId.") && it.name.endsWith(".json") && !it.name.contains(".sync-conflict-") }
            .mapNotNull { readJson<SupplyStatusRecord>(it) }
            .maxByOrNull { it.at }
            ?: SupplyStatusRecord("IN STOCK")
    }

    private fun StoredSupplyItem.resolve(): SupplyItem = resolveWith(resolveStatus(id))

    private fun StoredSupplyItem.resolveWith(s: SupplyStatusRecord): SupplyItem {
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
        // List the status directory once and reuse it across all items, instead of
        // re-listing it inside resolve()/resolveStatus() for every item.
        val statusFiles = statusDir.listFiles()?.toList().orEmpty()
        return itemsDir.listFiles { f -> f.extension == "json" && !f.name.contains(".sync-conflict-") }
            ?.mapNotNull { file ->
                val stored = readJson<StoredSupplyItem>(file) ?: return@mapNotNull null
                stored.resolveWith(resolveStatusFrom(stored.id, statusFiles))
            }
            ?: emptyList()
    }

    fun getItem(itemId: String): SupplyItem? =
        readJson<StoredSupplyItem>(File(itemsDir, "$itemId.json"))?.resolve()

    fun getComments(itemId: String): List<SupplyComment> {
        val dir = File(commentsDir, itemId)
        if (!dir.exists()) return emptyList()
        return dir.listFiles { f -> f.extension == "json" && !f.name.contains(".sync-conflict-") }
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

    fun createCategory(name: String): SupplyCategory {
        supplyDir.mkdirs()
        // CROSS-PROGRAM: see METADATA_AUDIT.md H-07. categories.json is a whole-list RMW also
        // performed by the Hours Tracker backend (atomic+locked there). categoryWriteLock only
        // guards against same-process races (e.g. two callers on this tablet); it does not
        // prevent a concurrent writer in another process/host from clobbering this update. The
        // write itself is atomic (temp+rename) so readers never see a torn file either way.
        synchronized(categoryWriteLock) {
            val existing = readJson<List<SupplyCategory>>(File(supplyDir, "categories.json")) ?: emptyList()
            val cat = SupplyCategory(UUID.randomUUID().toString(), name.trim(), existing.size)
            val updated = existing + cat
            atomicWrite(File(supplyDir, "categories.json"), gson.toJson(updated))
            return cat
        }
    }

    fun createItem(
        categoryId: String, name: String, notes: String?,
        fields: Map<String, String>,
        status: String = "IN STOCK",
        tabletId: String = "tablet"
    ): SupplyItem {
        itemsDir.mkdirs()
        val id = UUID.randomUUID().toString()
        val now = java.time.Instant.now().toString()
        val stored = StoredSupplyItem(
            id = id, categoryId = categoryId, name = name,
            notes = notes?.takeIf { it.isNotBlank() },
            fields = fields, customFields = emptyMap(),
            attachmentIds = emptyList(), createdAt = now, updatedAt = now
        )
        // CROSS-PROGRAM: see METADATA_AUDIT.md H-07 — items/<id>.json is also written by the
        // Hours Tracker backend (atomic+locked). Atomic write here prevents a concurrent reader
        // (backend, peer tablet, or this app's own getItems()) from observing a torn file.
        atomicWrite(File(itemsDir, "$id.json"), gson.toJson(stored))
        if (status != "IN STOCK") {
            setStatus(id, status, "", tabletId)
        }
        return stored.resolve()
    }

    fun updateItem(itemId: String, name: String, categoryId: String, notes: String?, fields: Map<String, String>): SupplyItem? {
        val file = File(itemsDir, "$itemId.json")
        val existing = readJson<StoredSupplyItem>(file) ?: return null
        val updated = existing.copy(
            name = name, categoryId = categoryId,
            notes = notes?.takeIf { it.isNotBlank() },
            fields = fields, updatedAt = java.time.Instant.now().toString()
        )
        // CROSS-PROGRAM: see METADATA_AUDIT.md H-07 — items/<id>.json is also written by the
        // Hours Tracker backend (atomic+locked). Atomic write here prevents a concurrent reader
        // (backend, peer tablet, or this app's own getItems()) from observing a torn file.
        atomicWrite(file, gson.toJson(updated))
        return updated.resolve()
    }

    fun addAttachment(itemId: String, attachment: SupplyAttachment, sourceFile: File): SupplyItem? {
        val itemFile = File(itemsDir, "$itemId.json")
        val existing = readJson<StoredSupplyItem>(itemFile) ?: return null
        val destDir = File(supplyDir, "attachments/$itemId")
        destDir.mkdirs()
        sourceFile.copyTo(File(destDir, attachment.storedName), overwrite = true)
        val updated = existing.copy(
            attachmentIds = existing.attachmentIds + attachment,
            updatedAt = java.time.Instant.now().toString()
        )
        // CROSS-PROGRAM: see METADATA_AUDIT.md H-07 — items/<id>.json is also written by the
        // Hours Tracker backend (atomic+locked). Atomic write here prevents a concurrent reader
        // (backend, peer tablet, or this app's own getItems()) from observing a torn file. Note:
        // the attachment binary copy just above is plain (overwrite = true) — that is tracked
        // separately as L-04 and intentionally out of scope for H-07.
        atomicWrite(itemFile, gson.toJson(updated))
        return updated.resolve()
    }

    fun getAttachmentFile(itemId: String, storedName: String): File =
        File(supplyDir, "attachments/$itemId/$storedName")

    // Legacy path helper kept for any callers that use absolutePath string
    fun attachmentPath(itemId: String, storedName: String): String =
        getAttachmentFile(itemId, storedName).absolutePath
}
