package com.kkc.sheettracker.data

import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class SupplyLiveClientTest {

    private val doc = """{"categories":[],"items":[{"id":"i1","name":"Screws"}],"comments":{}}"""

    @Test
    fun `connects to supply live URL and sends hello with tablet id`() {
        val fakeSocket = mock<WebSocket>()
        val capturedRequest = AtomicReference<Request>()
        val capturedListener = AtomicReference<WebSocketListener>()
        val client = client(fakeSocket, capturedRequest, capturedListener)

        client.start()
        waitUntil { capturedListener.get() != null }
        capturedListener.get().onOpen(fakeSocket, mock())

        assertEquals("http://192.168.1.15:47821/api/supply/live", capturedRequest.get().url.toString())
        verify(fakeSocket).send("""{"type":"hello","tabletId":"tablet-7"}""")
        client.stop()
    }

    @Test
    fun `valid snapshot is dispatched and reports connected`() {
        val fakeSocket = mock<WebSocket>()
        val listener = AtomicReference<WebSocketListener>()
        val snapshots = CopyOnWriteArrayList<SupplyLiveSnapshot>()
        val states = CopyOnWriteArrayList<Boolean>()
        val client = client(fakeSocket, capturedListener = listener, onSupply = { snapshots.add(it) }, onConnectionState = { states.add(it) })

        client.start()
        waitUntil { listener.get() != null }
        listener.get().onMessage(fakeSocket, """{"type":"snapshot","revision":3,"supply":$doc}""")

        assertEquals(3L, snapshots.single().revision)
        assertEquals("Screws", snapshots.single().items.getValue("i1").name)
        assertEquals(listOf(true), states)
        client.stop()
    }

    @Test
    fun `supply frames apply after snapshot and stale revisions are dropped`() {
        val fakeSocket = mock<WebSocket>()
        val listener = AtomicReference<WebSocketListener>()
        val revisions = CopyOnWriteArrayList<Long>()
        val client = client(fakeSocket, capturedListener = listener, onSupply = { revisions.add(it.revision) })

        client.start()
        waitUntil { listener.get() != null }
        val l = listener.get()
        l.onMessage(fakeSocket, """{"type":"supply","revision":1,"supply":$doc}""")        // before snapshot: ignored
        l.onMessage(fakeSocket, """{"type":"snapshot","revision":2,"supply":$doc}""")
        l.onMessage(fakeSocket, """{"type":"snapshot","revision":5,"supply":$doc}""")      // duplicate snapshot: ignored
        l.onMessage(fakeSocket, """{"type":"supply","revision":2,"supply":$doc}""")        // stale: ignored
        l.onMessage(fakeSocket, """{"type":"supply","revision":3,"supply":$doc}""")
        l.onMessage(fakeSocket, """{"type":"supply","revision":-1,"supply":$doc}""")       // invalid revision: ignored

        assertEquals(listOf(2L, 3L), revisions)
        client.stop()
    }

    @Test
    fun `invalid payload is ignored and does not report connected`() {
        val fakeSocket = mock<WebSocket>()
        val listener = AtomicReference<WebSocketListener>()
        val snapshots = CopyOnWriteArrayList<SupplyLiveSnapshot>()
        val states = CopyOnWriteArrayList<Boolean>()
        val client = client(fakeSocket, capturedListener = listener, onSupply = { snapshots.add(it) }, onConnectionState = { states.add(it) })

        client.start()
        waitUntil { listener.get() != null }
        listener.get().onMessage(fakeSocket, """{"type":"snapshot","revision":1,"supply":{"categories":[],"items":{}}}""")
        listener.get().onMessage(fakeSocket, """not json""")

        assertTrue(snapshots.isEmpty())
        assertTrue(states.isEmpty())
        client.stop()
    }

    @Test
    fun `not_running reports disconnected`() {
        val fakeSocket = mock<WebSocket>()
        val listener = AtomicReference<WebSocketListener>()
        val states = CopyOnWriteArrayList<Boolean>()
        val client = client(fakeSocket, capturedListener = listener, onConnectionState = { states.add(it) })

        client.start()
        waitUntil { listener.get() != null }
        listener.get().onMessage(fakeSocket, """{"type":"not_running"}""")

        assertEquals(listOf(false), states)
        client.stop()
    }

    @Test
    fun `failure reports disconnected and reconnects`() {
        val fakeSocket = mock<WebSocket>()
        val listener = AtomicReference<WebSocketListener>()
        val connects = AtomicInteger(0)
        val states = CopyOnWriteArrayList<Boolean>()
        val client = SupplyLiveClient(
            config = configWithIp("192.168.1.15"),
            tabletId = "tablet-7",
            onSupply = {},
            onConnectionState = { states.add(it) },
            reconnectDelayMs = { 0L },
            webSocketFactory = { _, l ->
                listener.set(l)
                connects.incrementAndGet()
                fakeSocket
            }
        )

        client.start()
        waitUntil { connects.get() == 1 }
        listener.get().onFailure(fakeSocket, IOException("reset"), null)
        waitUntil { connects.get() == 2 }

        assertEquals(false, states.first())
        client.stop()
    }

    @Test
    fun `backoff doubles to a 30 second cap`() {
        assertEquals(1_000L, nextSupplyLiveBackoffDelayMs(0))
        assertEquals(2_000L, nextSupplyLiveBackoffDelayMs(1))
        assertEquals(30_000L, nextSupplyLiveBackoffDelayMs(5))
        assertEquals(30_000L, nextSupplyLiveBackoffDelayMs(50))
    }

    private fun client(
        fakeSocket: WebSocket,
        capturedRequest: AtomicReference<Request> = AtomicReference(),
        capturedListener: AtomicReference<WebSocketListener> = AtomicReference(),
        onSupply: (SupplyLiveSnapshot) -> Unit = {},
        onConnectionState: (Boolean) -> Unit = {}
    ) = SupplyLiveClient(
        config = configWithIp("192.168.1.15"),
        tabletId = "tablet-7",
        onSupply = onSupply,
        onConnectionState = onConnectionState,
        reconnectDelayMs = { 0L },
        webSocketFactory = { request, listener ->
            capturedRequest.set(request)
            capturedListener.set(listener)
            fakeSocket
        }
    )

    private fun configWithIp(ip: String): AdminSyncConfig = mock {
        onBlocking { getManualIp() } doReturn ip
    }

    private fun waitUntil(timeoutMs: Long = 2_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) {
                // The client stores the socket just after the factory returns; callbacks for a
                // socket that is not yet stored are ignored as stale. Give that assignment time.
                Thread.sleep(100L)
                return
            }
            Thread.sleep(20L)
        }
        throw AssertionError("Timed out waiting for condition")
    }
}
