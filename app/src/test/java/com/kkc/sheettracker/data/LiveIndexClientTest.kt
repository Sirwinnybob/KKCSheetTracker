package com.kkc.sheettracker.data

import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

class LiveIndexClientTest {

    @Test
    fun `backoff doubles from 1s and caps at 30s`() {
        assertEquals(1_000L, nextBackoffDelayMs(0))
        assertEquals(2_000L, nextBackoffDelayMs(1))
        assertEquals(4_000L, nextBackoffDelayMs(2))
        assertEquals(8_000L, nextBackoffDelayMs(3))
        assertEquals(16_000L, nextBackoffDelayMs(4))
        assertEquals(30_000L, nextBackoffDelayMs(5))
        assertEquals(30_000L, nextBackoffDelayMs(6))
        assertEquals(30_000L, nextBackoffDelayMs(100))
    }
}

class LiveIndexEnvelopeParsingTest {

    private val gson = Gson()

    @Test
    fun `parses a snapshot envelope`() {
        val json = """
            {"type":"snapshot","serverInstanceId":"abc123","revision":7,
             "jobs":{"1234 - Test Job":{"jobInfo":{"folderName":"1234 - Test Job",
             "jobNumber":"1234","jobName":"Test Job","hiddenFromProduction":false,
             "lineupPosition":2},"progressSummary":{"cnc":null,"hardwoods":null,
             "hasDeliverySheet":false,"has3DAssets":false}}}}
        """.trimIndent()

        val envelope = gson.fromJson(json, LiveIndexEnvelope::class.java)

        assertEquals("snapshot", envelope.type)
        assertEquals(7L, envelope.revision)
        val job = envelope.jobs?.get("1234 - Test Job")
        assertEquals("1234", job?.jobInfo?.jobNumber)
    }

    @Test
    fun `parses an upsert delta envelope`() {
        val json = """
            {"type":"delta","delta":{"type":"upsert","folderName":"1234 - Test Job",
             "revision":8,"index":{"jobInfo":{"folderName":"1234 - Test Job",
             "jobNumber":"1234","jobName":"Test Job","hiddenFromProduction":false,
             "lineupPosition":2},"progressSummary":null}}}
        """.trimIndent()

        val envelope = gson.fromJson(json, LiveIndexEnvelope::class.java)

        assertEquals("delta", envelope.type)
        assertEquals("upsert", envelope.delta?.type)
        assertEquals("1234 - Test Job", envelope.delta?.folderName)
        assertEquals("1234", envelope.delta?.index?.jobInfo?.jobNumber)
    }

    @Test
    fun `parses a remove delta envelope with a null index`() {
        val json = """{"type":"delta","delta":{"type":"remove","folderName":"1234 - Test Job","revision":9,"index":null}}"""

        val envelope = gson.fromJson(json, LiveIndexEnvelope::class.java)

        assertEquals("remove", envelope.delta?.type)
        assertEquals(null, envelope.delta?.index)
    }

    @Test
    fun `parses not_running and error envelopes`() {
        val notRunning = gson.fromJson("""{"type":"not_running"}""", LiveIndexEnvelope::class.java)
        assertEquals("not_running", notRunning.type)

        val error = gson.fromJson("""{"type":"error","message":"expected a hello message first"}""", LiveIndexEnvelope::class.java)
        assertEquals("error", error.type)
        assertEquals("expected a hello message first", error.message)
    }
}

class LiveIndexClientDispatchTest {

    private fun configWithIp(ip: String): AdminSyncConfig = mock {
        onBlocking { getManualIp() } doReturn ip
    }

    @Test
    fun `sends a hello frame with the tablet id on open`() = runBlocking {
        val fakeSocket = mock<WebSocket>()
        val capturedListener = AtomicReference<WebSocketListener>()
        val client = LiveIndexClient(
            config = configWithIp("192.168.1.15"),
            tabletId = "tablet-7",
            onSnapshot = {},
            onDelta = { _, _ -> },
            onConnectionState = {},
            webSocketFactory = { _, listener -> capturedListener.set(listener); fakeSocket }
        )

        client.start()
        waitUntil { capturedListener.get() != null }
        capturedListener.get().onOpen(fakeSocket, mock())

        verify(fakeSocket).send("""{"type":"hello","tabletId":"tablet-7"}""")
        client.stop()
    }

    @Test
    fun `dispatches a snapshot to onSnapshot and reports connected`() = runBlocking {
        val fakeSocket = mock<WebSocket>()
        val capturedListener = AtomicReference<WebSocketListener>()
        val snapshots = CopyOnWriteArrayList<Map<String, com.kkc.sheettracker.data.models.CacheIndexRoot>>()
        val connectionStates = CopyOnWriteArrayList<Boolean>()
        val client = LiveIndexClient(
            config = configWithIp("192.168.1.15"),
            tabletId = "tablet-7",
            onSnapshot = { snapshots.add(it) },
            onDelta = { _, _ -> },
            onConnectionState = { connectionStates.add(it) },
            webSocketFactory = { _, listener -> capturedListener.set(listener); fakeSocket }
        )

        client.start()
        waitUntil { capturedListener.get() != null }
        capturedListener.get().onMessage(fakeSocket, """{"type":"snapshot","serverInstanceId":"abc","revision":1,"jobs":{}}""")

        assertTrue(snapshots.isNotEmpty())
        assertTrue(connectionStates.contains(true))
        client.stop()
    }

    @Test
    fun `dispatches upsert and remove deltas to onDelta`() = runBlocking {
        val fakeSocket = mock<WebSocket>()
        val capturedListener = AtomicReference<WebSocketListener>()
        val deltas = CopyOnWriteArrayList<Pair<String, com.kkc.sheettracker.data.models.CacheIndexRoot?>>()
        val client = LiveIndexClient(
            config = configWithIp("192.168.1.15"),
            tabletId = "tablet-7",
            onSnapshot = {},
            onDelta = { folder, index -> deltas.add(folder to index) },
            onConnectionState = {},
            webSocketFactory = { _, listener -> capturedListener.set(listener); fakeSocket }
        )

        client.start()
        waitUntil { capturedListener.get() != null }
        capturedListener.get().onMessage(
            fakeSocket,
            """{"type":"delta","delta":{"type":"upsert","folderName":"1234 - Job","revision":2,"index":{"jobInfo":{"folderName":"1234 - Job","jobNumber":"1234","jobName":"Job","hiddenFromProduction":false,"lineupPosition":null},"progressSummary":null}}}"""
        )
        capturedListener.get().onMessage(
            fakeSocket,
            """{"type":"delta","delta":{"type":"remove","folderName":"1234 - Job","revision":3,"index":null}}"""
        )

        assertEquals(2, deltas.size)
        assertEquals("1234 - Job", deltas[0].first)
        assertTrue(deltas[0].second != null)
        assertNull(deltas[1].second)
        client.stop()
    }

    @Test
    fun `not_running reports disconnected without a delta`() = runBlocking {
        val fakeSocket = mock<WebSocket>()
        val capturedListener = AtomicReference<WebSocketListener>()
        val connectionStates = CopyOnWriteArrayList<Boolean>()
        val client = LiveIndexClient(
            config = configWithIp("192.168.1.15"),
            tabletId = "tablet-7",
            onSnapshot = {},
            onDelta = { _, _ -> },
            onConnectionState = { connectionStates.add(it) },
            webSocketFactory = { _, listener -> capturedListener.set(listener); fakeSocket }
        )

        client.start()
        waitUntil { capturedListener.get() != null }
        capturedListener.get().onMessage(fakeSocket, """{"type":"not_running"}""")

        assertTrue(connectionStates.contains(false))
        client.stop()
    }

    private fun waitUntil(timeoutMs: Long = 2_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(20L)
        }
        throw AssertionError("Timed out waiting for condition")
    }
}

class LiveIndexClientReconnectTest {

    private fun configWithIp(ip: String): AdminSyncConfig = mock {
        onBlocking { getManualIp() } doReturn ip
    }

    @Test
    fun `onFailure schedules a reconnect that calls the factory again`() = runBlocking {
        val fakeSocket = mock<WebSocket>()
        val listeners = CopyOnWriteArrayList<WebSocketListener>()
        val connectAttempts = java.util.concurrent.atomic.AtomicInteger(0)
        val client = LiveIndexClient(
            config = configWithIp("192.168.1.15"),
            tabletId = "tablet-7",
            onSnapshot = {},
            onDelta = { _, _ -> },
            onConnectionState = {},
            reconnectDelayMs = { 0L },
            webSocketFactory = { _, listener -> connectAttempts.incrementAndGet(); listeners.add(listener); fakeSocket }
        )

        client.start()
        waitUntil { listeners.size == 1 }
        listeners[0].onFailure(fakeSocket, RuntimeException("boom"), null)

        waitUntil { connectAttempts.get() >= 2 }
        client.stop()
    }

    @Test
    fun `backoff attempt counter resets after a successful snapshot`() = runBlocking {
        val fakeSocket = mock<WebSocket>()
        val listeners = CopyOnWriteArrayList<WebSocketListener>()
        val recordedAttempts = CopyOnWriteArrayList<Int>()
        val client = LiveIndexClient(
            config = configWithIp("192.168.1.15"),
            tabletId = "tablet-7",
            onSnapshot = {},
            onDelta = { _, _ -> },
            onConnectionState = {},
            reconnectDelayMs = { attempt -> recordedAttempts.add(attempt); 0L },
            webSocketFactory = { _, listener -> listeners.add(listener); fakeSocket }
        )

        client.start()
        waitUntil { listeners.size == 1 }
        listeners[0].onFailure(fakeSocket, RuntimeException("first failure"), null)
        waitUntil { listeners.size == 2 }
        listeners[1].onMessage(fakeSocket, """{"type":"snapshot","serverInstanceId":"abc","revision":1,"jobs":{}}""")
        listeners[1].onFailure(fakeSocket, RuntimeException("second failure"), null)

        waitUntil { recordedAttempts.size >= 2 }
        assertEquals(0, recordedAttempts[0]) // first failure: attempt was 0
        assertEquals(0, recordedAttempts[1]) // reset by the snapshot before the second failure
        client.stop()
    }

    @Test
    fun `duplicate onFailure on the same listener schedules only one reconnect`() = runBlocking {
        val fakeSocket = mock<WebSocket>()
        val listeners = CopyOnWriteArrayList<WebSocketListener>()
        val scheduleCount = java.util.concurrent.atomic.AtomicInteger(0)
        val client = LiveIndexClient(
            config = configWithIp("192.168.1.15"),
            tabletId = "tablet-7",
            onSnapshot = {},
            onDelta = { _, _ -> },
            onConnectionState = {},
            reconnectDelayMs = { attempt -> scheduleCount.incrementAndGet(); 100L },
            webSocketFactory = { _, listener -> listeners.add(listener); fakeSocket }
        )

        client.start()
        waitUntil { listeners.size == 1 }
        // Simulate a stray duplicate callback firing on the same (stale) listener instance.
        listeners[0].onFailure(fakeSocket, RuntimeException("first"), null)
        listeners[0].onFailure(fakeSocket, RuntimeException("second"), null)

        Thread.sleep(300L) // longer than the 100ms reconnect delay so the pending job would have fired
        assertEquals(1, scheduleCount.get())
        assertEquals(2, listeners.size) // exactly one reconnect actually happened
        client.stop()
    }

    @Test
    fun `stop cancels a pending reconnect`() = runBlocking {
        val fakeSocket = mock<WebSocket>()
        val listeners = CopyOnWriteArrayList<WebSocketListener>()
        val connectAttempts = java.util.concurrent.atomic.AtomicInteger(0)
        val client = LiveIndexClient(
            config = configWithIp("192.168.1.15"),
            tabletId = "tablet-7",
            onSnapshot = {},
            onDelta = { _, _ -> },
            onConnectionState = {},
            reconnectDelayMs = { 200L },
            webSocketFactory = { _, listener -> connectAttempts.incrementAndGet(); listeners.add(listener); fakeSocket }
        )

        client.start()
        waitUntil { listeners.size == 1 }
        listeners[0].onFailure(fakeSocket, RuntimeException("boom"), null)
        client.stop()

        Thread.sleep(400L) // longer than the 200ms reconnect delay
        assertEquals(1, connectAttempts.get())
    }

    private fun waitUntil(timeoutMs: Long = 2_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(20L)
        }
        throw AssertionError("Timed out waiting for condition")
    }
}
