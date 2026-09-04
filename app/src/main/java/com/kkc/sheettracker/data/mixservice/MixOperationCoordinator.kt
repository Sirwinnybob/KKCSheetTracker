package com.kkc.sheettracker.data.mixservice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Owns job mutations independently of a Compose lifecycle. Every visible change is first made
 * durable, then emitted, so navigation or process recreation cannot lose an accepted operation.
 */
class MixOperationCoordinator(
    private val service: MixOperationService,
    private val store: MixOperationSessionStore,
    private val pollIntervalMillis: Long = POLL_INTERVAL_MILLIS,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    /** Process-shared cache sink. It is invoked only after a completed session is durable. */
    private val catalogPublisher: MixCatalogPublisher? = null,
) {
    private val lock = Mutex()
    private val _sessions = MutableStateFlow<Map<String, ManageCodeSession>>(emptyMap())
    val sessions: StateFlow<Map<String, ManageCodeSession>> = _sessions.asStateFlow()
    private val _restoreState = MutableStateFlow<MixOperationRestoreState>(MixOperationRestoreState.Restoring)
    val restoreState: StateFlow<MixOperationRestoreState> = _restoreState.asStateFlow()
    private val restoreLock = Mutex()

    fun start(session: ManageCodeSession) {
        if (_restoreState.value != MixOperationRestoreState.Ready) return
        scope.launch { startInternal(session) }
    }

    fun retry(job: String, material: String) {
        if (_restoreState.value != MixOperationRestoreState.Ready) return
        scope.launch {
            val retrySession = lock.withLock {
                val existing = _sessions.value[job] ?: return@launch
                if (existing.current.state !in setOf("failed", "interrupted")) return@launch
                if (existing.currentAction?.material != material) return@launch
                val index = existing.currentActionIndex
                val resetActions = existing.actions.toMutableList().also {
                    it[index] = it[index].copy(operationId = null)
                }
                existing.copy(
                    actions = resetActions,
                    currentActionIndex = index,
                    completedMaterials = completedMaterialCount(resetActions, index),
                    current = MixServiceOperation(job = job, material = material),
                ).also { publishLocked(it) }
            }
            submitCurrentAction(retrySession.job)
        }
    }

    /**
     * Replaces the durable session left by an acknowledged catalog conflict.
     *
     * A catalog conflict is not retryable: its action carries a stale revision. The caller must
     * provide a newly planned, clean session (including fresh catalog revisions and PGM request
     * IDs). The replacement is saved before its first action is submitted, and the old action is
     * never replayed.
     */
    suspend fun replaceCatalogChangedSession(replacement: ManageCodeSession): Boolean {
        if (_restoreState.value != MixOperationRestoreState.Ready) return false
        val shouldSubmit = lock.withLock {
            if (_restoreState.value != MixOperationRestoreState.Ready) return false
            val existing = _sessions.value[replacement.job] ?: return false
            if (!existing.isFailedCatalogChangedSession()) return false
            if (!replacement.isFreshReplacementSession()) return false

            publishLocked(replacement)
            true
        }
        if (shouldSubmit) submitCurrentAction(replacement.job)
        return shouldSubmit
    }

    fun restore() {
        scope.launch {
            restoreLock.withLock {
                _restoreState.value = MixOperationRestoreState.Restoring
                val loadResult = runCatching { store.load() }.getOrElse {
                    MixOperationSessionLoadResult.Failure(
                        it.message?.takeIf(String::isNotBlank) ?: "persisted sessions could not be read"
                    )
                }
                if (loadResult is MixOperationSessionLoadResult.Failure) {
                    _restoreState.value = MixOperationRestoreState.Failed(loadResult.message)
                    return@withLock
                }
                val restored = (loadResult as MixOperationSessionLoadResult.Success).sessions
                lock.withLock {
                    _sessions.value = restored
                }
                _restoreState.value = MixOperationRestoreState.Ready
                restored.values.forEach { session ->
                    scope.launch {
                        val action = session.currentAction ?: return@launch
                        val id = action.operationId
                        if (id == null) {
                            if (action.isCatalogAction && session.current.state == "failed") return@launch
                            markInterrupted(session.job, action.material, "submission was not acknowledged before restart")
                        } else {
                            pollExistingOperation(session.job, id)
                        }
                    }
                }
            }
        }
    }

    private suspend fun startInternal(session: ManageCodeSession) {
        val shouldSubmit = lock.withLock {
            if (_restoreState.value != MixOperationRestoreState.Ready) return
            val existing = _sessions.value[session.job]
            if (existing != null && !existing.isCompletedSuccessfully) return
            publishLocked(session)
            true
        }
        if (shouldSubmit) submitCurrentAction(session.job)
    }

    private suspend fun submitCurrentAction(job: String) {
        val action = lock.withLock {
            val session = _sessions.value[job] ?: return
            val next = session.currentAction ?: return
            if (next.operationId != null) return
            val submittedAction = if (next.isCatalogAction && next.job != job) {
                next.copy(job = job)
            } else {
                next
            }
            val actions = session.actions.toMutableList().also {
                it[session.currentActionIndex] = submittedAction
            }
            publishLocked(
                session.copy(
                    actions = actions,
                    current = MixServiceOperation(
                        job = job,
                        kind = submittedAction.kind,
                        material = submittedAction.material,
                        state = "submitting",
                        stage = "submitting",
                    )
                )
            )
            submittedAction
        }

        if (action.isCatalogAction) {
            submitCatalogAction(job, action)
            return
        }

        val accepted = runCatching {
            when (action.kind) {
                ManageCodeOperationAction.MIX -> service.submitMix(
                    job,
                    action.material,
                    action.name,
                    action.programs,
                    action.replaceExisting,
                )
                ManageCodeOperationAction.PGM_EDITS -> service.submitPgmEdits(
                    job,
                    action.material,
                    action.requestId,
                    action.editRows,
                )
                else -> throw MixOperationClientException("unknown operation action: ${action.kind}")
            }
        }
        if (accepted.isFailure) {
            markInterrupted(job, action.material, accepted.exceptionOrNull()?.message ?: "operation submission was not acknowledged")
            return
        }

        val operation = accepted.getOrThrow()
        lock.withLock {
            val session = _sessions.value[job] ?: return
            val index = session.currentActionIndex
            val actions = session.actions.toMutableList().also { it[index] = it[index].copy(operationId = operation.id) }
            publishLocked(session.copy(actions = actions, current = operation))
        }
        pollExistingOperation(job, operation.id)
    }

    private suspend fun submitCatalogAction(job: String, action: ManageCodeOperationAction) {
        val result = runCatching { service.submitCatalogMutation(action) }
        if (result.isFailure) {
            markInterrupted(
                job,
                action.material,
                result.exceptionOrNull()?.message ?: "catalog submission was not acknowledged",
            )
            return
        }

        val nextJob = lock.withLock {
            val session = _sessions.value[job] ?: return@withLock null
            if (session.currentAction != action) return@withLock null
            when (val mutation = result.getOrThrow()) {
                is MixCatalogMutationResult.Success -> completeCatalogAction(
                    session,
                    action,
                    mutation.snapshot,
                    warning = null,
                )
                is MixCatalogMutationResult.SyncFailed -> completeCatalogAction(
                    session,
                    action,
                    mutation.snapshot,
                    warning = MixOperationWarning(
                        code = mutation.code,
                        message = mutation.code,
                        recoveries = mutation.recoveries.ifEmpty {
                            mutation.recoveryUrl?.let { listOf(MixOperationRecovery(url = it)) }
                                ?: emptyList()
                        },
                    ),
                )
                else -> {
                    publishLocked(
                        session.copy(current = catalogFailureOperation(job, action, mutation))
                    )
                    null
                }
            }
        }
        if (nextJob != null) submitCurrentAction(nextJob)
    }

    private suspend fun completeCatalogAction(
        session: ManageCodeSession,
        action: ManageCodeOperationAction,
        snapshot: MixCatalogSnapshot,
        warning: MixOperationWarning?,
    ): String? {
        val nextIndex = session.currentActionIndex + 1
        val completed = session.copy(
            currentActionIndex = nextIndex,
            completedMaterials = completedMaterialCount(session.actions, nextIndex),
            warnings = warning?.let { session.warnings + it } ?: session.warnings,
            current = MixServiceOperation(
                kind = action.kind,
                job = session.job,
                material = action.material,
                state = "completed",
                stage = "completed",
                result = snapshot,
                warning = warning,
            ),
        )
        persistCatalogCompletionLocked(completed, snapshot)
        return if (nextIndex < session.actions.size) session.job else null
    }

    /**
     * Catalog completion has two durable consumers. Save the operation first, then update the
     * shared display cache, and only then emit the in-memory session so readers cannot observe a
     * completed mutation while still seeing the previous catalog revision.
     */
    private suspend fun persistCatalogCompletionLocked(
        session: ManageCodeSession,
        snapshot: MixCatalogSnapshot,
    ) {
        val updated = _sessions.value + (session.job to session)
        store.save(updated)
        if (MixCatalogJson.isValidSnapshot(snapshot)) {
            runCatching { catalogPublisher?.publish(snapshot) }
        }
        _sessions.value = updated
    }

    private fun catalogFailureOperation(
        job: String,
        action: ManageCodeOperationAction,
        result: MixCatalogMutationResult,
    ) = MixServiceOperation(
        kind = action.kind,
        job = job,
        material = action.material,
        state = "failed",
        stage = "failed",
        error = result.failureCode(),
        result = result,
    )

    private suspend fun pollExistingOperation(job: String, operationId: String) {
        while (true) {
            val operation = runCatching { service.getOperation(operationId) }.getOrNull()
            if (operation == null) {
                delay(pollIntervalMillis)
                continue
            }
            val nextJob = lock.withLock {
                val session = _sessions.value[job] ?: return
                if (session.currentAction?.operationId != operationId) return
                if (!operation.isTerminal) {
                    publishLocked(session.copy(current = operation))
                    null
                } else if (operation.state == "completed") {
                    val nextIndex = session.currentActionIndex + 1
                    publishLocked(
                        session.copy(
                            currentActionIndex = nextIndex,
                            completedMaterials = completedMaterialCount(session.actions, nextIndex),
                            warnings = operation.warning?.let { session.warnings + it } ?: session.warnings,
                            current = operation,
                        )
                    )
                    if (nextIndex < session.actions.size) job else null
                } else {
                    publishLocked(session.copy(current = operation))
                    null
                }
            }
            if (operation.isTerminal) {
                if (operation.state == "completed" && nextJob != null) submitCurrentAction(nextJob)
                return
            }
            delay(pollIntervalMillis)
        }
    }

    private suspend fun markInterrupted(job: String, material: String, message: String) {
        lock.withLock {
            val session = _sessions.value[job] ?: return
            publishLocked(
                session.copy(
                    current = MixServiceOperation(
                        job = job,
                        material = material,
                        state = "interrupted",
                        stage = "interrupted",
                        error = message,
                    )
                )
            )
        }
    }

    private suspend fun publishLocked(session: ManageCodeSession) {
        val updated = _sessions.value + (session.job to session)
        store.save(updated)
        _sessions.value = updated
    }

    private companion object {
        const val POLL_INTERVAL_MILLIS = 2_000L

        fun completedMaterialCount(actions: List<ManageCodeOperationAction>, nextActionIndex: Int): Int =
            actions.map { it.material }.distinct().count { material ->
                actions.indexOfLast { it.material == material } < nextActionIndex
            }
    }
}

private fun ManageCodeSession.isFailedCatalogChangedSession(): Boolean =
    current.state == "failed" &&
        current.error == "catalog_changed" &&
        currentAction?.isCatalogAction == true

private fun ManageCodeSession.isFreshReplacementSession(): Boolean =
    actions.isNotEmpty() &&
        currentActionIndex == 0 &&
        current.state == "queued" &&
        actions.all { it.operationId == null }

private val ManageCodeOperationAction.isCatalogAction: Boolean
    get() = kind in setOf(
        ManageCodeOperationAction.CATALOG_CREATE,
        ManageCodeOperationAction.CATALOG_REPLACE,
        ManageCodeOperationAction.EXTERNAL_DELETE,
    )

private fun MixCatalogMutationResult.failureCode(): String = when (this) {
    MixCatalogMutationResult.CatalogChanged -> "catalog_changed"
    MixCatalogMutationResult.ExternalMixesPresent -> "external_mixes_present"
    MixCatalogMutationResult.EditBusy -> "edit_busy"
    MixCatalogMutationResult.CompileBusy -> "compile_busy"
    MixCatalogMutationResult.WinxisoTimeout -> "winxiso_timeout"
    is MixCatalogMutationResult.DuplicateName -> "duplicate_name: ${name}"
    is MixCatalogMutationResult.MissingProgram -> "missing_program: ${pgm}"
    is MixCatalogMutationResult.HistorySyncError -> "history_sync_failed: ${message}"
    is MixCatalogMutationResult.BadRequest -> "bad_request: ${message}"
    MixCatalogMutationResult.NetworkError -> "network_error"
    is MixCatalogMutationResult.Success -> "success"
    is MixCatalogMutationResult.SyncFailed -> code
}
