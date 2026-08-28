package com.kkc.sheettracker.data.mixservice

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
) {
    companion object {
        const val MIX = "mix"
        const val PGM_EDITS = "pgm_edits"

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
    }
}

data class ManageCodeSession(
    val job: String,
    val actions: List<ManageCodeOperationAction>,
    val currentActionIndex: Int = 0,
    val completedMaterials: Int = 0,
    val warnings: List<MixOperationWarning> = emptyList(),
    val current: MixServiceOperation = MixServiceOperation(job = job),
) {
    val totalMaterials: Int get() = actions.map { it.material }.distinct().size
    val currentAction: ManageCodeOperationAction? get() = actions.getOrNull(currentActionIndex)
    val isTerminal: Boolean
        get() = currentAction == null || current.state in setOf("failed", "interrupted")
    val isCompletedSuccessfully: Boolean
        get() = currentAction == null && current.state == "completed"
}

class MixOperationClientException(message: String) : IllegalStateException(message)
