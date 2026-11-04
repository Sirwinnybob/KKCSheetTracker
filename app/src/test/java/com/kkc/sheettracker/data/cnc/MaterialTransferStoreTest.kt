package com.kkc.sheettracker.data.cnc

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MaterialTransferStoreTest {
    @get:Rule val temp = TemporaryFolder()
    private val red = CncMachines.scm
    private val blue = CncMachines.scmNew
    private val preview = TransferPreview("pv-1", listOf("R1.pgm"), listOf("R2.pgm"), emptyList(), emptyList(), true)

    private class FakeApi : MaterialTransferApi {
        val starts = mutableListOf<Pair<String, String>>()
        val polls = mutableListOf<String>()
        var startResult: TransferResult<TransferOperation> = TransferResult.Ok(TransferOperation("op-1", "preparing", null, null))
        var startGate: CompletableDeferred<Unit>? = null
        var operationResult: TransferResult<TransferOperation> = TransferResult.Ok(TransferOperation("op-1", "staging", null, null))
        override suspend fun preview(request: TransferPreviewRequest) = error("unused")
        override suspend fun start(previewId: String, requestId: String): TransferResult<TransferOperation> {
            starts += previewId to requestId; startGate?.await(); return startResult
        }
        override suspend fun operation(operationId: String): TransferResult<TransferOperation> { polls += operationId; return operationResult }
    }

    private var ids = 0
    private fun store(apis: Map<String, FakeApi>) = MaterialTransferStore(temp.root.resolve("transfers.json"),
        clientFor = { endpoint -> apis.getValue(endpoint) }, now = { 1_000L }, newRequestId = { "req-${++ids}" })

    @Test fun startWritesAheadThenBindsOperationToTheDestinationEndpoint() = runTest {
        val api = FakeApi()
        val store = store(mapOf(blue.baseUrl to api))
        val started = store.start(red, blue, "711", "19mm", preview, "folder-r", PunloadPolicy.REMOVE) as TransferStart.Started
        val record = started.record
        assertEquals(TransferState.SUBMITTED, record.state)
        assertEquals("op-1", record.operationId)
        assertEquals(listOf("pv-1" to "req-1"), api.starts)
        assertEquals(red.id, record.sourceMachineId); assertEquals(red.baseUrl, record.sourceEndpoint)
        assertEquals(blue.id, record.destinationMachineId); assertEquals(blue.baseUrl, record.destinationEndpoint)
        assertEquals("pv-1", record.previewId); assertEquals("folder-r", record.expectedSourceRevision)
        assertEquals("remove", record.punloadPolicy)
    }

    @Test fun duplicateTapsSubmitOnce() = runTest {
        val api = FakeApi().apply { startGate = CompletableDeferred() }
        val store = store(mapOf(blue.baseUrl to api))
        val first = async { store.start(red, blue, "711", "19mm", preview, "r", PunloadPolicy.REMOVE) }
        runCurrent()
        val second = store.start(red, blue, "711", "19mm", preview, "r", PunloadPolicy.REMOVE)
        assertTrue(second is TransferStart.AlreadyActive)
        // The same job/material from the other machine's group is the same physical material.
        assertTrue(store.start(blue, red, "711", "19mm", preview, "r", PunloadPolicy.KEEP_REMOVED) is TransferStart.AlreadyActive)
        api.startGate!!.complete(Unit)
        assertTrue(first.await() is TransferStart.Started)
        assertEquals(1, api.starts.size)
    }

    @Test fun lostReplyIsUncertainAndNeverAutoResubmitted() = runTest {
        val api = FakeApi().apply { startResult = TransferResult.Offline }
        val store = store(mapOf(blue.baseUrl to api))
        val record = (store.start(red, blue, "711", "19mm", preview, "r", PunloadPolicy.REMOVE) as TransferStart.Started).record
        assertEquals(TransferState.UNCERTAIN, record.state)
        repeat(3) { store.refreshActive() }
        assertEquals(1, api.starts.size)
        assertTrue(api.polls.isEmpty())
        assertEquals(TransferState.UNCERTAIN, store.records.value.single().state)
        // Only an explicit operator action resends, with the same request and preview IDs.
        api.startResult = TransferResult.Ok(TransferOperation("op-1", "preparing", null, null))
        store.resendUncertain(record.requestId)
        assertEquals(listOf("pv-1" to "req-1", "pv-1" to "req-1"), api.starts)
        assertEquals(TransferState.SUBMITTED, store.records.value.single().state)
    }

    @Test fun definiteRefusalFailsWithoutPolling() = runTest {
        val api = FakeApi().apply { startResult = TransferResult.Rejected("preview_stale", "Inputs changed") }
        val store = store(mapOf(blue.baseUrl to api))
        val record = (store.start(red, blue, "711", "19mm", preview, "r", PunloadPolicy.REMOVE) as TransferStart.Started).record
        assertEquals(TransferState.FAILED, record.state)
        assertEquals("Inputs changed", record.message)
        assertFalse(record.active)
    }

    @Test fun processRecoveryPollsTheBoundEndpointAndCompletesOnlyWhenVerified() = runTest {
        val blueApi = FakeApi()
        val redApi = FakeApi()
        val apis = mapOf(blue.baseUrl to blueApi, red.baseUrl to redApi)
        store(apis).start(red, blue, "711", "19mm", preview, "r", PunloadPolicy.REMOVE)
        // App restart: a fresh store reads the durable record and resumes on Blue's snapshot endpoint.
        val restored = store(apis)
        assertEquals(TransferState.SUBMITTED, restored.records.value.single().state)
        restored.refreshActive()
        assertEquals(listOf("op-1"), blueApi.polls)
        assertTrue(redApi.polls.isEmpty())
        assertEquals("staging", restored.records.value.single().phase)
        blueApi.operationResult = TransferResult.Offline
        restored.refreshActive()
        assertEquals(TransferState.SUBMITTED, restored.records.value.single().state) // Timeout keeps checking.
        blueApi.operationResult = TransferResult.Ok(TransferOperation("op-1", "activated", "completed", null))
        restored.refreshActive()
        assertEquals(TransferState.COMPLETED, restored.records.value.single().state)
        assertEquals(TransferState.COMPLETED, store(apis).records.value.single().state)
    }

    @Test fun crashDuringSubmitRestoresAsUncertain() = runTest {
        val api = FakeApi().apply { startGate = CompletableDeferred() }
        val store = store(mapOf(blue.baseUrl to api))
        async { store.start(red, blue, "711", "19mm", preview, "r", PunloadPolicy.REMOVE) }
        runCurrent()
        assertEquals(TransferState.SUBMITTING, store.records.value.single().state)
        // The process dies before the reply: the write-ahead record must not be lost or replayed.
        val restored = store(mapOf(blue.baseUrl to api))
        assertEquals(TransferState.UNCERTAIN, restored.records.value.single().state)
        restored.refreshActive()
        assertEquals(1, api.starts.size)
        api.startGate!!.complete(Unit)
    }

    @Test fun failedOutcomeAndDismissal() = runTest {
        val api = FakeApi().apply { operationResult = TransferResult.Ok(TransferOperation("op-1", "staging", "failed", "Destination exists")) }
        val store = store(mapOf(blue.baseUrl to api))
        val record = (store.start(red, blue, "711", "19mm", preview, "r", PunloadPolicy.REMOVE) as TransferStart.Started).record
        assertFalse(store.dismiss(record.requestId)) // Active records cannot be dismissed.
        store.refreshActive()
        assertEquals(TransferState.FAILED, store.records.value.single().state)
        assertEquals("Destination exists", store.records.value.single().message)
        assertTrue(store.dismiss(record.requestId))
        assertTrue(store.records.value.isEmpty())
        assertTrue(store(mapOf(blue.baseUrl to api)).records.value.isEmpty())
    }

    @Test fun corruptJournalIsKeptAsideAndStartsEmpty() {
        val file = temp.root.resolve("transfers.json")
        file.writeText("{bad")
        assertTrue(store(emptyMap()).records.value.isEmpty())
        assertTrue(temp.root.listFiles()!!.any { it.name.startsWith("transfers.json.corrupt") })
    }
}
