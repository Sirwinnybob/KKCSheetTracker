package com.kkc.sheettracker.data.cnc

import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import com.kkc.sheettracker.data.mixservice.MixServiceOperation
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

enum class MixOperationKind {
    /** Synchronous mix-files mutation (write, FRZ, split, restore, rename, delete): the reply is the outcome. */
    MIX_WRITE,
    /** Async second-pass edit (`POST .../pgm-edits` → 202 operation). */
    SECOND_PASS_EDIT,
    /** Async second-pass revert to original (`POST .../pgm-edits/revert` → 202 operation). */
    SECOND_PASS_REVERT,
}

enum class MixOperationState {
    /** Written before the POST. Found on load, it means the process died mid-submit: UNCERTAIN. */
    SUBMITTING,
    /** Accepted with an operation ID; the app watcher polls `GET {endpoint}/operations/{id}`. */
    SUBMITTED,
    /** No reply to the POST. Never re-POSTed automatically; the operator reviews the CNC copy. */
    UNCERTAIN,
    COMPLETED,
    FAILED,
    /** Interrupted, or not every file succeeded: the CNC copy may be partly changed. */
    NEEDS_ATTENTION,
}

/**
 * Durable record of one CNC mix/second-pass mutation. The endpoint is a snapshot: a later machine
 * config change never retargets it. [operation] is the latest status snapshot from the service.
 */
data class MixOperationRecord(
    val requestId: String,
    val kind: MixOperationKind,
    val machineId: String,
    val endpoint: String,
    val job: String,
    val material: String,
    /** Operator-facing subject, such as the mix file name. */
    val label: String,
    val operationId: String?,
    val state: MixOperationState,
    val stage: String?,
    val message: String?,
    /** 1-based step of a multi-step second-pass plan, and the plan size. */
    val step: Int,
    val steps: Int,
    val operation: MixServiceOperation?,
    /** The operator saw the outcome (on the Mixes screen or by dismissing the app notice). */
    val acknowledged: Boolean,
    val createdAtMs: Long,
    val updatedAtMs: Long,
) {
    val pollable: Boolean get() = state == MixOperationState.SUBMITTED && !operationId.isNullOrBlank()
    val terminal: Boolean get() = state != MixOperationState.SUBMITTING && state != MixOperationState.SUBMITTED
}

/** Persistent journal of mix/second-pass mutations (app-private, outside any synced root). */
class MixOperationStore(
    private val file: File,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val gson = GsonBuilder().serializeNulls().disableHtmlEscaping().create()
    private val mutex = Mutex()
    private val mutableRecords = MutableStateFlow(load())
    val records: StateFlow<List<MixOperationRecord>> = mutableRecords.asStateFlow()

    fun get(requestId: String): MixOperationRecord? = records.value.firstOrNull { it.requestId == requestId }

    /** Write-ahead: the request exists durably before any byte is sent. */
    suspend fun begin(requestId: String, kind: MixOperationKind, machineId: String, endpoint: String, job: String,
        material: String, label: String, step: Int = 1, steps: Int = 1): MixOperationRecord = mutex.withLock {
        val time = now()
        MixOperationRecord(requestId, kind, machineId, endpoint, job, material, label, null, MixOperationState.SUBMITTING,
            null, null, step, steps, null, false, time, time).also(::put)
    }

    /** Applies [change] to the record when it exists; returns the stored result. */
    suspend fun update(requestId: String, change: (MixOperationRecord) -> MixOperationRecord): MixOperationRecord? = mutex.withLock {
        val current = get(requestId) ?: return null
        change(current).copy(updatedAtMs = now()).also(::put)
    }

    /** Hides a terminal outcome. Finished records have no other use, so they are removed. */
    suspend fun acknowledge(requestId: String): Boolean = mutex.withLock {
        val record = get(requestId) ?: return false
        if (!record.terminal) return false
        save(records.value.filterNot { it.requestId == requestId })
        true
    }

    private fun put(record: MixOperationRecord) =
        save(records.value.filterNot { it.requestId == record.requestId } + record)

    private fun save(next: List<MixOperationRecord>) {
        file.parentFile?.let { check(it.isDirectory || it.mkdirs()) { "Cannot create mix operation journal directory" } }
        val temporary = File.createTempFile(file.name, ".tmp", file.parentFile)
        try {
            FileOutputStream(temporary).use { it.write(gson.toJson(next).toByteArray(Charsets.UTF_8)); it.fd.sync() }
            try {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally { temporary.delete() }
        mutableRecords.value = next
    }

    private fun load(): List<MixOperationRecord> {
        if (!file.isFile) return emptyList()
        val loaded = try {
            gson.fromJson<List<MixOperationRecord>>(file.readText(Charsets.UTF_8), object : TypeToken<List<MixOperationRecord>>() {}.type)
                ?.filter { it.requestId.isNotBlank() && it.endpoint.isNotBlank() && it.state != null && it.kind != null }
                ?: emptyList()
        } catch (_: Exception) {
            // Keep the unreadable journal for diagnosis rather than silently discarding a pending operation.
            file.renameTo(File(file.parentFile, "${file.name}.corrupt-${now()}"))
            return emptyList()
        }
        // A write-ahead record without a reply means the process died during the POST.
        return loaded.map {
            if (it.state == MixOperationState.SUBMITTING) it.copy(state = MixOperationState.UNCERTAIN, message = UNCONFIRMED) else it
        }
    }

    companion object {
        const val UNCONFIRMED = "The tablet didn't get the CNC's reply."
    }
}
