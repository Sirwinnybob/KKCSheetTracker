package com.kkc.sheettracker.data

import com.kkc.sheettracker.data.models.ArchiveJobEntry
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

private fun waitUntil(timeoutMs: Long = 2_000L, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        if (condition()) return
        Thread.sleep(20L)
    }
    throw AssertionError("Timed out waiting for condition")
}

private fun configWithIp(ip: String): AdminSyncConfig = mock {
    onBlocking { getManualIp() } doReturn ip
}

class ArchiveLibraryClientTest {

    @Test
    fun `hello is sent on open with the given tabletId`() {
        val sentFrames = CopyOnWriteArrayList<String>()
        val fakeSocket = mock<WebSocket> {
            on { send(org.mockito.kotlin.any<String>()) } doAnswer { invocation ->
                sentFrames.add(invocation.getArgument(0)); true
            }
        }
        val listenerRef = AtomicReference<WebSocketListener>()
        val client = ArchiveLibraryClient(
            config = configWithIp("192.168.1.15"),
            tabletId = "tablet-7",
            onSnapshot = {},
            onDelta = { _, _ -> },
            onConnectionState = {},
            webSocketFactory = { _, listener -> listenerRef.set(listener); fakeSocket }
        )
        client.start()
        waitUntil { listenerRef.get() != null }
        listenerRef.get().onOpen(fakeSocket, mock())
        waitUntil { sentFrames.isNotEmpty() }
        assertTrue(sentFrames[0].contains("\"type\":\"hello\""))
        assertTrue(sentFrames[0].contains("tablet-7"))
        client.stop()
    }

    @Test
    fun `snapshot frame delivers all entries via onSnapshot`() {
        val fakeSocket = mock<WebSocket>()
        val listenerRef = AtomicReference<WebSocketListener>()
        var delivered: Map<String, ArchiveJobEntry>? = null
        val client = ArchiveLibraryClient(
            config = configWithIp("192.168.1.15"),
            tabletId = "tablet-7",
            onSnapshot = { delivered = it },
            onDelta = { _, _ -> },
            onConnectionState = {},
            webSocketFactory = { _, listener -> listenerRef.set(listener); fakeSocket }
        )
        client.start()
        waitUntil { listenerRef.get() != null }
        listenerRef.get().onMessage(
            fakeSocket,
            """{"type":"snapshot","serverInstanceId":"s1","revision":1,"archives":{"100 - Alpha":{"archiveJobId":"100 - Alpha","folderName":"100 - Alpha","jobNumber":"100","jobName":"Alpha","archivedAt":"2026-08-19T00:00:00Z","contentVersion":"v1"}}}"""
        )
        waitUntil { delivered != null }
        assertEquals("100", delivered!!["100 - Alpha"]!!.jobNumber)
        client.stop()
    }

    @Test
    fun `delta frame with entry delivers upsert via onDelta`() {
        val fakeSocket = mock<WebSocket>()
        val listenerRef = AtomicReference<WebSocketListener>()
        var deliveredId: String? = null
        var deliveredEntry: ArchiveJobEntry? = null
        val client = ArchiveLibraryClient(
            config = configWithIp("192.168.1.15"),
            tabletId = "tablet-7",
            onSnapshot = {},
            onDelta = { id, entry -> deliveredId = id; deliveredEntry = entry },
            onConnectionState = {},
            webSocketFactory = { _, listener -> listenerRef.set(listener); fakeSocket }
        )
        client.start()
        waitUntil { listenerRef.get() != null }
        listenerRef.get().onMessage(
            fakeSocket,
            """{"type":"delta","delta":{"type":"upsert","archiveJobId":"100 - Alpha","revision":2,"entry":{"archiveJobId":"100 - Alpha","folderName":"100 - Alpha","jobNumber":"100","jobName":"Alpha","archivedAt":"2026-08-19T00:00:00Z","contentVersion":"v1"}}}"""
        )
        waitUntil { deliveredId != null }
        assertEquals("100 - Alpha", deliveredId)
        assertEquals("100", deliveredEntry!!.jobNumber)
        client.stop()
    }

    @Test
    fun `delta frame with null entry delivers removal via onDelta`() {
        val fakeSocket = mock<WebSocket>()
        val listenerRef = AtomicReference<WebSocketListener>()
        var deliveredId: String? = null
        var deliveredEntry: ArchiveJobEntry? = ArchiveJobEntry("x", "x", "x", "x", "x", "x")
        val client = ArchiveLibraryClient(
            config = configWithIp("192.168.1.15"),
            tabletId = "tablet-7",
            onSnapshot = {},
            onDelta = { id, entry -> deliveredId = id; deliveredEntry = entry },
            onConnectionState = {},
            webSocketFactory = { _, listener -> listenerRef.set(listener); fakeSocket }
        )
        client.start()
        waitUntil { listenerRef.get() != null }
        listenerRef.get().onMessage(
            fakeSocket,
            """{"type":"delta","delta":{"type":"remove","archiveJobId":"100 - Alpha","revision":3,"entry":null}}"""
        )
        waitUntil { deliveredId != null }
        assertEquals("100 - Alpha", deliveredId)
        assertEquals(null, deliveredEntry)
        client.stop()
    }

    @Test
    fun `onFailure reports disconnected and schedules a reconnect`() {
        val fakeSocket = mock<WebSocket>()
        val listeners = CopyOnWriteArrayList<WebSocketListener>()
        var connected: Boolean? = null
        val client = ArchiveLibraryClient(
            config = configWithIp("192.168.1.15"),
            tabletId = "tablet-7",
            onSnapshot = {},
            onDelta = { _, _ -> },
            onConnectionState = { connected = it },
            reconnectDelayMs = { 50L },
            webSocketFactory = { _, listener -> listeners.add(listener); fakeSocket }
        )
        client.start()
        waitUntil { listeners.size == 1 }
        listeners[0].onFailure(fakeSocket, RuntimeException("boom"), null)
        waitUntil { connected == false }
        waitUntil { listeners.size == 2 }
        client.stop()
    }
}
