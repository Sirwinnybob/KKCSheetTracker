package com.kkc.sheettracker.data

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.kkc.sheettracker.data.models.*
import java.io.File
import java.time.Instant
import java.util.UUID

class SafetyRepository(private val basePath: String) {
    private val safetyDir get() = File(basePath, ".safety")
    private val concernsDir get() = File(safetyDir, "concerns")
    private val statusDir get() = File(safetyDir, "status")
    private val commentsDir get() = File(safetyDir, "comments")
    private val attachmentsDir get() = File(safetyDir, "attachments")
    private val gson = Gson()

    private inline fun <reified T> readJson(file: File): T? {
        if (!file.exists()) return null
        return runCatching { gson.fromJson(file.readText(), object : TypeToken<T>() {}.type) as T }.getOrNull()
    }

    private fun resolveStatus(concernId: String, statusFiles: List<File>): SafetyStatusRecord {
        return statusFiles
            .filter { it.name.startsWith("$concernId.") && it.name.endsWith(".json") && !it.name.contains(".sync-conflict-") }
            .mapNotNull { file -> readJson<SafetyStatusRecord>(file)?.let { record -> file.name to record } }
            .maxWithOrNull(
                compareBy<Pair<String, SafetyStatusRecord>> { (_, record) ->
                    runCatching { Instant.parse(record.at) }.getOrNull() ?: Instant.MIN
                }.thenBy { (_, record) -> record.at }.thenBy { (filename, _) -> filename }
            )
            ?.second
            ?: SafetyStatusRecord("OPEN")
    }

    fun getConcerns(): List<SafetyItem> {
        if (!concernsDir.exists()) return emptyList()
        val statusFiles = statusDir.listFiles()?.toList().orEmpty()
        return concernsDir.listFiles { f -> f.extension == "json" && !f.name.contains(".sync-conflict-") }
            ?.mapNotNull { file ->
                val stored = readJson<StoredSafetyConcern>(file) ?: return@mapNotNull null
                val statusRecord = resolveStatus(stored.id, statusFiles)
                SafetyItem(
                    id = stored.id,
                    author = stored.author,
                    title = stored.title,
                    category = stored.category,
                    description = stored.description,
                    status = statusRecord.status,
                    statusBy = statusRecord.by,
                    statusAt = statusRecord.at,
                    attachmentIds = stored.attachmentIds,
                    createdAt = stored.createdAt,
                    updatedAt = stored.updatedAt
                )
            }
            ?.sortedByDescending { it.createdAt }
            ?: emptyList()
    }

    fun getComments(concernId: String): List<SafetyComment> {
        val dir = File(commentsDir, concernId)
        if (!dir.exists()) return emptyList()
        return dir.listFiles { f -> f.extension == "json" && !f.name.contains(".sync-conflict-") }
            ?.mapNotNull { readJson<SafetyComment>(it) }
            ?.sortedWith(compareBy<SafetyComment> { runCatching { Instant.parse(it.createdAt) }.getOrNull() ?: Instant.MIN }.thenBy { it.createdAt })
            ?: emptyList()
    }

    fun addConcern(
        author: String,
        title: String,
        category: String,
        description: String,
        attachmentIds: List<String>,
        tabletId: String
    ): SafetyItem {
        concernsDir.mkdirs()
        statusDir.mkdirs()
        val id = UUID.randomUUID().toString()
        val now = Instant.now().toString()
        val stored = StoredSafetyConcern(
            id = id,
            author = author.trim(),
            title = title.trim(),
            category = category.trim(),
            description = description.trim(),
            attachmentIds = attachmentIds,
            createdAt = now,
            updatedAt = now
        )
        atomicWriteFile(File(concernsDir, "$id.json"), gson.toJson(stored))

        val statusRecord = SafetyStatusRecord("OPEN", author.trim(), now)
        atomicWriteFile(File(statusDir, "$id.$tabletId.json"), gson.toJson(statusRecord))

        return SafetyItem(
            id = id,
            author = stored.author,
            title = stored.title,
            category = stored.category,
            description = stored.description,
            status = "OPEN",
            statusBy = stored.author,
            statusAt = now,
            attachmentIds = attachmentIds,
            createdAt = now,
            updatedAt = now
        )
    }

    fun addComment(concernId: String, author: String, text: String): SafetyComment {
        val dir = File(commentsDir, concernId)
        dir.mkdirs()
        val id = UUID.randomUUID().toString()
        val comment = SafetyComment(id, author.trim(), text.trim(), Instant.now().toString())
        atomicWriteFile(File(dir, "$id.json"), gson.toJson(comment))
        return comment
    }

    fun setStatus(concernId: String, status: String, by: String, tabletId: String) {
        statusDir.mkdirs()
        val file = File(statusDir, "$concernId.$tabletId.json")
        atomicWriteFile(file, gson.toJson(SafetyStatusRecord(status, by, Instant.now().toString())))
    }

    fun saveAttachment(bytes: ByteArray, filename: String): String {
        attachmentsDir.mkdirs()
        val file = File(attachmentsDir, filename)
        file.writeBytes(bytes)
        return filename
    }

    fun getAttachmentFile(filename: String): File {
        return File(attachmentsDir, filename)
    }
}
