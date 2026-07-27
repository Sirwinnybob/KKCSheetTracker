package com.kkc.sheettracker.data

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Stores the completion state of sheet-mode board stock item rips per job.
 * Lives at: {baseDir}/{jobFolderName}/.metadata/admin/sheet_rip_done.json
 * Thread-safe and atomic writes.
 */
class SheetRipProgressStore(
    private val baseDir: File
) {
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val writeMutexByJob = ConcurrentHashMap<String, Mutex>()
    private val latestProjectionRevisionByItem = ConcurrentHashMap<String, Long>()
    private val nextProjectionRevisionByItem = ConcurrentHashMap<String, AtomicLong>()

    private fun projectionKey(jobFolderName: String, itemId: String): String = "$jobFolderName|$itemId"

    fun nextProjectionRevision(jobFolderName: String, itemId: String): Long =
        nextProjectionRevisionByItem.getOrPut(projectionKey(jobFolderName, itemId)) { AtomicLong() }.incrementAndGet()

    private fun sheetRipFile(jobFolderName: String): File {
        return File(baseDir, "$jobFolderName/.metadata/admin/sheet_rip_done.json")
    }

    fun loadDone(jobFolderName: String): Map<String, Boolean> {
        val file = sheetRipFile(jobFolderName)
        if (!file.exists() || !file.isFile) return emptyMap()
        return runCatching {
            val type = object : TypeToken<Map<String, Boolean>>() {}.type
            gson.fromJson<Map<String, Boolean>>(file.readText(), type) ?: emptyMap()
        }.getOrElse { emptyMap() }
    }

    suspend fun setDone(
        jobFolderName: String,
        itemId: String,
        done: Boolean,
        projectionRevision: Long? = null
    ) {
        if (itemId.isBlank()) return
        val mutex = writeMutexByJob.getOrPut(jobFolderName) { Mutex() }
        mutex.withLock {
            val projectionKey = projectionKey(jobFolderName, itemId)
            if (projectionRevision != null) {
                val latestRevision = latestProjectionRevisionByItem[projectionKey]
                if (latestRevision != null && projectionRevision < latestRevision) return
                latestProjectionRevisionByItem[projectionKey] = projectionRevision
            }
            val current = loadDone(jobFolderName).toMutableMap()
            current[itemId] = done
            val body = gson.toJson(current)
            atomicWriteFile(sheetRipFile(jobFolderName), body)
        }
    }
}
