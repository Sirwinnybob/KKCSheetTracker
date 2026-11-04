package com.kkc.sheettracker.data.cnc

import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
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
import java.util.UUID

enum class TransferState {
    /** Written before the POST. Found on load, it means the process died mid-submit: UNCERTAIN. */
    SUBMITTING,
    /** Accepted with an operation ID; poll the bound coordinator endpoint. */
    SUBMITTED,
    /** No reply to the POST. Never re-POSTed automatically. */
    UNCERTAIN,
    COMPLETED,
    FAILED,
}

/** Durable mutation record. Endpoints are snapshots: a later machine config change never retargets it. */
data class MaterialTransferRecord(
    val requestId: String,
    val job: String,
    val material: String,
    val sourceMachineId: String,
    val sourceEndpoint: String,
    val destinationMachineId: String,
    val destinationEndpoint: String,
    val previewId: String,
    val expectedSourceRevision: String,
    val punloadPolicy: String,
    val operationId: String?,
    val state: TransferState,
    val phase: String?,
    val message: String?,
    val createdAtMs: Long,
    val updatedAtMs: Long,
) {
    val active: Boolean get() = state == TransferState.SUBMITTING || state == TransferState.SUBMITTED || state == TransferState.UNCERTAIN
}

sealed interface TransferStart {
    data class Started(val record: MaterialTransferRecord) : TransferStart
    /** A move of this job/material is already pending (duplicate tap, second group, or earlier session). */
    data class AlreadyActive(val record: MaterialTransferRecord) : TransferStart
}

/**
 * Persistent material-move journal (app-private, outside any synced root). One active move per
 * job/material regardless of machine. Restoration polls the bound destination before deciding.
 */
class MaterialTransferStore(
    private val file: File,
    private val clientFor: (endpoint: String) -> MaterialTransferApi = { MaterialTransferClient(it) },
    private val now: () -> Long = System::currentTimeMillis,
    private val newRequestId: () -> String = { "tablet-move-${UUID.randomUUID()}" },
) {
    private val gson = GsonBuilder().serializeNulls().disableHtmlEscaping().create()
    private val mutex = Mutex()
    private val mutableRecords = MutableStateFlow(load())
    val records: StateFlow<List<MaterialTransferRecord>> = mutableRecords.asStateFlow()

    fun activeFor(job: String, material: String): MaterialTransferRecord? =
        records.value.firstOrNull { it.active && it.job == job && it.material == material }

    suspend fun start(source: CncMachine, destination: CncMachine, job: String, material: String, preview: TransferPreview,
        expectedSourceRevision: String, policy: PunloadPolicy): TransferStart {
        val record = mutex.withLock {
            activeFor(job, material)?.let { return TransferStart.AlreadyActive(it) }
            val time = now()
            MaterialTransferRecord(newRequestId(), job, material, source.id, source.baseUrl, destination.id, destination.baseUrl,
                preview.previewId, expectedSourceRevision, policy.wire, null, TransferState.SUBMITTING, null, null, time, time)
                .also { put(it) } // Write-ahead: the request exists durably before any byte is sent.
        }
        return TransferStart.Started(submit(record))
    }

    /** Explicit operator action only: resends the same request and preview IDs for the service to deduplicate. */
    suspend fun resendUncertain(requestId: String): MaterialTransferRecord? {
        val record = mutex.withLock {
            val current = records.value.firstOrNull { it.requestId == requestId && it.state == TransferState.UNCERTAIN } ?: return null
            current.copy(state = TransferState.SUBMITTING, updatedAtMs = now()).also { put(it) }
        }
        return submit(record)
    }

    private suspend fun submit(record: MaterialTransferRecord): MaterialTransferRecord {
        val result = try { clientFor(record.destinationEndpoint).start(record.previewId, record.requestId) }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { TransferResult.Offline }
        val next = when (result) {
            is TransferResult.Ok -> record.withOperation(result.value).copy(operationId = result.value.id)
            is TransferResult.Rejected -> record.copy(state = TransferState.FAILED, message = result.message.ifBlank { result.code })
            TransferResult.Unsupported -> record.copy(state = TransferState.FAILED, message = "The CNC service doesn't support moving materials yet.")
            TransferResult.Offline -> record.copy(state = TransferState.UNCERTAIN, message = "Checking move status")
        }.copy(updatedAtMs = now())
        mutex.withLock { put(next) }
        return next
    }

    /** Polls every submitted move once, on its own bound endpoint. Never re-POSTs. */
    suspend fun refreshActive() {
        records.value.filter { it.state == TransferState.SUBMITTED && it.operationId != null }.forEach { record ->
            val result = try { clientFor(record.destinationEndpoint).operation(record.operationId!!) }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { TransferResult.Offline }
            val next = when (result) {
                is TransferResult.Ok -> record.withOperation(result.value)
                // A timeout or refusal to report is not an outcome: keep checking the same operation.
                else -> record.copy(message = "Checking move status")
            }.copy(updatedAtMs = now())
            mutex.withLock { if (records.value.any { it.requestId == record.requestId && it.state == TransferState.SUBMITTED }) put(next) }
        }
    }

    /** Removes a finished (or operator-reviewed uncertain) record. Active submitted moves stay. */
    suspend fun dismiss(requestId: String): Boolean = mutex.withLock {
        val record = records.value.firstOrNull { it.requestId == requestId } ?: return false
        if (record.state == TransferState.SUBMITTING || record.state == TransferState.SUBMITTED) return false
        save(records.value.filterNot { it.requestId == requestId })
        true
    }

    private fun MaterialTransferRecord.withOperation(operation: TransferOperation) = copy(
        phase = operation.phase.ifBlank { phase },
        state = when {
            !operation.isTerminal -> TransferState.SUBMITTED
            operation.succeeded -> TransferState.COMPLETED
            else -> TransferState.FAILED
        },
        message = if (operation.isTerminal && !operation.succeeded) operation.error ?: "The move didn't finish" else null,
    )

    private fun put(record: MaterialTransferRecord) =
        save(records.value.filterNot { it.requestId == record.requestId } + record)

    private fun save(next: List<MaterialTransferRecord>) {
        file.parentFile?.let { check(it.isDirectory || it.mkdirs()) { "Cannot create transfer journal directory" } }
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

    private fun load(): List<MaterialTransferRecord> {
        if (!file.isFile) return emptyList()
        val loaded = try {
            gson.fromJson<List<MaterialTransferRecord>>(file.readText(Charsets.UTF_8), object : TypeToken<List<MaterialTransferRecord>>() {}.type)
                ?.filter { it.requestId.isNotBlank() && it.destinationEndpoint.isNotBlank() && it.state != null }
                ?: emptyList()
        } catch (_: Exception) {
            // Keep the unreadable journal for diagnosis rather than silently discarding a pending move.
            file.renameTo(File(file.parentFile, "${file.name}.corrupt-${now()}"))
            return emptyList()
        }
        // A write-ahead record without a reply means the process died during the POST.
        return loaded.map { if (it.state == TransferState.SUBMITTING) it.copy(state = TransferState.UNCERTAIN, message = "Checking move status") else it }
    }
}
