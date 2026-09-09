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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Strict JSON boundary for catalog snapshots. Gson's default adapters coerce malformed data. */
internal object MixCatalogJson {
    fun parseFetchSnapshot(content: String, job: String, material: String): MixCatalogSnapshot? =
        runCatching {
            parseFetchSnapshot(JsonParser.parseString(content), job, material)
        }.getOrNull()

    private fun parseFetchSnapshot(
        element: JsonElement,
        job: String,
        material: String,
    ): MixCatalogSnapshot? {
        val envelope = element.takeIf(JsonElement::isJsonObject)?.asJsonObject
            ?: return null
        if (envelope.booleanValue("ok") != true) return null
        return parseSnapshotObject(envelope, job, material)
    }

    /** Parses the {ok,catalog:{revision,entries}} envelope returned by catalog mutations. */
    fun parseMutationSnapshot(content: String, job: String, material: String): MixCatalogSnapshot? =
        runCatching {
            parseMutationSnapshot(JsonParser.parseString(content), job, material)
        }.getOrNull()

    /**
     * A completed async catalog operation's [MixServiceOperation.result] arrives already
     * Gson-deserialized into generic maps/lists rather than raw JSON text. Round-trip it back
     * through Gson so the existing string-based envelope parser can be reused unchanged.
     */
    fun parseMutationResult(result: Any?, job: String, material: String): MixCatalogSnapshot? =
        runCatching { parseMutationSnapshot(Gson().toJson(result), job, material) }.getOrNull()

    /** Parses a nested mutation envelope, used by both HTTP 200 and sync-failure responses. */
    fun parseMutationSnapshot(
        element: JsonElement?,
        job: String,
        material: String,
    ): MixCatalogSnapshot? {
        val envelope = element?.takeIf(JsonElement::isJsonObject)?.asJsonObject
            ?: return null
        if (envelope.booleanValue("ok") != true) return null
        val catalog = envelope.objectValue("catalog") ?: return null
        return parseSnapshotObject(catalog, job, material)
    }

    fun parseSnapshotObject(
        objectValue: JsonObject,
        job: String,
        material: String,
    ): MixCatalogSnapshot? {
        if (job.isBlank() || material.isBlank()) return null
        val revision = objectValue.longValue("revision") ?: return null
        val entriesElement = objectValue["entries"]
        if (entriesElement == null || !entriesElement.isJsonArray) return null
        val entries = entriesElement.asJsonArray.map { parseEntry(it) ?: return null }
        return MixCatalogSnapshot(job, material, revision, entries)
            .takeIf(::isValidSnapshot)
    }

    fun parseEntry(element: JsonElement): MixCatalogEntry? {
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
            if (!program.isJsonPrimitive || !program.asJsonPrimitive.isString || program.asString.isBlank()) {
                return null
            }
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

    fun isValidSnapshot(snapshot: MixCatalogSnapshot?): Boolean = try {
        if (
            snapshot == null ||
            snapshot.job.isBlank() ||
            snapshot.material.isBlank() ||
            snapshot.revision <= 0L
        ) return false
        snapshot.entries.all { entry ->
            entry.name.isNotBlank() &&
                entry.mixFilename.isNotBlank() &&
                (entry.lifecycle === MixLifecycle.ACTIVE ||
                    entry.lifecycle === MixLifecycle.HISTORY ||
                    entry.lifecycle === MixLifecycle.EXTERNAL) &&
                entry.programs.all(String::isNotBlank)
        }
    } catch (_: RuntimeException) {
        false
    }

    fun JsonObject.longValue(name: String): Long? {
        val value = get(name) ?: return null
        if (!value.isJsonPrimitive || !value.asJsonPrimitive.isNumber) return null
        return value.asString.toLongOrNull()?.takeIf { it > 0L }
    }

    private fun JsonObject.objectValue(name: String): JsonObject? =
        get(name)?.takeIf(JsonElement::isJsonObject)?.asJsonObject

    fun JsonObject.nonBlankString(name: String): String? =
        get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
            ?.asString
            ?.takeIf(String::isNotBlank)

    private fun JsonObject.optionalString(name: String): String? =
        get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    private fun JsonObject.optionalBoolean(name: String): Boolean? =
        get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean

    private fun JsonObject.booleanValue(name: String): Boolean? =
        get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean

    private fun JsonObject.isOptionalString(name: String): Boolean {
        val value = get(name) ?: return true
        return value.isJsonNull || (value.isJsonPrimitive && value.asJsonPrimitive.isString)
    }

    private fun JsonObject.isOptionalBoolean(name: String): Boolean {
        val value = get(name) ?: return true
        return value.isJsonNull || (value.isJsonPrimitive && value.asJsonPrimitive.isBoolean)
    }
}

data class CachedMixCatalogSnapshot(
    val snapshot: MixCatalogSnapshot,
    val fetchedAtMillis: Long
)

/** Explicitly exposes snapshots visible in memory that still need disk persistence. */
data class MixCatalogDurabilityState(
    val pending: List<MixCatalogSnapshot> = emptyList(),
)

/** Boundary for publishing a validated mutation snapshot to the process-shared catalog cache. */
fun interface MixCatalogPublisher {
    fun publish(snapshot: MixCatalogSnapshot): Boolean
}

internal enum class MixCatalogCacheWriteResult {
    PUBLISHED,
    UNCHANGED,
    SUPERSEDED,
    FAILED,
}

/** Durable, per-job/material catalog snapshots. The enclosing cache directory is injected. */
class MixCatalogCache(
    private val root: File,
    private val nowMillis: () -> Long = System::currentTimeMillis
) : MixCatalogPublisher {
    private val gson = Gson()
    private val frontCache = mutableMapOf<Pair<String, String>, CachedMixCatalogSnapshot>()
    private val pendingWrites = mutableMapOf<Pair<String, String>, CachedMixCatalogSnapshot>()
    private val _durabilityState = MutableStateFlow(MixCatalogDurabilityState())
    val durabilityState: StateFlow<MixCatalogDurabilityState> = _durabilityState.asStateFlow()

    @Synchronized
    fun read(job: String, material: String): MixCatalogSnapshot? =
        readCached(job, material)?.snapshot

    @Synchronized
    fun readCached(job: String, material: String): CachedMixCatalogSnapshot? {
        if (job.isBlank() || material.isBlank()) return null
        val key = job to material
        frontCache[key]?.let { return it }
        val cached = readBacking(job, material) ?: return null
        frontCache[key] = cached
        return cached
    }

    /** Revision is a service equality token: only an identical revision is a no-op. */
    @Synchronized
    fun write(snapshot: MixCatalogSnapshot?): Boolean {
        return writeInternal(snapshot) == MixCatalogCacheWriteResult.PUBLISHED
    }

    /** Retries disk publication of an acknowledged snapshot without resubmitting its mutation. */
    @Synchronized
    fun retryPendingWrites(): Boolean {
        if (pendingWrites.isEmpty()) return true
        val pending = pendingWrites.values.toList()
        var allPublished = true
        pending.forEach { cached ->
            if (writeInternal(cached.snapshot, forceWrite = true) == MixCatalogCacheWriteResult.FAILED) {
                allPublished = false
            }
        }
        return allPublished
    }

    /** Publishes only when the cache still contains the state observed before a refresh began. */
    @Synchronized
    internal fun writeIfUnchanged(
        snapshot: MixCatalogSnapshot?,
        expected: CachedMixCatalogSnapshot?,
    ): MixCatalogCacheWriteResult {
        return try {
            if (!MixCatalogJson.isValidSnapshot(snapshot)) return MixCatalogCacheWriteResult.FAILED
            snapshot ?: return MixCatalogCacheWriteResult.FAILED
            val key = snapshot.job to snapshot.material
            val pending = pendingWrites[key]
            if (pending != null && snapshot.revision <= pending.snapshot.revision) {
                // A server acknowledgement already visible to the app must not be replaced by
                // an older/equal refresh while its durable file is waiting for recovery.
                return MixCatalogCacheWriteResult.SUPERSEDED
            }
            val existing = readCached(snapshot.job, snapshot.material)
            if (existing?.snapshot?.revision == snapshot.revision && key !in pendingWrites) {
                return MixCatalogCacheWriteResult.UNCHANGED
            }
            if (existing != expected) return MixCatalogCacheWriteResult.SUPERSEDED
            writeInternal(snapshot)
        } catch (_: Throwable) {
            MixCatalogCacheWriteResult.FAILED
        }
    }

    private fun writeInternal(
        snapshot: MixCatalogSnapshot?,
        forceWrite: Boolean = false,
    ): MixCatalogCacheWriteResult {
        var temporary: File? = null
        var pending: CachedMixCatalogSnapshot? = null
        try {
            if (!MixCatalogJson.isValidSnapshot(snapshot)) return MixCatalogCacheWriteResult.FAILED
            snapshot ?: return MixCatalogCacheWriteResult.FAILED
            val cached = CachedMixCatalogSnapshot(snapshot, nowMillis())
            pending = cached
            val key = snapshot.job to snapshot.material
            val acknowledged = pendingWrites[key]
            if (!forceWrite && acknowledged != null && snapshot.revision <= acknowledged.snapshot.revision) {
                return MixCatalogCacheWriteResult.UNCHANGED
            }
            if (forceWrite && acknowledged != null && snapshot.revision < acknowledged.snapshot.revision) {
                // A retry can be holding an older list entry while a newer acknowledgement for
                // the same key is already pending. Never let the stale retry consume that entry.
                return MixCatalogCacheWriteResult.UNCHANGED
            }
            val existing = readCached(snapshot.job, snapshot.material)
            if (!forceWrite &&
                existing?.snapshot?.revision == snapshot.revision &&
                key !in pendingWrites
            ) {
                return MixCatalogCacheWriteResult.UNCHANGED
            }
            if (!root.isDirectory && !root.mkdirs()) {
                rememberPending(cached)
                return MixCatalogCacheWriteResult.FAILED
            }
            val destination = cacheFile(snapshot.job, snapshot.material)
            temporary = File.createTempFile(destination.name, ".tmp", root)
            FileOutputStream(temporary).use { output ->
                output.write(gson.toJson(cached).toByteArray(StandardCharsets.UTF_8))
                output.fd.sync()
            }
            if (forceWrite) {
                val durable = readBacking(snapshot.job, snapshot.material)
                if (durable != null && durable.snapshot.revision >= snapshot.revision) {
                    val currentPending = pendingWrites[key]
                    if (currentPending == null || currentPending.snapshot.revision <= durable.snapshot.revision) {
                        pendingWrites.remove(key)
                        frontCache[key] = durable
                        publishDurabilityState()
                    }
                    // Another cache instance has already persisted this revision (or newer).
                    // The acknowledged snapshot is therefore recovered without replacing the
                    // durable winner; retain any still-newer pending acknowledgement above.
                    return MixCatalogCacheWriteResult.UNCHANGED
                }
            }
            try {
                Files.move(
                    temporary!!.toPath(),
                    destination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary!!.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            frontCache[key] = cached
            pendingWrites.remove(key)
            publishDurabilityState()
            return MixCatalogCacheWriteResult.PUBLISHED
        } catch (_: Throwable) {
            pending?.let(::rememberPending)
            return MixCatalogCacheWriteResult.FAILED
        } finally {
            try {
                temporary?.delete()
            } catch (_: Throwable) {
                // Cache cleanup is best effort; persistence failures must stay contained.
            }
        }
    }

    private fun rememberPending(cached: CachedMixCatalogSnapshot) {
        val key = cached.snapshot.job to cached.snapshot.material
        // The server mutation has already been acknowledged. Make that result immediately
        // observable even if this device cannot currently persist the cache file.
        frontCache[key] = cached
        pendingWrites[key] = cached
        publishDurabilityState()
    }

    private fun publishDurabilityState() {
        _durabilityState.value = MixCatalogDurabilityState(
            pending = pendingWrites.values.map { it.snapshot },
        )
    }

    /** Publisher semantics report durable success for both a write and an existing equal entry. */
    override fun publish(snapshot: MixCatalogSnapshot): Boolean = synchronized(this) {
        if (!MixCatalogJson.isValidSnapshot(snapshot)) return@synchronized false
        val key = snapshot.job to snapshot.material
        val current = readCached(snapshot.job, snapshot.material)
        if (current != null && current.snapshot.revision > snapshot.revision) {
            // A completed mutation can be restored after a newer refresh has already won. The
            // newer cache is the durable result; never regress it while acknowledging recovery.
            // Keep any in-memory pending write: the newer visible snapshot may itself still need
            // persistence and must remain recoverable across the next coordinator restore.
            return@synchronized true
        }
        val pending = pendingWrites[key]
        val retryPending = pending != null && (
            snapshot.revision > pending.snapshot.revision || snapshot == pending.snapshot
        )
        writeInternal(snapshot, forceWrite = retryPending) != MixCatalogCacheWriteResult.FAILED
    }

    private fun cacheFile(job: String, material: String): File = File(root, "${key(job, material)}.json")

    private fun readBacking(job: String, material: String): CachedMixCatalogSnapshot? {
        val file = cacheFile(job, material)
        if (!file.isFile) return null
        val cached = runCatching {
            parseCachedSnapshot(file.readText(StandardCharsets.UTF_8))
        }.getOrNull() ?: return null
        if (cached.snapshot.job != job || cached.snapshot.material != material) return null
        return cached
    }

    private fun key(job: String, material: String): String = MessageDigest.getInstance("SHA-256")
        .digest("$job\u0000$material".toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun parseCachedSnapshot(content: String): CachedMixCatalogSnapshot? {
        val rootObject = runCatching { JsonParser.parseString(content).asJsonObject }.getOrNull() ?: return null
        val fetchedAtMillis = MixCatalogJson.run { rootObject.longValue("fetchedAtMillis") } ?: return null
        val snapshotObject = rootObject.objectValue("snapshot") ?: return null
        val job = MixCatalogJson.run { snapshotObject.nonBlankString("job") } ?: return null
        val material = MixCatalogJson.run { snapshotObject.nonBlankString("material") } ?: return null
        val snapshot = MixCatalogJson.parseSnapshotObject(snapshotObject, job, material) ?: return null
        return CachedMixCatalogSnapshot(snapshot, fetchedAtMillis)
    }

    private fun JsonObject.objectValue(name: String): JsonObject? =
        get(name)?.takeIf(JsonElement::isJsonObject)?.asJsonObject

    companion object {
        fun inAppFiles(context: Context): MixCatalogCache =
            MixCatalogCache(File(context.filesDir, "state/mix_catalog"))
    }
}

/** Coordinates a persistent stale-while-revalidate catalog without owning a UI lifecycle. */
class MixCatalogRepository(
    private val client: MixCatalogReader,
    private val cache: MixCatalogCache
) {
    private data class CacheKey(val job: String, val material: String)

    private data class RefreshAttempt(
        val key: CacheKey,
        val generation: Long,
        val base: CachedMixCatalogSnapshot?,
    )

    private val refreshLock = Any()
    private val latestGeneration = mutableMapOf<CacheKey, Long>()

    fun cached(job: String, material: String): MixCatalogSnapshot? = cache.read(job, material)

    fun cachedAndRefresh(
        job: String,
        material: String,
        scope: CoroutineScope,
        onRefreshed: (MixCatalogFetchResult) -> Unit = {}
    ): MixCatalogSnapshot? {
        val cached = cached(job, material)
        val attempt = beginRefresh(job, material)
        scope.launch { onRefreshed(refresh(attempt)) }
        return cached
    }

    suspend fun refresh(job: String, material: String): MixCatalogFetchResult =
        refresh(beginRefresh(job, material))

    private suspend fun refresh(attempt: RefreshAttempt): MixCatalogFetchResult {
        val result = client.getMixCatalog(attempt.key.job, attempt.key.material)
        if (result !is MixCatalogFetchResult.Success) return result
        if (!MixCatalogJson.isValidSnapshot(result.snapshot)) return MixCatalogFetchResult.NetworkError

        val publication = synchronized(refreshLock) {
            if (latestGeneration[attempt.key] != attempt.generation) {
                MixCatalogCacheWriteResult.SUPERSEDED
            } else {
                cache.writeIfUnchanged(result.snapshot, attempt.base)
            }
        }
        return when (publication) {
            MixCatalogCacheWriteResult.PUBLISHED,
            MixCatalogCacheWriteResult.UNCHANGED -> result
            MixCatalogCacheWriteResult.SUPERSEDED,
            MixCatalogCacheWriteResult.FAILED -> MixCatalogFetchResult.NetworkError
        }
    }

    private fun beginRefresh(job: String, material: String): RefreshAttempt {
        val key = CacheKey(job, material)
        synchronized(refreshLock) {
            val generation = (latestGeneration[key] ?: 0L) + 1L
            latestGeneration[key] = generation
            return RefreshAttempt(key, generation, cache.readCached(job, material))
        }
    }
}
