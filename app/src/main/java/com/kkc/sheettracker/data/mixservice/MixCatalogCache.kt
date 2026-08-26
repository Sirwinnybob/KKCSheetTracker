package com.kkc.sheettracker.data.mixservice

import android.content.Context
import com.google.gson.Gson
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Durable, per-job/material catalog snapshots. The enclosing cache directory is injected. */
class MixCatalogCache(private val root: File) {
    private val gson = Gson()

    @Synchronized
    fun read(job: String, material: String): MixCatalogSnapshot? {
        val file = cacheFile(job, material)
        if (!file.isFile) return null
        val snapshot = runCatching {
            gson.fromJson(file.readText(StandardCharsets.UTF_8), MixCatalogSnapshot::class.java)
        }.getOrNull() ?: return null
        return snapshot.takeIf { it.job == job && it.material == material }
    }

    /** Writes only the first snapshot or a strictly newer revision. */
    @Synchronized
    fun write(snapshot: MixCatalogSnapshot): Boolean {
        val existing = read(snapshot.job, snapshot.material)
        if (existing != null && snapshot.revision <= existing.revision) return false

        root.mkdirs()
        val destination = cacheFile(snapshot.job, snapshot.material)
        val temporary = File.createTempFile(destination.name, ".tmp", root)
        try {
            FileOutputStream(temporary).use { output ->
                output.write(gson.toJson(snapshot).toByteArray(StandardCharsets.UTF_8))
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
            return true
        } finally {
            temporary.delete()
        }
    }

    private fun cacheFile(job: String, material: String): File = File(root, "${key(job, material)}.json")

    private fun key(job: String, material: String): String = MessageDigest.getInstance("SHA-256")
        .digest("$job\u0000$material".toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

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
        .also { result -> if (result is MixCatalogMutationResult.Success) cache.write(result.snapshot) }

    suspend fun deleteExternalMix(
        job: String,
        material: String,
        filename: String,
        expectedRevision: Long
    ): MixCatalogMutationResult = client.deleteExternalMix(job, material, filename, expectedRevision)
        .also { result -> if (result is MixCatalogMutationResult.Success) cache.write(result.snapshot) }
}
