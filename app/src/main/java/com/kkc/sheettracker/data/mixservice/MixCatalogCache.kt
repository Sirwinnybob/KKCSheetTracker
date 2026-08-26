package com.kkc.sheettracker.data.mixservice

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

data class CachedMixCatalogSnapshot(
    val snapshot: MixCatalogSnapshot,
    val fetchedAtMillis: Long
)

/** Durable, per-job/material catalog snapshots. The enclosing cache directory is injected. */
class MixCatalogCache(
    private val root: File,
    private val nowMillis: () -> Long = System::currentTimeMillis
) {
    private val gson = Gson()
    private val frontCache = mutableMapOf<Pair<String, String>, CachedMixCatalogSnapshot>()

    @Synchronized
    fun read(job: String, material: String): MixCatalogSnapshot? =
        readCached(job, material)?.snapshot

    @Synchronized
    fun readCached(job: String, material: String): CachedMixCatalogSnapshot? {
        if (job.isBlank() || material.isBlank()) return null
        val key = job to material
        frontCache[key]?.let { return it }
        val file = cacheFile(job, material)
        if (!file.isFile) return null
        val cached = parseCachedSnapshot(file.readText(StandardCharsets.UTF_8)) ?: return null
        if (cached.snapshot.job != job || cached.snapshot.material != material) return null
        frontCache[key] = cached
        return cached
    }

    /** Revision is a service equality token: only an identical revision is a no-op. */
    @Synchronized
    fun write(snapshot: MixCatalogSnapshot): Boolean {
        if (!isValidSnapshot(snapshot)) return false
        val existing = readCached(snapshot.job, snapshot.material)
        if (existing?.snapshot?.revision == snapshot.revision) return false

        if (!root.isDirectory && !root.mkdirs()) return false
        val destination = cacheFile(snapshot.job, snapshot.material)
        val temporary = File.createTempFile(destination.name, ".tmp", root)
        val cached = CachedMixCatalogSnapshot(snapshot, nowMillis())
        try {
            FileOutputStream(temporary).use { output ->
                output.write(gson.toJson(cached).toByteArray(StandardCharsets.UTF_8))
                output.fd.sync()
            }
            try {
                Files.move(
                    temporary.toPath(),
                    destination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            frontCache[snapshot.job to snapshot.material] = cached
            return true
        } finally {
            temporary.delete()
        }
    }

    private fun cacheFile(job: String, material: String): File = File(root, "${key(job, material)}.json")

    private fun key(job: String, material: String): String = MessageDigest.getInstance("SHA-256")
        .digest("$job\u0000$material".toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun parseCachedSnapshot(content: String): CachedMixCatalogSnapshot? {
        val rootObject = runCatching { JsonParser.parseString(content).asJsonObject }.getOrNull() ?: return null
        val fetchedAtMillis = rootObject.longValue("fetchedAtMillis") ?: return null
        val snapshotObject = rootObject.objectValue("snapshot") ?: return null
        val job = snapshotObject.nonBlankString("job") ?: return null
        val material = snapshotObject.nonBlankString("material") ?: return null
        val revision = snapshotObject.longValue("revision") ?: return null
        val entriesElement = snapshotObject["entries"]
        if (entriesElement == null || !entriesElement.isJsonArray) return null
        val entries = entriesElement.asJsonArray.map { parseEntry(it) ?: return null }
        val snapshot = MixCatalogSnapshot(job, material, revision, entries)
        return CachedMixCatalogSnapshot(snapshot, fetchedAtMillis).takeIf { isValidSnapshot(it.snapshot) }
    }

    private fun parseEntry(element: JsonElement): MixCatalogEntry? {
        if (!element.isJsonObject) return null
        val entry = element.asJsonObject
        val name = entry.nonBlankString("name") ?: return null
        val mixFilename = entry.nonBlankString("mixFilename") ?: return null
        val lifecycle = when (entry.nonBlankString("lifecycle")) {
            "active" -> MixLifecycle.ACTIVE
            "history" -> MixLifecycle.HISTORY
            "external" -> MixLifecycle.EXTERNAL
            else -> return null
        }
        val programsElement = entry["programs"]
        if (programsElement == null || !programsElement.isJsonArray) return null
        val programs = programsElement.asJsonArray.map { program ->
            if (!program.isJsonPrimitive || !program.asJsonPrimitive.isString || program.asString.isBlank()) return null
            program.asString
        }
        if (!entry.isOptionalString("status") ||
            !entry.isOptionalString("createdAt") ||
            !entry.isOptionalString("updatedAt") ||
            !entry.isOptionalString("lastCompiledAt") ||
            !entry.isOptionalString("lastCompileError") ||
            !entry.isOptionalBoolean("lastCompileOk")
        ) return null
        return MixCatalogEntry(
            name = name,
            mixFilename = mixFilename,
            lifecycle = lifecycle,
            programs = programs,
            status = entry.optionalString("status"),
            createdAt = entry.optionalString("createdAt"),
            updatedAt = entry.optionalString("updatedAt"),
            lastCompiledAt = entry.optionalString("lastCompiledAt"),
            lastCompileOk = entry.optionalBoolean("lastCompileOk"),
            lastCompileError = entry.optionalString("lastCompileError")
        )
    }

    private fun isValidSnapshot(snapshot: MixCatalogSnapshot): Boolean =
        snapshot.job.isNotBlank() &&
            snapshot.material.isNotBlank() &&
            snapshot.entries.all {
                it.name.isNotBlank() &&
                    it.mixFilename.isNotBlank() &&
                    it.programs.all(String::isNotBlank)
            }

    private fun JsonObject.longValue(name: String): Long? {
        val value = get(name) ?: return null
        if (!value.isJsonPrimitive || !value.asJsonPrimitive.isNumber) return null
        return value.asString.toLongOrNull()
    }

    private fun JsonObject.objectValue(name: String): JsonObject? =
        get(name)?.takeIf(JsonElement::isJsonObject)?.asJsonObject

    private fun JsonObject.nonBlankString(name: String): String? =
        get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString?.takeIf(String::isNotBlank)

    private fun JsonObject.optionalString(name: String): String? =
        get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    private fun JsonObject.optionalBoolean(name: String): Boolean? =
        get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean

    private fun JsonObject.isOptionalString(name: String): Boolean {
        val value = get(name) ?: return true
        return value.isJsonNull || (value.isJsonPrimitive && value.asJsonPrimitive.isString)
    }

    private fun JsonObject.isOptionalBoolean(name: String): Boolean {
        val value = get(name) ?: return true
        return value.isJsonNull || (value.isJsonPrimitive && value.asJsonPrimitive.isBoolean)
    }

    companion object {
        fun inAppFiles(context: Context): MixCatalogCache =
            MixCatalogCache(File(context.filesDir, "state/mix_catalog"))
    }
}

/** Coordinates a persistent stale-while-revalidate catalog without owning a UI lifecycle. */
class MixCatalogRepository(
    private val client: MixServiceClient,
    private val cache: MixCatalogCache
) {
    fun cached(job: String, material: String): MixCatalogSnapshot? = cache.read(job, material)

    fun cachedAndRefresh(
        job: String,
        material: String,
        scope: CoroutineScope,
        onRefreshed: (MixCatalogFetchResult) -> Unit = {}
    ): MixCatalogSnapshot? {
        val cached = cached(job, material)
        scope.launch { onRefreshed(refresh(job, material)) }
        return cached
    }

    suspend fun refresh(job: String, material: String): MixCatalogFetchResult {
        val result = client.getMixCatalog(job, material)
        if (result is MixCatalogFetchResult.Success) cache.write(result.snapshot)
        return result
    }

    suspend fun replaceMix(
        job: String,
        material: String,
        name: String,
        programs: List<String>,
        expectedRevision: Long
    ): MixCatalogMutationResult = client.replaceMix(job, material, name, programs, expectedRevision)
        .also { result -> result.snapshotOrNull()?.let(cache::write) }

    suspend fun deleteExternalMix(
        job: String,
        material: String,
        filename: String,
        expectedRevision: Long
    ): MixCatalogMutationResult = client.deleteExternalMix(job, material, filename, expectedRevision)
        .also { result -> result.snapshotOrNull()?.let(cache::write) }

    private fun MixCatalogMutationResult.snapshotOrNull(): MixCatalogSnapshot? = when (this) {
        is MixCatalogMutationResult.Success -> snapshot
        is MixCatalogMutationResult.SyncFailed -> snapshot
        else -> null
    }
}
