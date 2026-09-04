package com.kkc.sheettracker.data.mixservice

import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MixOperationCoordinatorTest {

    @Test
    fun `catalog replace persists revision before submission and advances on success`() = runBlocking {
        val store = InMemorySessionStore()
        val service = CatalogService { submitted ->
            val persisted = store.currentSessions.getValue("648")
            assertEquals(submitted, persisted.currentAction)
            assertEquals("submitting", persisted.current.state)
        }
        val coordinator = MixOperationCoordinator(service, store, pollIntervalMillis = 1)

        coordinator.restore()
        withTimeout(1_000) { coordinator.restoreState.first { it == MixOperationRestoreState.Ready } }
        coordinator.start(
            session(
                actions = listOf(
                    ManageCodeOperationAction.catalogReplace(
                        material = "Maple",
                        name = "Current",
                        programs = listOf("R2.pgm"),
                        expectedRevision = 7L,
                    )
                )
            )
        )

        withTimeout(1_000) { coordinator.sessions.first { it["648"]?.isCompletedSuccessfully == true } }

        assertEquals(7L, store.saved.first().actions.single().expectedRevision)
        assertEquals("648", service.submitted.single().job)
        assertEquals(1, service.submitCount)
        assertTrue(store.saved.any { it.current.state == "submitting" })
        assertTrue(store.saved.any { it.current.state == "completed" })
    }

    @Test
    fun `catalog success persists completion before publishing the shared cache`() = runBlocking {
        val root = Files.createTempDirectory("mix-catalog-operation-cache").toFile()
        try {
            val events = mutableListOf<String>()
            val cache = MixCatalogCache(root, nowMillis = {
                events += "cache-publish"
                1234L
            })
            val store = object : MixOperationSessionStore {
                var sessions: Map<String, ManageCodeSession> = emptyMap()

                override suspend fun load(): MixOperationSessionLoadResult =
                    MixOperationSessionLoadResult.Success(sessions)

                override suspend fun save(next: Map<String, ManageCodeSession>) {
                    sessions = next
                    if (next["648"]?.current?.state == "completed") {
                        events += "durable-completion"
                    }
                }
            }
            val coordinator = MixOperationCoordinator(
                service = CatalogService(),
                store = store,
                pollIntervalMillis = 1,
                catalogPublisher = cache,
            )

            coordinator.restore()
            withTimeout(1_000) { coordinator.restoreState.first { it == MixOperationRestoreState.Ready } }
            coordinator.start(
                session(
                    actions = listOf(
                        ManageCodeOperationAction.catalogReplace(
                            material = "Maple",
                            name = "Current",
                            programs = listOf("R2.pgm"),
                            expectedRevision = 7L,
                        )
                    )
                )
            )

            withTimeout(1_000) { coordinator.sessions.first { it["648"]?.isCompletedSuccessfully == true } }

            assertEquals(listOf("durable-completion", "cache-publish"), events)
            assertEquals(8L, cache.read("648", "Maple")?.revision)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `completed catalog history sync warning advances and is persisted`() = runBlocking {
        val root = Files.createTempDirectory("mix-catalog-sync-failure-cache").toFile()
        val store = InMemorySessionStore()
        val service = CatalogService(
            MixCatalogMutationResult.SyncFailed(
                snapshot = MixCatalogSnapshot(job = "648", material = "M", revision = 8L),
                code = "history_sync_failed",
                recoveryUrl = "/jobs/648/materials/M/mix-history/sync",
                recoveries = listOf(
                    MixOperationRecovery(
                        url = "/jobs/648/materials/M/mix-history/sync",
                        method = "POST",
                        change = mapOf("historyFile" to ".pgm_edit_history.json"),
                    )
                ),
            )
        )
        val cache = MixCatalogCache(root)
        val coordinator = MixOperationCoordinator(
            service = service,
            store = store,
            pollIntervalMillis = 1,
            catalogPublisher = cache,
        )

        coordinator.restore()
        withTimeout(1_000) { coordinator.restoreState.first { it == MixOperationRestoreState.Ready } }
        coordinator.start(
            session(
                actions = listOf(
                    ManageCodeOperationAction.externalDelete(
                        material = "M",
                        externalMixFilename = "Manual.mix",
                        expectedRevision = 7L,
                    )
                )
            )
        )

        val completed = withTimeout(1_000) {
            coordinator.sessions.first { it["648"]?.isCompletedSuccessfully == true }
        }.getValue("648")

        assertEquals("history_sync_failed", completed.warnings.single().code)
        assertEquals(
            "/jobs/648/materials/M/mix-history/sync",
            completed.warnings.single().recoveries.single().url,
        )
        assertEquals("POST", completed.warnings.single().recoveries.single().method)
        assertEquals(
            ".pgm_edit_history.json",
            completed.warnings.single().recoveries.single().change["historyFile"],
        )
        assertTrue(store.saved.any { it.current.state == "completed" && it.warnings.isNotEmpty() })
        assertEquals(8L, cache.read("648", "M")?.revision)
        root.deleteRecursively()
        Unit
    }

    @Test
    fun `catalog action is submitted before following pgm action`() = runBlocking {
        val store = InMemorySessionStore()
        val service = CatalogService()
        val coordinator = MixOperationCoordinator(service, store, pollIntervalMillis = 1)
        val actions = listOf(
            ManageCodeOperationAction.catalogReplace("M", "Current", listOf("R2.pgm"), 7L),
            ManageCodeOperationAction.pgmEdits("M", "request", emptyList()),
        )

        coordinator.restore()
        withTimeout(1_000) { coordinator.restoreState.first { it == MixOperationRestoreState.Ready } }
        coordinator.start(session(actions = actions))
        val activePgm = withTimeout(1_000) {
            coordinator.sessions.first { it["648"]?.current?.kind == "pgm_edit" }
        }.getValue("648")

        assertEquals(listOf("catalog_replace", "pgm_edits"), service.submissionKinds)
        assertEquals("pgm_edit", activePgm.current.kind)
    }

    @Test
    fun `restored unacknowledged catalog submission becomes interrupted without replay`() = runBlocking {
        val pending = session(
            actions = listOf(ManageCodeOperationAction.catalogReplace("M", "Current", listOf("R1.pgm"), 7L)),
            current = operation(state = "submitting", stage = "submitting").copy(kind = "catalog_replace"),
        )
        val store = InMemorySessionStore(mapOf("648" to pending))
        val service = CatalogService()
        val coordinator = MixOperationCoordinator(service, store, pollIntervalMillis = 1)

        coordinator.restore()
        val restored = withTimeout(1_000) {
            coordinator.sessions.first { it["648"]?.current?.state == "interrupted" }
        }.getValue("648")

        assertEquals("interrupted", restored.current.state)
        assertFalse(restored.isCompletedSuccessfully)
        assertEquals(0, service.submitCount)
    }

    @Test
    fun `restore preserves acknowledged catalog failure while interrupting unacknowledged submission`() = runBlocking {
        val failedCatalog = session(
            actions = listOf(
                ManageCodeOperationAction.catalogReplace(
                    job = "648",
                    material = "M",
                    name = "Current",
                    programs = listOf("R1.pgm"),
                    expectedRevision = 7L,
                )
            ),
            current = operation(state = "failed", stage = "failed").copy(
                kind = "catalog_replace",
                error = "catalog_changed",
                result = MixCatalogMutationResult.CatalogChanged,
            ),
        )
        val pendingCatalog = session(
            actions = listOf(
                ManageCodeOperationAction.catalogReplace(
                    job = "649",
                    material = "M",
                    name = "Current",
                    programs = listOf("R1.pgm"),
                    expectedRevision = 7L,
                )
            ),
            current = operation(state = "submitting", stage = "submitting").copy(
                job = "649",
                kind = "catalog_replace",
            ),
        ).copy(job = "649")
        val store = InMemorySessionStore(
            mapOf("648" to failedCatalog, "649" to pendingCatalog)
        )
        val service = CatalogService()
        val coordinator = MixOperationCoordinator(service, store, pollIntervalMillis = 1)

        coordinator.restore()
        val restored = withTimeout(1_000) {
            coordinator.sessions.first { sessions ->
                sessions["648"]?.current?.state == "failed" &&
                    sessions["649"]?.current?.state == "interrupted"
            }
        }

        val failed = restored.getValue("648")
        assertEquals("failed", failed.current.state)
        assertEquals("catalog_changed", failed.current.error)
        assertEquals(MixCatalogMutationResult.CatalogChanged, failed.current.result)
        assertEquals("interrupted", restored.getValue("649").current.state)
        assertEquals(0, service.submitCount)
    }

    @Test
    fun `catalog failure is durably mapped and does not advance to the next action`() = runBlocking {
        val store = InMemorySessionStore()
        val service = CatalogService(MixCatalogMutationResult.CatalogChanged)
        val coordinator = MixOperationCoordinator(service, store, pollIntervalMillis = 1)

        coordinator.restore()
        withTimeout(1_000) { coordinator.restoreState.first { it == MixOperationRestoreState.Ready } }
        coordinator.start(
            session(
                actions = listOf(
                    ManageCodeOperationAction.catalogReplace("M", "Current", listOf("R1.pgm"), 7L),
                    ManageCodeOperationAction.pgmEdits("M", "request", emptyList()),
                )
            )
        )

        val failed = withTimeout(1_000) {
            coordinator.sessions.first { it["648"]?.current?.state == "failed" }
        }.getValue("648")

        assertEquals(0, failed.currentActionIndex)
        assertEquals("catalog_changed", failed.current.error)
        assertEquals(1, service.submitCount)
    }

    @Test
    fun `replacing catalog changed session persists fresh actions before submitting`() = runBlocking {
        val failed = session(
            actions = listOf(
                ManageCodeOperationAction.catalogReplace(
                    job = "648",
                    material = "M",
                    name = "Current",
                    programs = listOf("R1.pgm"),
                    expectedRevision = 7L,
                ),
            ),
            current = operation(state = "failed", stage = "failed").copy(
                kind = ManageCodeOperationAction.CATALOG_REPLACE,
                error = "catalog_changed",
                result = MixCatalogMutationResult.CatalogChanged,
            ),
        )
        val store = InMemorySessionStore(mapOf("648" to failed))
        val service = CatalogService { submitted ->
            val persisted = store.currentSessions.getValue("648")
            assertEquals("submitting", persisted.current.state)
            assertEquals(submitted, persisted.currentAction)
            assertEquals(9L, persisted.currentAction?.expectedRevision)
            assertEquals("new-request", persisted.actions.getOrNull(1)?.requestId)
        }
        val coordinator = MixOperationCoordinator(service, store, pollIntervalMillis = 1)

        coordinator.restore()
        withTimeout(1_000) { coordinator.restoreState.first { it == MixOperationRestoreState.Ready } }

        val replacement = ManageCodeSession(
            job = "648",
            actions = listOf(
                ManageCodeOperationAction.catalogReplace(
                    job = "648",
                    material = "M",
                    name = "Current",
                    programs = listOf("R2.pgm"),
                    expectedRevision = 9L,
                ),
                ManageCodeOperationAction.pgmEdits(
                    material = "M",
                    requestId = "new-request",
                    editRows = listOf(PgmEditRow("R2.pgm", "standard", removePUnload = false)),
                ),
            ),
        )

        assertTrue(coordinator.replaceCatalogChangedSession(replacement))

        val completed = withTimeout(1_000) {
            coordinator.sessions.first { it["648"]?.isCompletedSuccessfully == true }
        }.getValue("648")

        assertEquals(listOf("catalog_replace", "pgm_edits"), service.submissionKinds)
        assertEquals(9L, service.submitted.single().expectedRevision)
        assertEquals("new-request", completed.actions[1].requestId)
        assertTrue(store.saved.indexOfFirst { it === replacement } >= 0)
    }

    @Test
    fun `replacing catalog changed session rejects invalid existing states without submitting`() = runBlocking {
        val invalidStates = listOf(
            operation(state = "failed", stage = "failed").copy(
                kind = ManageCodeOperationAction.CATALOG_REPLACE,
                error = "network_error",
            ),
            operation(state = "interrupted", stage = "interrupted").copy(
                kind = ManageCodeOperationAction.CATALOG_REPLACE,
                error = "catalog_changed",
            ),
            operation(state = "running", stage = "compiling").copy(
                kind = ManageCodeOperationAction.CATALOG_REPLACE,
                error = "catalog_changed",
            ),
            operation(state = "completed", stage = "completed").copy(
                kind = ManageCodeOperationAction.CATALOG_REPLACE,
                error = "catalog_changed",
            ),
        )

        invalidStates.forEach { invalidCurrent ->
            val store = InMemorySessionStore(
                mapOf(
                    "648" to session(
                        actions = listOf(
                            ManageCodeOperationAction.catalogReplace(
                                job = "648",
                                material = "M",
                                name = "Current",
                                programs = listOf("R1.pgm"),
                                expectedRevision = 7L,
                            ),
                        ),
                        current = invalidCurrent,
                    )
                )
            )
            val service = CatalogService()
            val coordinator = MixOperationCoordinator(service, store, pollIntervalMillis = 1)
            coordinator.restore()
            withTimeout(1_000) { coordinator.restoreState.first { it == MixOperationRestoreState.Ready } }

            assertFalse(
                coordinator.replaceCatalogChangedSession(
                    ManageCodeSession(
                        job = "648",
                        actions = listOf(
                            ManageCodeOperationAction.catalogReplace(
                                job = "648",
                                material = "M",
                                name = "Current",
                                programs = listOf("R2.pgm"),
                                expectedRevision = 9L,
                            ),
                        ),
                    )
                )
            )
            assertEquals(0, service.submitCount)
        }
    }

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
        actions: List<ManageCodeOperationAction> = listOf(
            ManageCodeOperationAction(
                kind = ManageCodeOperationAction.MIX,
                material = "M",
                name = "Mix",
                programs = listOf("R1.pgm"),
                operationId = operationId,
            )
        ),
    ) = ManageCodeSession(
        job = "648",
        actions = actions.map { if (it.operationId == null && operationId != null) it.copy(operationId = operationId) else it },
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

    private inner class CatalogService(
        private val result: MixCatalogMutationResult = MixCatalogMutationResult.Success(MixCatalogSnapshot()),
        private val onCatalogSubmit: (ManageCodeOperationAction) -> Unit = {},
    ) : MixOperationService {
        var submitCount = 0
        val submitted = mutableListOf<ManageCodeOperationAction>()
        val submissionKinds = mutableListOf<String>()

        override suspend fun submitCatalogMutation(action: ManageCodeOperationAction): MixCatalogMutationResult {
            onCatalogSubmit(action)
            submitCount += 1
            submitted += action
            submissionKinds += action.kind
            return when (val mutation = result) {
                is MixCatalogMutationResult.Success -> mutation.copy(
                    snapshot = mutation.snapshot.copy(
                        job = action.job,
                        material = action.material,
                        revision = action.expectedRevision + 1,
                    )
                )
                else -> mutation
            }
        }

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
            submissionKinds += "pgm_edits"
            return operation(id = "edit", state = "queued", stage = "queued").copy(kind = "pgm_edit")
        }

        override suspend fun getOperation(id: String): MixServiceOperation =
            operation(id = id, state = "completed", stage = "completed").copy(kind = "pgm_edit")

        override suspend fun listJobOperations(job: String): List<MixServiceOperation> = emptyList()
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

        override suspend fun submitCatalogMutation(action: ManageCodeOperationAction): MixCatalogMutationResult =
            error("Not used by this test")

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

        override suspend fun submitCatalogMutation(action: ManageCodeOperationAction): MixCatalogMutationResult =
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

        override suspend fun submitCatalogMutation(action: ManageCodeOperationAction): MixCatalogMutationResult =
            error("Not used by this test")

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

        override suspend fun submitCatalogMutation(action: ManageCodeOperationAction): MixCatalogMutationResult {
            submitCount += 1
            error("must not submit")
        }

        override suspend fun getOperation(id: String): MixServiceOperation =
            throw MixOperationClientException("status unavailable")

        override suspend fun listJobOperations(job: String): List<MixServiceOperation> = emptyList()
    }
}
