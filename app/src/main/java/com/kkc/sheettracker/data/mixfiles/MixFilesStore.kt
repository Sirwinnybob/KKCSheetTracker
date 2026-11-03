package com.kkc.sheettracker.data.mixfiles

import com.google.gson.GsonBuilder
import com.kkc.sheettracker.data.cnc.CncMachine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale

data class MaterialKey(val machineId: String, val job: String, val material: String)

sealed interface CatalogState {
    data object Loading : CatalogState
    data class Live(val catalog: MixFilesCatalog) : CatalogState
    data class Offline(val cached: MixFilesCatalog?) : CatalogState
    data class Outdated(val cached: MixFilesCatalog?) : CatalogState
    data object NoFolder : CatalogState
}

/** One shared snapshot and serialized reader per material. Supply a background scope for disk IO. */
class MixFilesStore(
    private val key: MaterialKey,
    private val api: MixFilesApi,
    private val cache: MixFilesCatalogCache,
    private val scope: CoroutineScope,
    private val pollMs: Long = 3_000L,
) {
    private val lock = Any()
    private val requests = Mutex()
    private val mutableState = MutableStateFlow<CatalogState>(CatalogState.Loading)
    val state: StateFlow<CatalogState> = mutableState.asStateFlow()
    private var catalog: MixFilesCatalog? = null
    private var observers = 0
    private var generation = 0L
    // A successful write may publish while a read is suspended. Its result must win.
    private var version = 0L
    private var consecutiveOffline = 0
    private var pollJob: Job? = null

    init { require(pollMs > 0) }

    fun acquire(): AutoCloseable {
        synchronized(lock) {
            if (observers++ == 0) {
                val expected = ++generation
                mutableState.value = CatalogState.Offline(catalog)
                pollJob = scope.launch { poll(expected) }
            }
        }
        var closed = false
        return AutoCloseable {
            synchronized(lock) {
                if (!closed) {
                    closed = true
                    if (--observers == 0) {
                        generation++
                        pollJob?.cancel()
                        pollJob = null
                    }
                }
            }
        }
    }

    suspend fun refreshNow() {
        val expected = synchronized(lock) { generation }
        fetch(expected, full = true)
    }

    fun publish(catalog: MixFilesCatalog) {
        synchronized(lock) { accept(catalog) }
    }

    private suspend fun poll(expected: Long) {
        val cached = cache.read(key)
        synchronized(lock) {
            if (generation != expected) return
            if (catalog == null && version == 0L) {
                catalog = cached
                mutableState.value = CatalogState.Offline(cached)
            }
        }
        fetch(expected, full = true)
        while (currentCoroutineContext().isActive) {
            delay(pollMs)
            fetch(expected, full = false)
        }
    }

    private suspend fun fetch(expected: Long, full: Boolean) = requests.withLock {
        currentCoroutineContext().ensureActive()
        val startedVersion = synchronized(lock) {
            if (generation != expected) return@withLock
            version
        }
        if (!full) {
            val revision = request { api.folderRevision(key.job, key.material) }
            val needsCatalog = synchronized(lock) {
                if (!isCurrent(expected, startedVersion)) return@withLock
                when (revision) {
                    is MixFilesResult.Ok -> {
                        val held = catalog
                        if (held != null && held.folderRevision == revision.value) {
                            consecutiveOffline = 0
                            mutableState.value = CatalogState.Live(held)
                            false
                        } else true
                    }
                    else -> {
                        failure(revision, catalogRequest = false)
                        false
                    }
                }
            }
            if (!needsCatalog) return@withLock
        }
        // The mutex also covers a revision-triggered fetch; manual refreshes cannot overlap it.
        val response = request { api.catalog(key.job, key.material) }
        synchronized(lock) {
            if (!isCurrent(expected, startedVersion)) return@withLock
            when (response) {
                is MixFilesResult.Ok -> accept(response.value)
                else -> failure(response, catalogRequest = true)
            }
        }
    }

    private fun isCurrent(expected: Long, startedVersion: Long) =
        generation == expected && version == startedVersion

    /** Called under lock; persist only if this is still the newest accepted snapshot. */
    private fun accept(next: MixFilesCatalog) {
        consecutiveOffline = 0
        catalog = next
        mutableState.value = CatalogState.Live(next)
        val acceptedVersion = ++version
        scope.launch {
            synchronized(lock) {
                if (version == acceptedVersion) runCatching { cache.write(key, next) }
            }
        }
    }

    private fun failure(result: MixFilesResult<*>, catalogRequest: Boolean) {
        if (result is MixFilesResult.Offline && mutableState.value is CatalogState.Live && ++consecutiveOffline < 3) return
        if (result !is MixFilesResult.Offline) consecutiveOffline = 0
        mutableState.value = when {
            result is MixFilesResult.ServiceOutdated -> CatalogState.Outdated(catalog)
            catalogRequest && result is MixFilesResult.Rejected && result.error.code == "not_found" -> CatalogState.NoFolder
            else -> CatalogState.Offline(catalog)
        }
    }

    private suspend fun <T> request(block: suspend () -> MixFilesResult<T>): MixFilesResult<T> =
        try { block() } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            MixFilesResult.Offline
        }
}

/** Ungrouped catalog JSON, preserving opaque entry strings and all modeled wire fields. */
class MixFilesCatalogCache(private val dir: File) {
    private val gson = GsonBuilder().serializeNulls().disableHtmlEscaping().create()

    fun read(key: MaterialKey): MixFilesCatalog? = runCatching {
        MixFilesJson.parseCatalog(file(key).readText(Charsets.UTF_8))
    }.getOrNull()

    @Synchronized
    fun write(key: MaterialKey, catalog: MixFilesCatalog) {
        check(dir.isDirectory || dir.mkdirs()) { "Cannot create mix catalog cache directory" }
        val json = gson.toJsonTree(catalog).asJsonObject.apply {
            addProperty("ok", true)
            getAsJsonArray("files").forEach { row ->
                val item = row.asJsonObject
                item.addProperty("kind", item["kind"].asString.lowercase(Locale.ROOT))
            }
        }
        val target = file(key)
        val temporary = File.createTempFile(target.name, ".tmp", dir)
        try {
            FileOutputStream(temporary).use { output ->
                output.write(gson.toJson(json).toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            try {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally { temporary.delete() }
    }

    private fun file(key: MaterialKey): File {
        // JSON array encoding avoids collisions between names containing path separators.
        val identity = gson.toJson(listOf(key.machineId, key.job, key.material))
        val hash = MessageDigest.getInstance("SHA-1").digest(identity.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return File(dir, "$hash.json")
    }
}

class MixFilesStoreRegistry(
    private val api: (CncMachine) -> MixFilesApi,
    private val cache: MixFilesCatalogCache,
    private val scope: CoroutineScope,
) {
    private val stores = mutableMapOf<MaterialKey, MixFilesStore>()

    @Synchronized
    fun store(machine: CncMachine, job: String, material: String): MixFilesStore {
        val key = MaterialKey(machine.id, job, material)
        return stores.getOrPut(key) { MixFilesStore(key, api(machine), cache, scope) }
    }
}
