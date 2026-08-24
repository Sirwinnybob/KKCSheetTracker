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

    private class RecordingArchiveClient : ArchiveLifecycleClient {
        var lastCollisionChoice: String? = null

        override suspend fun triggerArchive(folderName: String, initiator: String): String? {
            lastCollisionChoice = "fail"
            return "op-123"
        }

        override suspend fun getOperationStatus(operationId: String): OperationStatus? = null
    }
}
