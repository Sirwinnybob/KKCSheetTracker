package com.kkc.sheettracker.data

import com.kkc.sheettracker.data.models.DeliverySchedule
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class DeliveryScheduleLiveClientTest {

    @Test
    fun `connects to delivery live URL and sends hello with tablet id`() = runBlocking {
        val fakeSocket = mock<WebSocket>()
        val capturedRequest = AtomicReference<Request>()
        val capturedListener = AtomicReference<WebSocketListener>()
        val client = client(
            fakeSocket = fakeSocket,
            capturedRequest = capturedRequest,
            capturedListener = capturedListener
        )

        client.start()
        waitUntil { capturedListener.get() != null }
        capturedListener.get().onOpen(fakeSocket, mock())

        assertEquals(
            // OkHttp normalizes ws:// requests to http:// before handing them to the WebSocket
            // transport; the path, host, and port identify the exact upgrade endpoint.
            "http://192.168.1.15:47821/api/delivery-schedule/live",
            capturedRequest.get().url.toString()
        )
        verify(fakeSocket).send("""{"type":"hello","tabletId":"tablet-7"}""")
        client.stop()
    }

    @Test
    fun `dispatches valid snapshot and reports connected`() = runBlocking {
        val fakeSocket = mock<WebSocket>()
        val capturedListener = AtomicReference<WebSocketListener>()
        val schedules = CopyOnWriteArrayList<DeliverySchedule>()
        val connectionStates = CopyOnWriteArrayList<Boolean>()
        val client = client(
            fakeSocket = fakeSocket,
            capturedListener = capturedListener,
            onSchedule = { schedules.add(it) },
            onConnectionState = { connectionStates.add(it) }
        )

        client.start()
        waitUntil { capturedListener.get() != null }
        capturedListener.get().onMessage(
            fakeSocket,
            """{"type":"snapshot","revision":1,"schedule":{"schemaVersion":1,"slots":{"friday_am":{"jobs":[{"jobNumber":"588","description":"KB GARDENIA WAY"}]}}}}"""
        )

        assertEquals("588", schedules.single().slot("friday", "am").jobs.single().jobNumber)
        assertEquals(listOf(true), connectionStates)
        client.stop()
    }

    @Test
    fun `dispatches valid schedule frames without changing connection state`() = runBlocking {
        val fakeSocket = mock<WebSocket>()
        val capturedListener = AtomicReference<WebSocketListener>()
        val schedules = CopyOnWriteArrayList<DeliverySchedule>()
        val connectionStates = CopyOnWriteArrayList<Boolean>()
        val client = client(
            fakeSocket = fakeSocket,
            capturedListener = capturedListener,
            onSchedule = { schedules.add(it) },
            onConnectionState = { connectionStates.add(it) }
        )

        client.start()
        waitUntil { capturedListener.get() != null }
        capturedListener.get().onMessage(
            fakeSocket,
            """{"type":"snapshot","revision":1,"schedule":{"schemaVersion":1,"slots":{}}}"""
        )
        capturedListener.get().onMessage(
            fakeSocket,
            """{"type":"schedule","revision":2,"schedule":{"schemaVersion":1,"slots":{"friday_pm":{"jobs":[{"jobNumber":"589"}]}}}}"""
        )

        assertEquals(2, schedules.size)
        assertEquals("589", schedules[1].slot("friday", "pm").jobs.single().jobNumber)
        assertEquals(listOf(true), connectionStates)
        client.stop()
    }

    @Test
    fun `does not connect until a valid initial snapshot arrives`() = runBlocking {
        val fakeSocket = mock<WebSocket>()
        val capturedListener = AtomicReference<WebSocketListener>()
        val schedules = CopyOnWriteArrayList<DeliverySchedule>()
        val connectionStates = CopyOnWriteArrayList<Boolean>()
        val client = client(
            fakeSocket = fakeSocket,
            capturedListener = capturedListener,
            onSchedule = { schedules.add(it) },
            onConnectionState = { connectionStates.add(it) }
        )

        client.start()
        waitUntil { capturedListener.get() != null }
        capturedListener.get().onMessage(
            fakeSocket,
            """{"type":"schedule","revision":1,"schedule":{"schemaVersion":1,"slots":{}}}"""
        )
        assertTrue(schedules.isEmpty())
        assertTrue(connectionStates.isEmpty())

        capturedListener.get().onMessage(
            fakeSocket,
            """{"type":"snapshot","revision":2,"schedule":{"schemaVersion":1,"slots":{}}}"""
        )
        assertEquals(1, schedules.size)
        assertEquals(listOf(true), connectionStates)
        client.stop()
    }

    @Test
    fun `suppresses invalid or missing schedule frames`() = runBlocking {
        val fakeSocket = mock<WebSocket>()
        val capturedListener = AtomicReference<WebSocketListener>()
        val schedules = CopyOnWriteArrayList<DeliverySchedule>()
        val connectionStates = CopyOnWriteArrayList<Boolean>()
        val client = client(
            fakeSocket = fakeSocket,
            capturedListener = capturedListener,
            onSchedule = { schedules.add(it) },
            onConnectionState = { connectionStates.add(it) }
        )

        client.start()
        waitUntil { capturedListener.get() != null }
        val listener = capturedListener.get()
        listener.onMessage(fakeSocket, "not json")
        listener.onMessage(fakeSocket, """{"type":"snapshot"}""")
        listener.onMessage(fakeSocket, """{"type":"schedule","schedule":null}""")
        listener.onMessage(fakeSocket, """{"type":"snapshot","schedule":{"schemaVersion":1}}""")
        listener.onMessage(fakeSocket, """{"type":"other","schedule":{"schemaVersion":1,"slots":{}}}""")

        assertTrue(schedules.isEmpty())
        assertTrue(connectionStates.isEmpty())
        client.stop()
    }

    @Test
    fun `not running reports disconnected`() = runBlocking {
        val fakeSocket = mock<WebSocket>()
        val capturedListener = AtomicReference<WebSocketListener>()
        val connectionStates = CopyOnWriteArrayList<Boolean>()
        val client = client(
            fakeSocket = fakeSocket,
            capturedListener = capturedListener,
            onConnectionState = { connectionStates.add(it) }
        )

        client.start()
        waitUntil { capturedListener.get() != null }
        capturedListener.get().onMessage(fakeSocket, """{"type":"not_running"}""")

        assertEquals(listOf(false), connectionStates)
        client.stop()
    }

    @Test
    fun `error reports disconnected without delivering a schedule`() = runBlocking {
        val fakeSocket = mock<WebSocket>()
        val capturedListener = AtomicReference<WebSocketListener>()
        val schedules = CopyOnWriteArrayList<DeliverySchedule>()
        val connectionStates = CopyOnWriteArrayList<Boolean>()
        val client = client(
            fakeSocket = fakeSocket,
            capturedListener = capturedListener,
            onSchedule = { schedules.add(it) },
            onConnectionState = { connectionStates.add(it) }
        )

        client.start()
        waitUntil { capturedListener.get() != null }
        capturedListener.get().onMessage(
            fakeSocket,
            """{"type":"error","message":"expected a hello message first","schedule":{"schemaVersion":1,"slots":{}}}"""
        )

        assertTrue(schedules.isEmpty())
        assertEquals(listOf(false), connectionStates)
        client.stop()
    }

    @Test
    fun `close reports disconnected and reconnects`() = runBlocking {
        val fakeSocket = mock<WebSocket>()
        val listeners = CopyOnWriteArrayList<WebSocketListener>()
        val connectionStates = CopyOnWriteArrayList<Boolean>()
        val connectAttempts = AtomicInteger()
        val client = client(
            fakeSocket = fakeSocket,
            onConnectionState = { connectionStates.add(it) },
            reconnectDelayMs = { 0L },
            webSocketFactory = { _, listener ->
                connectAttempts.incrementAndGet()
                listeners.add(listener)
                fakeSocket
            }
        )

        client.start()
        waitUntil { listeners.size == 1 }
        listeners[0].onClosed(fakeSocket, 1001, "gone")

        waitUntil { connectAttempts.get() == 2 }
        assertEquals(listOf(false), connectionStates)
        client.stop()
    }

    @Test
    fun `failure reports disconnected and reconnects`() = runBlocking {
        val fakeSocket = mock<WebSocket>()
        val listeners = CopyOnWriteArrayList<WebSocketListener>()
        val connectionStates = CopyOnWriteArrayList<Boolean>()
        val connectAttempts = AtomicInteger()
        val client = client(
            fakeSocket = fakeSocket,
            onConnectionState = { connectionStates.add(it) },
            reconnectDelayMs = { 0L },
            webSocketFactory = { _, listener ->
                connectAttempts.incrementAndGet()
                listeners.add(listener)
                fakeSocket
            }
        )

        client.start()
        waitUntil { listeners.size == 1 }
        listeners[0].onFailure(fakeSocket, RuntimeException("boom"), null)

        waitUntil { connectAttempts.get() == 2 }
        assertEquals(listOf(false), connectionStates)
        client.stop()
    }

    @Test
    fun `duplicate close callbacks schedule only one reconnect`() = runBlocking {
        val fakeSocket = mock<WebSocket>()
        val listeners = CopyOnWriteArrayList<WebSocketListener>()
        val scheduleCount = AtomicInteger()
        val client = client(
            fakeSocket = fakeSocket,
            reconnectDelayMs = { scheduleCount.incrementAndGet(); 100L },
            webSocketFactory = { _, listener ->
                listeners.add(listener)
                fakeSocket
            }
        )

        client.start()
        waitUntil { listeners.size == 1 }
        listeners[0].onClosed(fakeSocket, 1001, "first")
        listeners[0].onClosed(fakeSocket, 1001, "second")

        Thread.sleep(300L)
        assertEquals(1, scheduleCount.get())
        assertEquals(2, listeners.size)
        client.stop()
    }

    @Test
    fun `backoff attempt counter resets after valid snapshot`() = runBlocking {
        val fakeSocket = mock<WebSocket>()
        val listeners = CopyOnWriteArrayList<WebSocketListener>()
        val recordedAttempts = CopyOnWriteArrayList<Int>()
        val client = client(
            fakeSocket = fakeSocket,
            reconnectDelayMs = { attempt -> recordedAttempts.add(attempt); 0L },
            webSocketFactory = { _, listener ->
                listeners.add(listener)
                fakeSocket
            }
        )

        client.start()
        waitUntil { listeners.size == 1 }
        listeners[0].onFailure(fakeSocket, RuntimeException("first"), null)
        waitUntil { listeners.size == 2 }
        listeners[1].onMessage(
            fakeSocket,
            """{"type":"snapshot","revision":1,"schedule":{"schemaVersion":1,"slots":{}}}"""
        )
        listeners[1].onFailure(fakeSocket, RuntimeException("second"), null)

        waitUntil { recordedAttempts.size >= 2 }
        assertEquals(0, recordedAttempts[0])
        assertEquals(0, recordedAttempts[1])
        client.stop()
    }

    @Test
    fun `stop cancels pending reconnect`() = runBlocking {
        val fakeSocket = mock<WebSocket>()
        val listeners = CopyOnWriteArrayList<WebSocketListener>()
        val connectAttempts = AtomicInteger()
        val client = client(
            fakeSocket = fakeSocket,
            reconnectDelayMs = { 200L },
            webSocketFactory = { _, listener ->
                connectAttempts.incrementAndGet()
                listeners.add(listener)
                fakeSocket
            }
        )

        client.start()
        waitUntil { listeners.size == 1 }
        listeners[0].onFailure(fakeSocket, RuntimeException("boom"), null)
        client.stop()

        Thread.sleep(400L)
        assertEquals(1, connectAttempts.get())
    }

    @Test
    fun `ignores stale callbacks from an old socket after a newer socket exists`() = runBlocking {
        val sockets = listOf(mock<WebSocket>(), mock<WebSocket>())
        val listeners = CopyOnWriteArrayList<WebSocketListener>()
        val schedules = CopyOnWriteArrayList<DeliverySchedule>()
        val connectionStates = CopyOnWriteArrayList<Boolean>()
        val connectAttempts = AtomicInteger()
        val client = client(
            fakeSocket = sockets[0],
            onSchedule = { schedules.add(it) },
            onConnectionState = { connectionStates.add(it) },
            reconnectDelayMs = { 0L },
            webSocketFactory = { _, listener ->
                val index = connectAttempts.getAndIncrement()
                listeners.add(listener)
                sockets[index.coerceAtMost(sockets.lastIndex)]
            }
        )

        client.start()
        waitUntil { listeners.size == 1 }
        listeners[0].onFailure(sockets[0], RuntimeException("first"), null)
        waitUntil { listeners.size == 2 }

        val staleSnapshot =
            """{"type":"snapshot","revision":1,"schedule":{"schemaVersion":1,"slots":{"friday_am":{"jobs":[{"jobNumber":"588"}]}}}}"""
        listeners[0].onMessage(sockets[0], staleSnapshot)
        listeners[0].onClosed(sockets[0], 1001, "stale")
        listeners[0].onFailure(sockets[0], RuntimeException("stale"), null)

        Thread.sleep(200L)
        assertTrue(schedules.isEmpty())
        assertEquals(listOf(false), connectionStates)
        assertEquals(2, listeners.size)
        client.stop()
    }

    @Test
    fun `concurrent starts create only one socket`() = runBlocking {
        val fakeSocket = mock<WebSocket>()
        val listeners = CopyOnWriteArrayList<WebSocketListener>()
        val client = client(
            fakeSocket = fakeSocket,
            webSocketFactory = { _, listener ->
                listeners.add(listener)
                fakeSocket
            }
        )
        val starts = (1..8).map { Thread { client.start() } }

        starts.forEach(Thread::start)
        starts.forEach(Thread::join)
        waitUntil { listeners.size == 1 }

        Thread.sleep(100L)
        assertEquals(1, listeners.size)
        client.stop()
    }

    @Test
    fun `rapid stop and start suppresses stale reconnect from prior lifecycle`() = runBlocking {
        val fakeSocket = mock<WebSocket>()
        val listeners = CopyOnWriteArrayList<WebSocketListener>()
        val connectAttempts = AtomicInteger()
        val client = client(
            fakeSocket = fakeSocket,
            reconnectDelayMs = { 200L },
            webSocketFactory = { _, listener ->
                connectAttempts.incrementAndGet()
                listeners.add(listener)
                fakeSocket
            }
        )

        client.start()
        waitUntil { listeners.size == 1 }
        listeners[0].onFailure(fakeSocket, RuntimeException("old lifecycle"), null)
        client.stop()
        client.start()
        waitUntil { listeners.size == 2 }

        Thread.sleep(400L)
        assertEquals(2, connectAttempts.get())
        client.stop()
    }

    private fun client(
        fakeSocket: WebSocket,
        capturedRequest: AtomicReference<Request> = AtomicReference(),
        capturedListener: AtomicReference<WebSocketListener> = AtomicReference(),
        onSchedule: (DeliverySchedule) -> Unit = {},
        onConnectionState: (Boolean) -> Unit = {},
        reconnectDelayMs: (Int) -> Long = { 0L },
        webSocketFactory: ((Request, WebSocketListener) -> WebSocket)? = null
    ): DeliveryScheduleLiveClient {
        val factory = webSocketFactory ?: { request, listener ->
            capturedRequest.set(request)
            capturedListener.set(listener)
            fakeSocket
        }
        return DeliveryScheduleLiveClient(
            config = configWithIp("192.168.1.15"),
            tabletId = "tablet-7",
            onSchedule = onSchedule,
            onConnectionState = onConnectionState,
            reconnectDelayMs = reconnectDelayMs,
            webSocketFactory = factory
        )
    }

    private fun configWithIp(ip: String): AdminSyncConfig = mock {
        onBlocking { getManualIp() } doReturn ip
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
