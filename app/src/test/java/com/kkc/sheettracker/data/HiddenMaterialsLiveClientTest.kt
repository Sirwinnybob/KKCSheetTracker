package com.kkc.sheettracker.data

import com.kkc.sheettracker.data.models.HiddenMaterialsDocument
import com.kkc.sheettracker.data.models.HiddenMaterialsMode
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

class HiddenMaterialsLiveClientTest {

    @Test
    fun `connects to hidden materials live URL and sends hello with mode and tablet id`() = runBlocking {
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
            "http://192.168.1.15:47821/api/hidden-materials/live",
            capturedRequest.get().url.toString()
        )
        verify(fakeSocket).send("""{"type":"hello","mode":"HARDWOODS","tabletId":"tablet-7"}""")
        client.stop()
    }

    @Test
    fun `dispatches valid snapshot and reports connected`() = runBlocking {
        val fakeSocket = mock<WebSocket>()
        val capturedListener = AtomicReference<WebSocketListener>()
        val documents = CopyOnWriteArrayList<HiddenMaterialsDocument>()
        val connectionStates = CopyOnWriteArrayList<Boolean>()
        val client = client(
            fakeSocket = fakeSocket,
            capturedListener = capturedListener,
            onDocument = { documents.add(it) },
            onConnectionState = { connectionStates.add(it) }
        )

        client.start()
        waitUntil { capturedListener.get() != null }
        capturedListener.get().onMessage(
            fakeSocket,
            """{"type":"snapshot","revision":1,"hiddenMaterials":{"global":{"entries":[{"docType":"NAILER_CUT_LIST","material":"Maple"}]},"jobs":{}}}"""
        )

        assertEquals("Maple", documents.single().global.entries.single().material)
        assertEquals(listOf(true), connectionStates)
        client.stop()
    }

    @Test
    fun `dispatches update frames without changing connection state`() = runBlocking {
        val fakeSocket = mock<WebSocket>()
        val capturedListener = AtomicReference<WebSocketListener>()
        val documents = CopyOnWriteArrayList<HiddenMaterialsDocument>()
        val connectionStates = CopyOnWriteArrayList<Boolean>()
        val client = client(
            fakeSocket = fakeSocket,
            capturedListener = capturedListener,
            onDocument = { documents.add(it) },
            onConnectionState = { connectionStates.add(it) }
        )

        client.start()
        waitUntil { capturedListener.get() != null }
        capturedListener.get().onMessage(
            fakeSocket,
            """{"type":"snapshot","revision":1,"hiddenMaterials":{"global":{"entries":[]},"jobs":{}}}"""
        )
        capturedListener.get().onMessage(
            fakeSocket,
            """{"type":"hiddenMaterials","revision":2,"hiddenMaterials":{"global":{"entries":[{"docType":"DOOR_LIST","material":"Oak"}]},"jobs":{}}}"""
        )

        assertEquals(2, documents.size)
        assertEquals("Oak", documents[1].global.entries.single().material)
        assertEquals(listOf(true), connectionStates)
        client.stop()
    }

    @Test
    fun `does not connect until a valid initial snapshot arrives`() = runBlocking {
        val fakeSocket = mock<WebSocket>()
        val capturedListener = AtomicReference<WebSocketListener>()
        val documents = CopyOnWriteArrayList<HiddenMaterialsDocument>()
        val connectionStates = CopyOnWriteArrayList<Boolean>()
        val client = client(
            fakeSocket = fakeSocket,
            capturedListener = capturedListener,
            onDocument = { documents.add(it) },
            onConnectionState = { connectionStates.add(it) }
        )

        client.start()
        waitUntil { capturedListener.get() != null }
        capturedListener.get().onMessage(
            fakeSocket,
            """{"type":"hiddenMaterials","revision":1,"hiddenMaterials":{"global":{"entries":[]},"jobs":{}}}"""
        )
        assertTrue(documents.isEmpty())
        assertTrue(connectionStates.isEmpty())

        capturedListener.get().onMessage(
            fakeSocket,
            """{"type":"snapshot","revision":2,"hiddenMaterials":{"global":{"entries":[]},"jobs":{}}}"""
        )
        assertEquals(1, documents.size)
        assertEquals(listOf(true), connectionStates)
        client.stop()
    }

    @Test
    fun `applies update frames only when their revision is strictly newer`() = runBlocking {
        val fakeSocket = mock<WebSocket>()
        val capturedListener = AtomicReference<WebSocketListener>()
        val documents = CopyOnWriteArrayList<HiddenMaterialsDocument>()
        val client = client(
            fakeSocket = fakeSocket,
            capturedListener = capturedListener,
            onDocument = { documents.add(it) }
        )

        client.start()
        waitUntil { capturedListener.get() != null }
        val listener = capturedListener.get()
        listener.onMessage(
            fakeSocket,
            """{"type":"snapshot","revision":5,"hiddenMaterials":{"global":{"entries":[{"docType":"NAILER_CUT_LIST","material":"snapshot"}]},"jobs":{}}}"""
        )
        listener.onMessage(
            fakeSocket,
            """{"type":"hiddenMaterials","revision":5,"hiddenMaterials":{"global":{"entries":[{"docType":"NAILER_CUT_LIST","material":"duplicate"}]},"jobs":{}}}"""
        )
        listener.onMessage(
            fakeSocket,
            """{"type":"hiddenMaterials","revision":4,"hiddenMaterials":{"global":{"entries":[{"docType":"NAILER_CUT_LIST","material":"older"}]},"jobs":{}}}"""
        )
        listener.onMessage(
            fakeSocket,
            """{"type":"hiddenMaterials","revision":6,"hiddenMaterials":{"global":{"entries":[{"docType":"NAILER_CUT_LIST","material":"newer"}]},"jobs":{}}}"""
        )

        assertEquals(
            listOf("snapshot", "newer"),
            documents.map { it.global.entries.single().material }
        )
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
    fun `error reports disconnected without delivering a document`() = runBlocking {
        val fakeSocket = mock<WebSocket>()
        val capturedListener = AtomicReference<WebSocketListener>()
        val documents = CopyOnWriteArrayList<HiddenMaterialsDocument>()
        val connectionStates = CopyOnWriteArrayList<Boolean>()
        val client = client(
            fakeSocket = fakeSocket,
            capturedListener = capturedListener,
            onDocument = { documents.add(it) },
            onConnectionState = { connectionStates.add(it) }
        )

        client.start()
        waitUntil { capturedListener.get() != null }
        capturedListener.get().onMessage(
            fakeSocket,
            """{"type":"error","message":"unknown mode"}"""
        )

        assertTrue(documents.isEmpty())
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
            """{"type":"snapshot","revision":1,"hiddenMaterials":{"global":{"entries":[]},"jobs":{}}}"""
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
    fun `suppresses invalid or missing document frames`() = runBlocking {
        val fakeSocket = mock<WebSocket>()
        val capturedListener = AtomicReference<WebSocketListener>()
        val documents = CopyOnWriteArrayList<HiddenMaterialsDocument>()
        val connectionStates = CopyOnWriteArrayList<Boolean>()
        val client = client(
            fakeSocket = fakeSocket,
            capturedListener = capturedListener,
            onDocument = { documents.add(it) },
            onConnectionState = { connectionStates.add(it) }
        )

        client.start()
        waitUntil { capturedListener.get() != null }
        val listener = capturedListener.get()
        listener.onMessage(fakeSocket, "not json")
        listener.onMessage(fakeSocket, """{"type":"snapshot"}""")
        listener.onMessage(fakeSocket, """{"type":"hiddenMaterials","hiddenMaterials":null}""")
        listener.onMessage(fakeSocket, """{"type":"snapshot","revision":1,"hiddenMaterials":{"jobs":{}}}""")
        listener.onMessage(fakeSocket, """{"type":"other","hiddenMaterials":{"global":{"entries":[]},"jobs":{}}}""")

        assertTrue(documents.isEmpty())
        assertTrue(connectionStates.isEmpty())
        client.stop()
    }

    private fun client(
        fakeSocket: WebSocket,
        mode: HiddenMaterialsMode = HiddenMaterialsMode.HARDWOODS,
        capturedRequest: AtomicReference<Request> = AtomicReference(),
        capturedListener: AtomicReference<WebSocketListener> = AtomicReference(),
        onDocument: (HiddenMaterialsDocument) -> Unit = {},
        onConnectionState: (Boolean) -> Unit = {},
        reconnectDelayMs: (Int) -> Long = { 0L },
        webSocketFactory: ((Request, WebSocketListener) -> WebSocket)? = null
    ): HiddenMaterialsLiveClient {
        val factory = webSocketFactory ?: { request, listener ->
            capturedRequest.set(request)
            capturedListener.set(listener)
            fakeSocket
        }
        return HiddenMaterialsLiveClient(
            config = configWithIp("192.168.1.15"),
            mode = mode,
            tabletId = "tablet-7",
            onDocument = onDocument,
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
