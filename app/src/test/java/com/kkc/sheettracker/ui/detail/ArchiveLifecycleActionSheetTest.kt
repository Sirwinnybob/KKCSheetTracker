package com.kkc.sheettracker.ui.detail

import com.kkc.sheettracker.data.ArchiveLifecycleClient
import com.kkc.sheettracker.data.OperationStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchiveLifecycleActionSheetTest {

    @Test
    fun `archive action is hidden outside admin mode`() {
        assertFalse(archiveActionVisible(adminEnabled = false, sourceIsLive = true))
    }

    @Test
    fun `admin archive request always uses fail collision choice`() = runBlocking {
        val client = RecordingArchiveClient()

        submitArchive(client, "100 - Alpha", "tablet-7")

        assertEquals("fail", client.lastCollisionChoice)
    }

    @Test
    fun `failed operation leaves live job visible and shows server error`() = runBlocking {
        assertEquals(
            LifecycleUiState.Failed("destination folder already exists"),
            reduceOperation("failed", "destination folder already exists"),
        )
    }

    @Test
    fun `failed operation bounds the server error`() {
        val longError = "x".repeat(300)

        val state = reduceOperation("failed", longError)

        assertEquals(LifecycleUiState.Failed("${"x".repeat(159)}…"), state)
    }

    @Test
    fun `accepted archive request cannot be dismissed before a terminal state`() {
        assertFalse(lifecycleSheetDismissible(LifecycleUiState.Queued))
        assertFalse(lifecycleSheetDismissible(LifecycleUiState.Working))
        assertTrue(lifecycleSheetDismissible(LifecycleUiState.Failed("server rejected request")))
    }

    @Test
    fun `pending archive request disables sheet gestures`() {
        assertFalse(lifecycleSheetGesturesEnabled(LifecycleUiState.Queued))
        assertFalse(lifecycleSheetGesturesEnabled(LifecycleUiState.Working))
        assertTrue(lifecycleSheetGesturesEnabled(LifecycleUiState.Confirming))
    }

    @Test
    fun `pending archive sheet remains visible after admin mode is disabled`() {
        assertFalse(lifecycleSheetVisible(adminEnabled = false, state = LifecycleUiState.Confirming))
        assertTrue(lifecycleSheetVisible(adminEnabled = false, state = LifecycleUiState.Queued))
        assertTrue(lifecycleSheetVisible(adminEnabled = false, state = LifecycleUiState.Working))
    }

    @Test
    fun `cancelled operation becomes terminal failure without completion`() = runBlocking {
        val client = ScriptedArchiveClient(statuses = listOf(OperationStatus("op-123", "cancelled", null)))
        val states = mutableListOf<LifecycleUiState>()
        var completions = 0

        runArchiveLifecycle(
            clientFactory = { client },
            folderName = "100 - Alpha",
            initiator = "tablet-7",
            onState = { nextState -> states.add(nextState) },
            onCompleted = { completions += 1 },
            pollDelay = {},
        )

        assertEquals(LifecycleUiState.Failed("The archive request was cancelled."), states.last())
        assertEquals(0, completions)
        assertEquals(1, client.statusCalls)
    }

    @Test
    fun `unknown operation state becomes terminal failure without completion`() = runBlocking {
        val client = ScriptedArchiveClient(statuses = listOf(OperationStatus("op-123", "paused", null)))
        val states = mutableListOf<LifecycleUiState>()
        var completions = 0

        runArchiveLifecycle(
            clientFactory = { client },
            folderName = "100 - Alpha",
            initiator = "tablet-7",
            onState = { nextState -> states.add(nextState) },
            onCompleted = { completions += 1 },
            pollDelay = {},
        )

        assertEquals(LifecycleUiState.Failed("The archive request returned an unknown status."), states.last())
        assertEquals(0, completions)
        assertEquals(1, client.statusCalls)
    }

    @Test
    fun `successful operation completes only after succeeded`() = runBlocking {
        val client = ScriptedArchiveClient(
            statuses = listOf(
                OperationStatus("op-123", "working", null),
                OperationStatus("op-123", "succeeded", null),
            ),
        )
        val states = mutableListOf<LifecycleUiState>()
        var completions = 0

        runArchiveLifecycle(
            clientFactory = { client },
            folderName = "100 - Alpha",
            initiator = "tablet-7",
            onState = { nextState -> states.add(nextState) },
            onCompleted = {
                assertEquals(LifecycleUiState.Completed, states.last())
                completions += 1
            },
            pollDelay = {},
        )

        assertEquals(
            listOf(LifecycleUiState.Queued, LifecycleUiState.Working, LifecycleUiState.Completed),
            states,
        )
        assertEquals(1, completions)
    }

    @Test
    fun `client factory exception becomes terminal failure`() = runBlocking {
        val states = mutableListOf<LifecycleUiState>()

        runArchiveLifecycle(
            clientFactory = { error("network config unavailable") },
            folderName = "100 - Alpha",
            initiator = "tablet-7",
            onState = { nextState -> states.add(nextState) },
            onCompleted = {},
            pollDelay = {},
        )

        assertEquals(LifecycleUiState.Failed("Unable to start the archive request."), states.last())
    }

    @Test
    fun `archive trigger exception becomes terminal failure`() = runBlocking {
        val states = mutableListOf<LifecycleUiState>()
        val client = ScriptedArchiveClient(triggerFailure = IllegalStateException("trigger unavailable"))

        runArchiveLifecycle(
            clientFactory = { client },
            folderName = "100 - Alpha",
            initiator = "tablet-7",
            onState = { nextState -> states.add(nextState) },
            onCompleted = {},
            pollDelay = {},
        )

        assertEquals(LifecycleUiState.Failed("Unable to start the archive request."), states.last())
    }

    @Test
    fun `operation status exception becomes terminal failure`() = runBlocking {
        val states = mutableListOf<LifecycleUiState>()
        val client = ScriptedArchiveClient(statusFailure = IllegalStateException("status unavailable"))

        runArchiveLifecycle(
            clientFactory = { client },
            folderName = "100 - Alpha",
            initiator = "tablet-7",
            onState = { nextState -> states.add(nextState) },
            onCompleted = {},
            pollDelay = {},
        )

        assertEquals(LifecycleUiState.Failed("Unable to check the archive request."), states.last())
    }

    private class RecordingArchiveClient : ArchiveLifecycleClient {
        var lastCollisionChoice: String? = null

        override suspend fun triggerArchive(folderName: String, initiator: String): String? {
            lastCollisionChoice = "fail"
            return "op-123"
        }

        override suspend fun getOperationStatus(operationId: String): OperationStatus? = null
    }

    private class ScriptedArchiveClient(
        private val statuses: List<OperationStatus> = emptyList(),
        private val triggerFailure: Throwable? = null,
        private val statusFailure: Throwable? = null,
    ) : ArchiveLifecycleClient {
        var statusCalls = 0

        override suspend fun triggerArchive(folderName: String, initiator: String): String? {
            triggerFailure?.let { throw it }
            return "op-123"
        }

        override suspend fun getOperationStatus(operationId: String): OperationStatus? {
            statusCalls += 1
            statusFailure?.let { throw it }
            return statuses.getOrNull(statusCalls - 1)
        }
    }
}
