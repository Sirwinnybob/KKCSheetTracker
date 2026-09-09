package com.kkc.sheettracker.data.mixservice

import com.google.gson.annotations.SerializedName

data class MixOperationRecovery(
    val url: String = "",
    val method: String = "",
    val change: Map<String, Any?> = emptyMap(),
)

data class MixOperationWarning(
    val code: String = "",
    val message: String = "",
    val recoveries: List<MixOperationRecovery> = emptyList(),
)

sealed interface MixOperationRestoreState {
    data object Restoring : MixOperationRestoreState
    data object Ready : MixOperationRestoreState
    data class Failed(val message: String) : MixOperationRestoreState
}

data class MixServiceOperation(
    val id: String = "",
    val kind: String = "",
    val job: String = "",
    val material: String = "",
    val state: String = "queued",
    val stage: String = "queued",
    val completedPrograms: Int = 0,
    val totalPrograms: Int = 0,
    val createdAt: String? = null,
    val startedAt: String? = null,
    val finishedAt: String? = null,
    val error: String? = null,
    /** Stable failure classification (catalog_changed, duplicate_mix, ...); null while non-terminal. */
    val code: String? = null,
    val result: Any? = null,
    val warning: MixOperationWarning? = null,
) {
    val isTerminal: Boolean get() = state in TERMINAL_STATES

    companion object {
        val TERMINAL_STATES = setOf("completed", "failed", "interrupted")
    }
}

/** Network boundary used by the process-scoped operation coordinator. */
interface MixOperationService {
    suspend fun submitMix(
        job: String,
        material: String,
        name: String,
        programs: List<String>,
        replaceExisting: Boolean = false,
    ): MixServiceOperation

    suspend fun submitPgmEdits(
        job: String,
        material: String,
        requestId: String,
        files: List<PgmEditRow>,
    ): MixServiceOperation

    /** Returns the accepted (202) operation; the caller polls [getOperation] for progress and result. */
    suspend fun submitCatalogMutation(action: ManageCodeOperationAction): MixServiceOperation

    suspend fun getOperation(id: String): MixServiceOperation

    suspend fun listJobOperations(job: String): List<MixServiceOperation>
}

/**
 * One ordered server mutation. The plain data shape deliberately remains Gson-friendly so the
 * complete recovery session can be stored in DataStore without a sealed-type adapter.
 */
data class ManageCodeOperationAction(
    val kind: String,
    val material: String,
    val name: String = "",
    val programs: List<String> = emptyList(),
    val replaceExisting: Boolean = false,
    val requestId: String = "",
    val editRows: List<PgmEditRow> = emptyList(),
    val operationId: String? = null,
    /** Job folder is filled by [MixOperationCoordinator] before catalog submission. */
    val job: String = "",
    val expectedRevision: Long = 0L,
    val externalMixFilename: String = "",
) {
    companion object {
        const val MIX = "mix"
        const val PGM_EDITS = "pgm_edits"
        const val CATALOG_CREATE = "catalog_create"
        const val CATALOG_REPLACE = "catalog_replace"
        const val EXTERNAL_DELETE = "external_delete"

        fun mix(
            material: String,
            name: String,
            programs: List<String>,
            replaceExisting: Boolean = false,
        ) = ManageCodeOperationAction(
            kind = MIX,
            material = material,
            name = name,
            programs = programs,
            replaceExisting = replaceExisting,
        )

        fun pgmEdits(material: String, requestId: String, editRows: List<PgmEditRow>) =
            ManageCodeOperationAction(
                kind = PGM_EDITS,
                material = material,
                requestId = requestId,
                editRows = editRows,
            )

        fun catalogCreate(
            material: String,
            name: String,
            programs: List<String>,
            expectedRevision: Long,
        ) = ManageCodeOperationAction(
            kind = CATALOG_CREATE,
            material = material,
            name = name,
            programs = programs,
            expectedRevision = expectedRevision,
        )

        fun catalogCreate(
            job: String,
            material: String,
            name: String,
            programs: List<String>,
            expectedRevision: Long,
        ) = catalogCreate(material, name, programs, expectedRevision).copy(job = job)

        fun catalogReplace(
            material: String,
            name: String,
            programs: List<String>,
            expectedRevision: Long,
        ) = ManageCodeOperationAction(
            kind = CATALOG_REPLACE,
            material = material,
            name = name,
            programs = programs,
            expectedRevision = expectedRevision,
        )

        fun catalogReplace(
            job: String,
            material: String,
            name: String,
            programs: List<String>,
            expectedRevision: Long,
        ) = catalogReplace(material, name, programs, expectedRevision).copy(job = job)

        fun externalDelete(
            material: String,
            externalMixFilename: String,
            expectedRevision: Long,
        ) = ManageCodeOperationAction(
            kind = EXTERNAL_DELETE,
            material = material,
            expectedRevision = expectedRevision,
            externalMixFilename = externalMixFilename,
        )

        fun externalDelete(
            job: String,
            material: String,
            externalMixFilename: String,
            expectedRevision: Long,
        ) = externalDelete(material, externalMixFilename, expectedRevision).copy(job = job)

        fun catalogExternalDelete(
            material: String,
            externalMixFilename: String,
            expectedRevision: Long,
        ) = externalDelete(material, externalMixFilename, expectedRevision)

        fun catalogExternalDelete(
            job: String,
            material: String,
            externalMixFilename: String,
            expectedRevision: Long,
        ) = externalDelete(job, material, externalMixFilename, expectedRevision)
    }
}

enum class MixLifecycle {
    @SerializedName("active") ACTIVE,
    @SerializedName("history") HISTORY,
    @SerializedName("external") EXTERNAL,
}

data class MixCatalogEntry(
    val name: String = "",
    val mixFilename: String = "",
    val lifecycle: MixLifecycle = MixLifecycle.ACTIVE,
    val programs: List<String> = emptyList(),
    val status: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val lastCompiledAt: String? = null,
    val lastCompileOk: Boolean? = null,
    val lastCompileError: String? = null,
)

data class MixCatalogSnapshot(
    val job: String = "",
    val material: String = "",
    val revision: Long = 0L,
    val entries: List<MixCatalogEntry> = emptyList(),
)

sealed class MixCatalogMutationResult {
    data class Success(val snapshot: MixCatalogSnapshot) : MixCatalogMutationResult()

    /** The CNC mutation completed; retrying it could duplicate the completed change. */
    data class SyncFailed(
        val snapshot: MixCatalogSnapshot,
        val code: String,
        val recoveryUrl: String?,
        val recoveries: List<MixOperationRecovery> = emptyList(),
    ) : MixCatalogMutationResult()

    object CatalogChanged : MixCatalogMutationResult()
    object ExternalMixesPresent : MixCatalogMutationResult()
    object EditBusy : MixCatalogMutationResult()
    object CompileBusy : MixCatalogMutationResult()
    object WinxisoTimeout : MixCatalogMutationResult()
    data class DuplicateName(val name: String) : MixCatalogMutationResult()
    data class MissingProgram(val pgm: String) : MixCatalogMutationResult()
    data class HistorySyncError(val message: String) : MixCatalogMutationResult()
    data class BadRequest(val message: String) : MixCatalogMutationResult()
    object NetworkError : MixCatalogMutationResult()
}

data class ManageCodeSession(
    val job: String,
    val actions: List<ManageCodeOperationAction>,
    val currentActionIndex: Int = 0,
    val completedMaterials: Int = 0,
    val warnings: List<MixOperationWarning> = emptyList(),
    val current: MixServiceOperation = MixServiceOperation(job = job),
    /** Snapshots returned by acknowledged catalog mutations, retained for cache recovery. */
    val completedCatalogSnapshots: List<MixCatalogSnapshot> = emptyList(),
) {
    val totalMaterials: Int get() = actions.map { it.material }.distinct().size
    val currentAction: ManageCodeOperationAction? get() = actions.getOrNull(currentActionIndex)
    val isTerminal: Boolean
        get() = currentAction == null || current.state in setOf("failed", "interrupted")
    val isCompletedSuccessfully: Boolean
        get() = currentAction == null && current.state == "completed"
}

class MixOperationClientException(message: String) : IllegalStateException(message)
