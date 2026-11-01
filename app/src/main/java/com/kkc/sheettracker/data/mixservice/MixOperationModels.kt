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
    /** Stable failure classification; null while non-terminal. */
    val code: String? = null,
    val result: Any? = null,
    val warning: MixOperationWarning? = null,
) {
    val isTerminal: Boolean get() = state in TERMINAL_STATES

    companion object {
        val TERMINAL_STATES = setOf("completed", "failed", "interrupted")
    }
}

/** Async operation boundary for second-pass PGM edits. */
interface MixOperationService {
    suspend fun submitPgmEdits(
        job: String,
        material: String,
        requestId: String,
        files: List<PgmEditRow>,
    ): MixServiceOperation

    suspend fun getOperation(id: String): MixServiceOperation
}

class MixOperationClientException(message: String) : IllegalStateException(message)
