package com.kkc.sheettracker.data.mixservice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MixOperationCoordinatorTest {

    @Test
    fun `coordinator retains active job session after observer cancellation`() = runBlocking {
        val store = InMemorySessionStore()
        val service = CompletingService()
        val coordinator = MixOperationCoordinator(service, store, pollIntervalMillis = 1)

        coordinator.restore()
        withTimeout(1_000) { coordinator.restoreState.first { it == MixOperationRestoreState.Ready } }
        coordinator.start(session())
        coordinator.sessions.first { it["648"]?.current?.state == "running" }
        val terminal = withTimeout(1_000) { coordinator.sessions.first { it["648"]?.isTerminal == true } }

        assertEquals("completed", terminal.getValue("648").current.state)
        assertEquals(1, store.currentSessions.getValue("648").completedMaterials)
    }

    @Test
    fun `restored interrupted operation requires retry without resubmitting`() = runBlocking {
        val interrupted = operation(id = "old", state = "interrupted", stage = "interrupted")
        val store = InMemorySessionStore(
            mapOf("648" to session(operationId = interrupted.id))
        )
        val service = CompletingService(operationToRead = interrupted)
        val coordinator = MixOperationCoordinator(service, store, pollIntervalMillis = 1)

        coordinator.restore()
        withTimeout(1_000) { coordinator.restoreState.first { it == MixOperationRestoreState.Ready } }
        val restored = withTimeout(1_000) {
            coordinator.sessions.first { it["648"]?.current?.state == "interrupted" }
        }.getValue("648")

        assertEquals("interrupted", restored.current.state)
        assertTrue(restored.isTerminal)
        assertEquals(0, service.submitCount)
    }

    @Test
    fun `restored unacknowledged submission becomes retry required without resubmitting`() = runBlocking {
        val pending = session(current = operation(state = "submitting", stage = "submitting"))
        val store = InMemorySessionStore(mapOf("648" to pending))
        val service = CompletingService()
        val coordinator = MixOperationCoordinator(service, store, pollIntervalMillis = 1)

        coordinator.restore()
        withTimeout(1_000) { coordinator.restoreState.first { it == MixOperationRestoreState.Ready } }
        val restored = withTimeout(1_000) {
            coordinator.sessions.first { it["648"]?.current?.state == "interrupted" }
        }.getValue("648")

        assertEquals("interrupted", restored.current.state)
        assertEquals(0, service.submitCount)
    }

    @Test
    fun `material completes only after every queued action for that material completes`() = runBlocking {
        val store = InMemorySessionStore()
        val service = TwoActionService(firstWarning = null, holdEditCompletion = true)
        val coordinator = MixOperationCoordinator(service, store, pollIntervalMillis = 1)
        val twoActions = session().copy(
            actions = listOf(
                ManageCodeOperationAction.mix("M", "Mix", listOf("R1.pgm")),
                ManageCodeOperationAction.pgmEdits("M", "request", emptyList()),
            )
        )

        coordinator.restore()
        withTimeout(1_000) { coordinator.restoreState.first { it == MixOperationRestoreState.Ready } }
        coordinator.start(twoActions)
        val editing = withTimeout(1_000) {
            coordinator.sessions.first { it["648"]?.current?.kind == "pgm_edit" }
        }.getValue("648")

        assertEquals(0, editing.completedMaterials)
        assertEquals(1, editing.totalMaterials)
        assertTrue(store.saved.none { it.currentActionIndex == 1 && it.isTerminal })

        service.allowEditCompletion.complete(Unit)
        val finished = withTimeout(1_000) {
            coordinator.sessions.first { it["648"]?.currentAction == null }
        }.getValue("648")
        assertEquals(1, finished.completedMaterials)
    }

    @Test
    fun `completed warning advances to the next action without retrying the completed action`() = runBlocking {
        val warning = MixOperationWarning(
            code = "history_sync_failed",
            message = "history unavailable",
            recoveries = emptyList(),
        )
        val service = TwoActionService(firstWarning = warning)
        val coordinator = MixOperationCoordinator(service, InMemorySessionStore(), pollIntervalMillis = 1)
        val twoActions = session().copy(
            actions = listOf(
                ManageCodeOperationAction.mix("M", "Mix", listOf("R1.pgm")),
                ManageCodeOperationAction.pgmEdits("M", "request", emptyList()),
            )
        )

        coordinator.restore()
        withTimeout(1_000) { coordinator.restoreState.first { it == MixOperationRestoreState.Ready } }
        coordinator.start(twoActions)
        val finished = withTimeout(1_000) {
            coordinator.sessions.first { it["648"]?.currentAction == null }
        }.getValue("648")

        assertEquals(1, finished.completedMaterials)
        assertEquals(1, service.mixSubmitCount)
        assertEquals(1, service.editSubmitCount)
        assertEquals(listOf(warning), finished.warnings)
        assertEquals(null, finished.current.warning)
    }

    @Test
    fun `completed successful session can be replaced by a new generated session`() = runBlocking {
        val service = CompletingService()
        val coordinator = MixOperationCoordinator(service, InMemorySessionStore(), pollIntervalMillis = 1)

        coordinator.restore()
        withTimeout(1_000) { coordinator.restoreState.first { it == MixOperationRestoreState.Ready } }
        coordinator.start(session())
        withTimeout(1_000) { coordinator.sessions.first { it["648"]?.currentAction == null } }
        assertEquals(1, service.submitCount)

        val replacement = session().copy(
            actions = listOf(ManageCodeOperationAction.mix("M", "Replacement", listOf("R2.pgm")))
        )
        coordinator.start(replacement)
        withTimeout(1_000) {
            coordinator.sessions.first {
                service.submitCount == 2 && it["648"]?.actions?.single()?.name == "Replacement"
            }
        }

        assertEquals(2, service.submitCount)
    }

    @Test
    fun `retrying failed edit does not count material complete before retry completes`() = runBlocking {
        val service = RetryEditService()
        val coordinator = MixOperationCoordinator(service, InMemorySessionStore(), pollIntervalMillis = 1)
        val twoActions = session().copy(
            actions = listOf(
                ManageCodeOperationAction.mix("M", "Mix", listOf("R1.pgm")),
                ManageCodeOperationAction.pgmEdits("M", "request", emptyList()),
            )
        )

        coordinator.restore()
        withTimeout(1_000) { coordinator.restoreState.first { it == MixOperationRestoreState.Ready } }
        coordinator.start(twoActions)
        val failed = withTimeout(1_000) {
            coordinator.sessions.first { it["648"]?.current?.state == "failed" }
        }.getValue("648")
        assertEquals(0, failed.completedMaterials)

        coordinator.retry("648", "M")
        val retrying = withTimeout(1_000) {
            coordinator.sessions.first { it["648"]?.current?.id == "edit-2" }
        }.getValue("648")

        assertEquals(0, retrying.completedMaterials)
        service.allowRetryCompletion.complete(Unit)
        withTimeout(1_000) { coordinator.sessions.first { it["648"]?.currentAction == null } }
        Unit
    }

    @Test
    fun `coordinator stays not ready until restore finishes and blocks early submission`() = runBlocking {
        val store = ControlledSessionStore()
        val service = CompletingService()
        val coordinator = MixOperationCoordinator(service, store, pollIntervalMillis = 1)

        assertEquals(MixOperationRestoreState.Restoring, coordinator.restoreState.value)
        coordinator.start(session())
        delay(20)
        assertEquals(0, service.submitCount)

        coordinator.restore()
        delay(20)
        assertEquals(MixOperationRestoreState.Restoring, coordinator.restoreState.value)
        store.nextLoad.complete(MixOperationSessionLoadResult.Success(emptyMap()))
        withTimeout(1_000) { coordinator.restoreState.first { it == MixOperationRestoreState.Ready } }

        coordinator.start(session())
        withTimeout(1_000) { coordinator.sessions.first { it["648"]?.currentAction == null } }
        assertEquals(1, service.submitCount)
    }

    @Test
    fun `restore failure remains not ready and can be retried without submitting`() = runBlocking {
        val store = InMemorySessionStore(
            initialLoad = MixOperationSessionLoadResult.Failure("persisted session JSON is malformed")
        )
        val service = CompletingService()
        val coordinator = MixOperationCoordinator(service, store, pollIntervalMillis = 1)

        coordinator.restore()
        val failure = withTimeout(1_000) {
            coordinator.restoreState.first { it is MixOperationRestoreState.Failed }
        }
        assertEquals(
            "persisted session JSON is malformed",
            (failure as MixOperationRestoreState.Failed).message,
        )
        coordinator.start(session())
        delay(20)
        assertEquals(0, service.submitCount)

        store.nextLoad = MixOperationSessionLoadResult.Success(emptyMap())
        coordinator.restore()
        withTimeout(1_000) { coordinator.restoreState.first { it == MixOperationRestoreState.Ready } }
        assertTrue(coordinator.sessions.value.isEmpty())
    }

    @Test
    fun `restored status read errors keep the accepted operation without replay`() = runBlocking {
        val persisted = session(operationId = "accepted").copy(
            current = operation(id = "accepted", state = "running", stage = "compiling")
        )
        val store = InMemorySessionStore(mapOf("648" to persisted))
        val service = StatusErrorService()
        val coordinatorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val coordinator = MixOperationCoordinator(service, store, pollIntervalMillis = 1, scope = coordinatorScope)

        coordinator.restore()
        withTimeout(1_000) { coordinator.restoreState.first { it == MixOperationRestoreState.Ready } }
        withTimeout(1_000) { coordinator.sessions.first { it["648"]?.current?.state == "running" } }
        delay(20)

        assertEquals(0, service.submitCount)
        assertEquals("accepted", coordinator.sessions.value.getValue("648").currentAction?.operationId)
        assertEquals("running", coordinator.sessions.value.getValue("648").current.state)
        coordinatorScope.cancel()
    }

    private fun session(
        operationId: String? = null,
        current: MixServiceOperation = operation(),
    ) = ManageCodeSession(
        job = "648",
        actions = listOf(
            ManageCodeOperationAction(
                kind = ManageCodeOperationAction.MIX,
                material = "M",
                name = "Mix",
                programs = listOf("R1.pgm"),
                operationId = operationId,
            )
        ),
        current = current,
    )

    private fun operation(
        id: String = "",
        state: String = "queued",
        stage: String = "queued",
    ) = MixServiceOperation(
        id = id,
        kind = "mix_write",
        job = "648",
        material = "M",
        state = state,
        stage = stage,
    )

    private class InMemorySessionStore(
        initial: Map<String, ManageCodeSession> = emptyMap(),
        initialLoad: MixOperationSessionLoadResult = MixOperationSessionLoadResult.Success(initial),
    ) : MixOperationSessionStore {
        var currentSessions = initial
        var nextLoad = initialLoad
        val saved = mutableListOf<ManageCodeSession>()

        override suspend fun load(): MixOperationSessionLoadResult = nextLoad

        override suspend fun save(sessions: Map<String, ManageCodeSession>) {
            currentSessions = sessions
            nextLoad = MixOperationSessionLoadResult.Success(sessions)
            saved += sessions.values
        }
    }

    private class ControlledSessionStore : MixOperationSessionStore {
        val nextLoad = kotlinx.coroutines.CompletableDeferred<MixOperationSessionLoadResult>()

        override suspend fun load(): MixOperationSessionLoadResult = nextLoad.await()

        override suspend fun save(sessions: Map<String, ManageCodeSession>) = Unit
    }

    private inner class TwoActionService(
        private val firstWarning: MixOperationWarning?,
        holdEditCompletion: Boolean = false,
    ) : MixOperationService {
        var mixSubmitCount = 0
        var editSubmitCount = 0
        val allowEditCompletion = kotlinx.coroutines.CompletableDeferred<Unit>().also {
            if (!holdEditCompletion) it.complete(Unit)
        }

        override suspend fun submitMix(
            job: String,
            material: String,
            name: String,
            programs: List<String>,
            replaceExisting: Boolean,
        ): MixServiceOperation {
            mixSubmitCount += 1
            return operation(id = "mix", state = "queued", stage = "queued")
        }

        override suspend fun submitPgmEdits(
            job: String,
            material: String,
            requestId: String,
            files: List<PgmEditRow>,
        ): MixServiceOperation {
            editSubmitCount += 1
            return operation(id = "edit", state = "queued", stage = "queued").copy(kind = "pgm_edit")
        }

        override suspend fun getOperation(id: String): MixServiceOperation {
            if (id == "edit") allowEditCompletion.await()
            return when (id) {
                "mix" -> operation(id = id, state = "completed", stage = "completed").copy(warning = firstWarning)
                "edit" -> operation(id = id, state = "completed", stage = "completed").copy(kind = "pgm_edit")
                else -> error("Unexpected operation $id")
            }
        }

        override suspend fun listJobOperations(job: String): List<MixServiceOperation> = emptyList()
    }

    private inner class CompletingService(
        private val operationToRead: MixServiceOperation? = null,
    ) : MixOperationService {
        var submitCount = 0
        private var reads = 0

        override suspend fun submitMix(
            job: String,
            material: String,
            name: String,
            programs: List<String>,
            replaceExisting: Boolean,
        ): MixServiceOperation {
            submitCount += 1
            return operation(id = "new", state = "queued", stage = "queued")
        }

        override suspend fun submitPgmEdits(job: String, material: String, requestId: String, files: List<PgmEditRow>): MixServiceOperation =
            error("Not used by this test")

        override suspend fun getOperation(id: String): MixServiceOperation {
            operationToRead?.let { return it }
            reads += 1
            return if (reads == 1) operation(id = id, state = "running", stage = "preparing")
            else operation(id = id, state = "completed", stage = "completed")
        }

        override suspend fun listJobOperations(job: String): List<MixServiceOperation> = emptyList()
    }

    private inner class RetryEditService : MixOperationService {
        var editSubmits = 0
        val allowRetryCompletion = kotlinx.coroutines.CompletableDeferred<Unit>()

        override suspend fun submitMix(
            job: String,
            material: String,
            name: String,
            programs: List<String>,
            replaceExisting: Boolean,
        ): MixServiceOperation = operation(id = "mix", state = "queued", stage = "queued")

        override suspend fun submitPgmEdits(
            job: String,
            material: String,
            requestId: String,
            files: List<PgmEditRow>,
        ): MixServiceOperation {
            editSubmits += 1
            return operation(id = "edit-$editSubmits", state = "queued", stage = "queued").copy(kind = "pgm_edit")
        }

        override suspend fun getOperation(id: String): MixServiceOperation = when (id) {
            "mix" -> operation(id = id, state = "completed", stage = "completed")
            "edit-1" -> operation(id = id, state = "failed", stage = "failed").copy(
                kind = "pgm_edit",
                error = "edit failed",
            )
            "edit-2" -> {
                allowRetryCompletion.await()
                operation(id = id, state = "completed", stage = "completed").copy(kind = "pgm_edit")
            }
            else -> error("Unexpected operation $id")
        }

        override suspend fun listJobOperations(job: String): List<MixServiceOperation> = emptyList()
    }

    private class StatusErrorService : MixOperationService {
        var submitCount = 0

        override suspend fun submitMix(
            job: String,
            material: String,
            name: String,
            programs: List<String>,
            replaceExisting: Boolean,
        ): MixServiceOperation {
            submitCount += 1
            error("must not submit")
        }

        override suspend fun submitPgmEdits(
            job: String,
            material: String,
            requestId: String,
            files: List<PgmEditRow>,
        ): MixServiceOperation {
            submitCount += 1
            error("must not submit")
        }

        override suspend fun getOperation(id: String): MixServiceOperation =
            throw MixOperationClientException("status unavailable")

        override suspend fun listJobOperations(job: String): List<MixServiceOperation> = emptyList()
    }
}
