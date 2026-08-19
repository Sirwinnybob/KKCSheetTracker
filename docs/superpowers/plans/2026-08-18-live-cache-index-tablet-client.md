# Live Cache-Index Tablet Client Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Connect KKCSheetTracker's Android app to Hours Tracker's existing `/api/ready-jobs-worker/live-index` WebSocket so the jobs list updates in near-real-time, falling back to the existing `StaticCachePoller`/file-based path whenever the socket is unavailable.

**Architecture:** A new `LiveIndexClient` (OkHttp `WebSocketListener`) parses the socket's snapshot/delta frames (identical JSON shape to `cache_index.json`'s `jobInfo`+`progressSummary`) and feeds a new `LiveAwareUnifiedMetadataEngine` decorator — a `by delegate`-based wrapper around the existing registry-singleton `FileBackedUnifiedMetadataEngine` that overrides only the three jobs-list-read methods. `NavGraph.kt`'s root composable owns the client's lifecycle and pauses/resumes `StaticCachePoller` based on connection state; the decorator is threaded down to the two existing jobs-list composables as a new parameter.

**Tech Stack:** Kotlin, Jetpack Compose, OkHttp 4.12.0 (WebSocket), Gson 2.13.2, JUnit4 + mockito-kotlin (existing test stack, no new dependencies).

**Spec:** `docs/superpowers/specs/2026-08-18-live-cache-index-tablet-client-design.md`

---

## Task 1: Reconnect backoff calculation

**Files:**
- Create: `app/src/main/java/com/kkc/sheettracker/data/LiveIndexClient.kt`
- Test: `app/src/test/java/com/kkc/sheettracker/data/LiveIndexClientTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.kkc.sheettracker.data

import org.junit.Assert.assertEquals
import org.junit.Test

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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.kkc.sheettracker.data.LiveIndexClientTest"`
Expected: FAIL to compile — `nextBackoffDelayMs` is unresolved (file doesn't exist yet).

- [ ] **Step 3: Write minimal implementation**

Create `app/src/main/java/com/kkc/sheettracker/data/LiveIndexClient.kt`:

```kotlin
package com.kkc.sheettracker.data

internal fun nextBackoffDelayMs(attempt: Int): Long =
    (1_000L shl attempt.coerceIn(0, 5)).coerceAtMost(30_000L)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.kkc.sheettracker.data.LiveIndexClientTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/data/LiveIndexClient.kt app/src/test/java/com/kkc/sheettracker/data/LiveIndexClientTest.kt
git commit -m "feat: add live-index reconnect backoff calculation"
```

---

## Task 2: Wire-format parsing models

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/data/LiveIndexClient.kt`
- Test: `app/src/test/java/com/kkc/sheettracker/data/LiveIndexClientTest.kt`

These model the exact JSON the server sends (`backend/routes/ready_jobs_worker_live_index.py` in the Hours Tracker repo): a `snapshot` envelope carries `jobs: {folderName: <cache_index.json-shaped object>}`; a `delta` envelope carries a nested `delta` object with `type` (`"upsert"`/`"remove"`), `folderName`, and `index` (null for remove). Each per-job `index`/`jobs[x]` value has the exact same `jobInfo`+`progressSummary` shape as `.metadata/cache_index.json`, already modeled by `CacheIndexRoot` in `app/src/main/java/com/kkc/sheettracker/data/models/CacheIndexModels.kt` — reuse it rather than defining a new job-payload model.

- [ ] **Step 1: Write the failing test**

Add to `LiveIndexClientTest.kt`:

```kotlin
import com.google.gson.Gson

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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.kkc.sheettracker.data.LiveIndexEnvelopeParsingTest"`
Expected: FAIL to compile — `LiveIndexEnvelope`/`LiveIndexDeltaWire` unresolved.

- [ ] **Step 3: Write minimal implementation**

Append to `LiveIndexClient.kt`:

```kotlin
import com.kkc.sheettracker.data.models.CacheIndexRoot

internal data class LiveIndexDeltaWire(
    val type: String? = null,
    val folderName: String? = null,
    val revision: Long? = null,
    val index: CacheIndexRoot? = null
)

internal data class LiveIndexEnvelope(
    val type: String? = null,
    val serverInstanceId: String? = null,
    val revision: Long? = null,
    val jobs: Map<String, CacheIndexRoot>? = null,
    val delta: LiveIndexDeltaWire? = null,
    val message: String? = null
)
```

(Put the `import` line with the file's other imports, not inline — shown here next to the code it supports for clarity.)

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.kkc.sheettracker.data.LiveIndexEnvelopeParsingTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/data/LiveIndexClient.kt app/src/test/java/com/kkc/sheettracker/data/LiveIndexClientTest.kt
git commit -m "feat: parse live-index WebSocket wire envelopes"
```

---

## Task 3: LiveIndexClient — hello handshake and message dispatch

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/data/LiveIndexClient.kt`
- Test: `app/src/test/java/com/kkc/sheettracker/data/LiveIndexClientTest.kt`

This step builds the class itself with a `webSocketFactory` constructor seam so tests can capture the `WebSocketListener` and drive its callbacks directly, without a real socket. `AdminSyncConfig` (`app/src/main/java/com/kkc/sheettracker/data/AdminSyncConfig.kt`) already provides `getManualIp()` (suspend) and the package-internal `buildAdminSyncUrl(ip)` helper — both directly usable since this file is in the same `com.kkc.sheettracker.data` package.

- [ ] **Step 1: Write the failing test**

Add to `LiveIndexClientTest.kt`:

```kotlin
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

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
```

Add `testImplementation("org.mockito.kotlin:mockito-kotlin:5.1.0")`'s `doReturn`/`onBlocking` usage requires no new dependency — `mockito-kotlin` is already declared in `app/build.gradle.kts`. `kotlinx-coroutines-core` (for `runBlocking`) is already transitively present (used throughout the app, e.g. `StaticCachePoller.kt`).

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.kkc.sheettracker.data.LiveIndexClientDispatchTest"`
Expected: FAIL to compile — `LiveIndexClient` class with this constructor doesn't exist yet.

- [ ] **Step 3: Write minimal implementation**

Replace the contents of `LiveIndexClient.kt` with:

```kotlin
package com.kkc.sheettracker.data

import android.util.Log
import com.google.gson.Gson
import com.kkc.sheettracker.data.models.CacheIndexRoot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

internal fun nextBackoffDelayMs(attempt: Int): Long =
    (1_000L shl attempt.coerceIn(0, 5)).coerceAtMost(30_000L)

internal data class LiveIndexDeltaWire(
    val type: String? = null,
    val folderName: String? = null,
    val revision: Long? = null,
    val index: CacheIndexRoot? = null
)

internal data class LiveIndexEnvelope(
    val type: String? = null,
    val serverInstanceId: String? = null,
    val revision: Long? = null,
    val jobs: Map<String, CacheIndexRoot>? = null,
    val delta: LiveIndexDeltaWire? = null,
    val message: String? = null
)

/**
 * Connects to Hours Tracker's `/api/ready-jobs-worker/live-index` read-only WebSocket
 * (see `docs/superpowers/specs/2026-08-18-live-cache-index-tablet-client-design.md`).
 * Any server-sent `snapshot` frame is always treated as a full replace, whatever the
 * reason it arrived (initial connect, revision-gap resync, or server restart) — the
 * server already resolves gap detection on its side.
 */
class LiveIndexClient(
    private val config: AdminSyncConfig,
    private val tabletId: String,
    private val onSnapshot: (jobs: Map<String, CacheIndexRoot>) -> Unit,
    private val onDelta: (folderName: String, index: CacheIndexRoot?) -> Unit,
    private val onConnectionState: (connected: Boolean) -> Unit,
    private val reconnectDelayMs: (attempt: Int) -> Long = ::nextBackoffDelayMs,
    private val webSocketFactory: (Request, WebSocketListener) -> WebSocket = { request, listener ->
        sharedClient.newWebSocket(request, listener)
    }
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var running = false
    @Volatile private var socket: WebSocket? = null
    private val attempt = AtomicInteger(0)
    private var pendingJob: Job? = null

    companion object {
        private const val TAG = "LiveIndexClient"
        private val gson = Gson()
        private val sharedClient = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }

    fun start() {
        if (running) return
        running = true
        attempt.set(0)
        connectNow()
    }

    fun stop() {
        running = false
        pendingJob?.cancel()
        pendingJob = null
        socket?.close(1000, "client stop")
        socket = null
    }

    private fun connectNow() {
        pendingJob = scope.launch {
            val baseUrl = buildAdminSyncUrl(config.getManualIp())
            if (baseUrl == null) {
                scheduleReconnect()
                return@launch
            }
            val wsUrl = baseUrl.replaceFirst("http://", "ws://") + "/api/ready-jobs-worker/live-index"
            val request = Request.Builder().url(wsUrl).build()
            socket = webSocketFactory(request, Listener())
        }
    }

    private fun scheduleReconnect() {
        if (!running) return
        val delayMs = reconnectDelayMs(attempt.getAndIncrement())
        pendingJob = scope.launch {
            delay(delayMs)
            if (running) connectNow()
        }
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocket.send(gson.toJson(mapOf("type" to "hello", "tabletId" to tabletId)))
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val envelope = runCatching { gson.fromJson(text, LiveIndexEnvelope::class.java) }.getOrNull() ?: return
            when (envelope.type) {
                "snapshot" -> {
                    attempt.set(0)
                    onSnapshot(envelope.jobs.orEmpty())
                    onConnectionState(true)
                }
                "delta" -> {
                    val delta = envelope.delta ?: return
                    val folderName = delta.folderName ?: return
                    onDelta(folderName, if (delta.type == "remove") null else delta.index)
                }
                "not_running", "error" -> onConnectionState(false)
                else -> Unit
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            onConnectionState(false)
            scheduleReconnect()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.w(TAG, "Live index socket failure", t)
            onConnectionState(false)
            scheduleReconnect()
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.kkc.sheettracker.data.LiveIndexClientDispatchTest"`
Expected: PASS (all 4 cases)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/data/LiveIndexClient.kt app/src/test/java/com/kkc/sheettracker/data/LiveIndexClientTest.kt
git commit -m "feat: implement LiveIndexClient hello handshake and message dispatch"
```

---

## Task 4: LiveIndexClient — reconnect on close/failure, backoff reset, stop() cancels pending retry

**Files:**
- Test: `app/src/test/java/com/kkc/sheettracker/data/LiveIndexClientTest.kt`

The implementation for this already exists from Task 3 (`onClosed`/`onFailure` call `scheduleReconnect()`, `onMessage`'s `"snapshot"` branch resets `attempt` to 0, `stop()` cancels `pendingJob`). This task adds the tests that prove it, using a zero-delay `reconnectDelayMs` override so retries happen near-instantly instead of waiting real backoff time (same "inject a fast/deterministic seam, use a short polling `waitUntil`" pattern as `StaticCachePollerTest`).

- [ ] **Step 1: Write the failing test**

Add to `LiveIndexClientTest.kt`:

```kotlin
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.kkc.sheettracker.data.LiveIndexClientReconnectTest"`
Expected: The `stop cancels a pending reconnect` case should already PASS since Task 3's implementation is complete; if any case unexpectedly fails, that reveals a real bug in Task 3's `scheduleReconnect`/`stop` logic — fix `LiveIndexClient.kt` (not the test) before proceeding.

- [ ] **Step 3: N/A — implementation already exists from Task 3**

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.kkc.sheettracker.data.LiveIndexClientReconnectTest"`
Expected: PASS (all 3 cases)

- [ ] **Step 5: Commit**

```bash
git add app/src/test/java/com/kkc/sheettracker/data/LiveIndexClientTest.kt
git commit -m "test: cover LiveIndexClient reconnect backoff and stop() cancellation"
```

---

## Task 5: LiveAwareUnifiedMetadataEngine decorator

**Files:**
- Create: `app/src/main/java/com/kkc/sheettracker/data/unified/LiveAwareUnifiedMetadataEngine.kt`
- Test: `app/src/test/java/com/kkc/sheettracker/data/unified/LiveAwareUnifiedMetadataEngineTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.kkc.sheettracker.data.unified

import com.kkc.sheettracker.data.models.CacheIndexJobInfo
import com.kkc.sheettracker.data.models.CacheIndexProgressSummary
import com.kkc.sheettracker.data.models.CacheIndexRoot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class LiveAwareUnifiedMetadataEngineTest {

    private fun rootFor(folderName: String, jobNumber: String, lineupPosition: Int? = null) = CacheIndexRoot(
        jobInfo = CacheIndexJobInfo(
            folderName = folderName,
            jobNumber = jobNumber,
            jobName = "Job $jobNumber",
            hiddenFromProduction = false,
            lineupPosition = lineupPosition
        ),
        progressSummary = CacheIndexProgressSummary(hasDeliverySheet = true)
    )

    @Test
    fun `listJobsFromCacheIndex returns live data once a snapshot is applied`() {
        val delegate = mock<UnifiedMetadataEngine> {
            on { listJobsFromCacheIndex() } doReturn (listOf(UnifiedJobInfo("stale", "0", "Stale")) to emptyList())
        }
        val engine = LiveAwareUnifiedMetadataEngine(delegate)

        engine.applySnapshot(mapOf("1234 - Job" to rootFor("1234 - Job", "1234")))
        val (jobs, needsDeep) = engine.listJobsFromCacheIndex()

        assertEquals(1, jobs.size)
        assertEquals("1234 - Job", jobs[0].folderName)
        assertTrue(needsDeep.isEmpty())
    }

    @Test
    fun `setConnected false clears live state and falls back to the delegate`() {
        val delegate = mock<UnifiedMetadataEngine> {
            on { getCachedJobInfos() } doReturn listOf(UnifiedJobInfo("from-delegate", "9", "Delegate Job"))
        }
        val engine = LiveAwareUnifiedMetadataEngine(delegate)
        engine.applySnapshot(mapOf("1234 - Job" to rootFor("1234 - Job", "1234")))

        engine.setConnected(false)
        val jobs = engine.getCachedJobInfos()

        assertEquals(1, jobs.size)
        assertEquals("from-delegate", jobs[0].folderName)
    }

    @Test
    fun `applyDelta upserts and removes individual jobs`() {
        val delegate = mock<UnifiedMetadataEngine>()
        val engine = LiveAwareUnifiedMetadataEngine(delegate)
        engine.applySnapshot(mapOf("1234 - Job" to rootFor("1234 - Job", "1234")))

        engine.applyDelta("5678 - Other", rootFor("5678 - Other", "5678"))
        assertEquals(2, engine.getCachedJobInfos().size)

        engine.applyDelta("1234 - Job", null)
        val jobs = engine.getCachedJobInfos()
        assertEquals(1, jobs.size)
        assertEquals("5678 - Other", jobs[0].folderName)
    }

    @Test
    fun `getProgressFromIndex prefers live data when connected, else delegates`() {
        val delegate = mock<UnifiedMetadataEngine> {
            on { getProgressFromIndex("1234 - Job") } doReturn null
        }
        val engine = LiveAwareUnifiedMetadataEngine(delegate)
        engine.applySnapshot(mapOf("1234 - Job" to rootFor("1234 - Job", "1234")))

        assertEquals(true, engine.getProgressFromIndex("1234 - Job")?.hasDeliverySheet)

        engine.setConnected(false)
        assertNull(engine.getProgressFromIndex("1234 - Job"))
    }

    @Test
    fun `unrelated interface methods pass straight through to the delegate`() {
        val delegate = mock<UnifiedMetadataEngine>()
        val engine = LiveAwareUnifiedMetadataEngine(delegate)

        engine.invalidateJob("1234 - Job")

        verify(delegate).invalidateJob("1234 - Job")
    }

    @Test
    fun `live jobs sort by lineup position then job number descending then folder name`() {
        val delegate = mock<UnifiedMetadataEngine>()
        val engine = LiveAwareUnifiedMetadataEngine(delegate)

        engine.applySnapshot(
            mapOf(
                "2000 - B" to rootFor("2000 - B", "2000", lineupPosition = null),
                "1000 - A" to rootFor("1000 - A", "1000", lineupPosition = null),
                "0500 - Pinned" to rootFor("0500 - Pinned", "0500", lineupPosition = 0)
            )
        )

        val (jobs, _) = engine.listJobsFromCacheIndex()

        assertEquals(listOf("0500 - Pinned", "2000 - B", "1000 - A"), jobs.map { it.folderName })
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.kkc.sheettracker.data.unified.LiveAwareUnifiedMetadataEngineTest"`
Expected: FAIL to compile — `LiveAwareUnifiedMetadataEngine` doesn't exist yet.

- [ ] **Step 3: Write minimal implementation**

Create `app/src/main/java/com/kkc/sheettracker/data/unified/LiveAwareUnifiedMetadataEngine.kt`:

```kotlin
package com.kkc.sheettracker.data.unified

import com.kkc.sheettracker.data.models.CacheIndexProgressSummary
import com.kkc.sheettracker.data.models.CacheIndexRoot
import java.util.concurrent.ConcurrentHashMap

/**
 * Decorates [delegate] (the registry-singleton [FileBackedUnifiedMetadataEngine]) with
 * live WebSocket state for the three jobs-list-read methods, leaving every other method
 * an exact passthrough via Kotlin interface delegation. See
 * docs/superpowers/specs/2026-08-18-live-cache-index-tablet-client-design.md.
 *
 * [setConnected] clearing the live map on disconnect (rather than leaving stale entries
 * behind) is what makes falling back to [delegate] safe at any moment.
 */
class LiveAwareUnifiedMetadataEngine(
    private val delegate: UnifiedMetadataEngine
) : UnifiedMetadataEngine by delegate {

    private val liveJobs = ConcurrentHashMap<String, CacheIndexRoot>()
    @Volatile private var connected = false

    fun applySnapshot(jobs: Map<String, CacheIndexRoot>) {
        liveJobs.clear()
        liveJobs.putAll(jobs)
        connected = true
    }

    fun applyDelta(folderName: String, index: CacheIndexRoot?) {
        if (index == null) liveJobs.remove(folderName) else liveJobs[folderName] = index
    }

    fun setConnected(value: Boolean) {
        connected = value
        if (!value) liveJobs.clear()
    }

    override fun listJobsFromCacheIndex(): Pair<List<UnifiedJobInfo>, List<String>> =
        if (connected) buildFromLive() to emptyList() else delegate.listJobsFromCacheIndex()

    override fun getProgressFromIndex(folderName: String): CacheIndexProgressSummary? =
        if (connected) liveJobs[folderName]?.progressSummary else delegate.getProgressFromIndex(folderName)

    override fun getCachedJobInfos(): List<UnifiedJobInfo> =
        if (connected) buildFromLive() else delegate.getCachedJobInfos()

    private fun buildFromLive(): List<UnifiedJobInfo> =
        liveJobs.values.mapNotNull { root ->
            val rawInfo = root.jobInfo ?: return@mapNotNull null
            UnifiedJobInfo(
                folderName = rawInfo.folderName,
                jobNumber = rawInfo.jobNumber,
                jobName = rawInfo.jobName,
                hiddenFromProduction = rawInfo.hiddenFromProduction,
                lineupPosition = rawInfo.lineupPosition,
                indexProgress = root.progressSummary
            )
        }.sortedWith(
            compareBy<UnifiedJobInfo> { it.lineupPosition ?: Int.MAX_VALUE }
                .thenByDescending { it.jobNumber.toIntOrNull() ?: 0 }
                .thenBy { it.folderName }
        )
}
```

This sort order matches `FileBackedUnifiedMetadataEngine.listJobsFromCacheIndex()` (`app/src/main/java/com/kkc/sheettracker/data/unified/FileBackedUnifiedMetadataEngine.kt:380-384`) exactly, so switching between live and file-backed data never reorders the jobs list.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.kkc.sheettracker.data.unified.LiveAwareUnifiedMetadataEngineTest"`
Expected: PASS (all 6 cases)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/data/unified/LiveAwareUnifiedMetadataEngine.kt app/src/test/java/com/kkc/sheettracker/data/unified/LiveAwareUnifiedMetadataEngineTest.kt
git commit -m "feat: add LiveAwareUnifiedMetadataEngine decorator"
```

---

## Task 6: Wire the live client into NavGraph.kt's root composable

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/navigation/NavGraph.kt`

`AppNavigation` (starts line 165) already owns `tabletId`, `basePath`, `isDebugBuild`, and the `staticCachePoller` whose lifecycle this task pauses/resumes. It does not yet have an `AdminSyncConfig` or a `Context` reference of its own (those currently live one level down, separately, inside `MultiBackStackNavigation` and `LegacySingleStackNavigation`) — this task adds a second, independent `AdminSyncConfig` instance scoped to `AppNavigation` for the live client. `AdminSyncConfig` is a thin wrapper over a shared DataStore file, so a second instance reads the same persisted IP; it does not duplicate any state.

- [ ] **Step 1: Add the required imports**

In `app/src/main/java/com/kkc/sheettracker/navigation/NavGraph.kt`, near the existing import at line 102 (`import com.kkc.sheettracker.data.unified.UnifiedMetadataEngineRegistry`), add:

```kotlin
import com.kkc.sheettracker.data.LiveIndexClient
import com.kkc.sheettracker.data.unified.LiveAwareUnifiedMetadataEngine
import com.kkc.sheettracker.data.unified.UnifiedMetadataEngine
```

(`com.kkc.sheettracker.data.AdminSyncConfig` is already imported at line 109; `androidx.compose.ui.platform.LocalContext` is already imported and used elsewhere in this file.)

- [ ] **Step 2: Construct the live engine and client in `AppNavigation`**

In `AppNavigation`, insert this block immediately after the `staticCachePoller` `DisposableEffect` closes (i.e. right after the `}` that ends the block at `app/src/main/java/com/kkc/sheettracker/navigation/NavGraph.kt:282`, before the existing `val hardwoodsRepository = remember(basePath) { HardwoodsRepository(File(basePath)) }` line at 284):

```kotlin
    val liveIndexContext = LocalContext.current
    val liveIndexAdminSyncConfig = remember { AdminSyncConfig.create(liveIndexContext) }
    val liveIndexRegistryEngine = remember(basePath, isDebugBuild) {
        UnifiedMetadataEngineRegistry.getOrCreate(File(basePath), isDebugBuild)
    }
    val liveIndexEngine = remember(liveIndexRegistryEngine) {
        LiveAwareUnifiedMetadataEngine(liveIndexRegistryEngine)
    }
    val liveIndexClient = remember(liveIndexAdminSyncConfig, liveIndexEngine, tabletId) {
        LiveIndexClient(
            config = liveIndexAdminSyncConfig,
            tabletId = tabletId,
            onSnapshot = { jobs ->
                liveIndexEngine.applySnapshot(jobs)
                watcherRefreshSignal.value = System.currentTimeMillis()
            },
            onDelta = { folderName, index ->
                liveIndexEngine.applyDelta(folderName, index)
                watcherRefreshSignal.value = System.currentTimeMillis()
            },
            onConnectionState = { isConnected ->
                liveIndexEngine.setConnected(isConnected)
                if (isConnected) staticCachePoller.stop() else staticCachePoller.start()
                watcherRefreshSignal.value = System.currentTimeMillis()
            }
        )
    }
    DisposableEffect(lifecycleOwner, liveIndexClient) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> liveIndexClient.start()
                Lifecycle.Event.ON_STOP -> {
                    liveIndexClient.stop()
                    liveIndexEngine.setConnected(false)
                    staticCachePoller.start()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            liveIndexClient.stop()
        }
    }
```

`ON_STOP` explicitly restarts `staticCachePoller` (not just stopping the live client) so the fallback path is guaranteed running whenever the app is backgrounded — mirroring the same explicit "don't wait for the async callback" reasoning as `setConnected(false)` itself (see the design doc's Fallback semantics section).

- [ ] **Step 3: Compile-check**

Run: `./gradlew compileDebugKotlin`
Expected: Fails only on the two call sites touched in Tasks 7 and 8 (which don't exist yet) — confirm the failure is specifically about the not-yet-added `liveIndexEngine`/`unifiedEngine` arguments in `MultiBackStackNavigation(...)` and `LegacySingleStackNavigation(...)` calls, not a syntax error in this block. If it's a syntax error in this block, fix it before proceeding.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/navigation/NavGraph.kt
git commit -m "feat: construct live-index client and engine in AppNavigation"
```

(This commit intentionally leaves the build red until Task 8 — the two downstream composables that must accept the new engine don't have their parameters yet. Tasks 7 and 8 land immediately after.)

---

## Task 7: Thread the live engine through the multi-back-stack path

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/navigation/NavGraph.kt`

`JobsTabHost` (starts line 1111) already has a local `val unifiedEngine = remember { UnifiedMetadataEngineRegistry.getOrCreate(File(basePath), isDebugBuild) }` at line 1148, used by name at its four `engine = unifiedEngine,` call sites (1193, 1211, 1228, 1247 — `rememberCncJobsSpec`/`rememberHardwoodsJobsSpec`/`rememberAssemblyJobsSpec`/`rememberSpecialtyJobsSpec`). Naming the new parameter `unifiedEngine` too means those four call sites need no changes at all — only the signature gains a parameter and the local `remember` line is deleted.

- [ ] **Step 1: Add a parameter to `JobsTabHost` and delete its local engine `remember`**

In `app/src/main/java/com/kkc/sheettracker/navigation/NavGraph.kt`, in `JobsTabHost`'s signature (ending at line 1145 with `active: Boolean = true`), change:

```kotlin
    onUiVisibilityChanged: (Boolean) -> Unit = {},
    active: Boolean = true
) {
```

to:

```kotlin
    onUiVisibilityChanged: (Boolean) -> Unit = {},
    active: Boolean = true,
    unifiedEngine: UnifiedMetadataEngine
) {
```

Then delete this line (1148):

```kotlin
    val unifiedEngine = remember { UnifiedMetadataEngineRegistry.getOrCreate(File(basePath), isDebugBuild) }
```

- [ ] **Step 2: Add a parameter to `MultiBackStackNavigation` and forward it to `JobsTabHost`**

In `MultiBackStackNavigation`'s signature (ends at line 436-437), change:

```kotlin
    onThemeFollowSyncedDefaultChanged: (Boolean) -> Unit,
    onThemeOverrideChanged: (String?) -> Unit,
    onThemeCatalogReload: () -> Unit
) {
```

to:

```kotlin
    onThemeFollowSyncedDefaultChanged: (Boolean) -> Unit,
    onThemeOverrideChanged: (String?) -> Unit,
    onThemeCatalogReload: () -> Unit,
    liveIndexEngine: UnifiedMetadataEngine
) {
```

(This exact tail — `onThemeFollowSyncedDefaultChanged`/`onThemeOverrideChanged`/`onThemeCatalogReload` immediately followed by `) {` — is unique to `MultiBackStackNavigation`'s signature; `LegacySingleStackNavigation`'s signature has the same three lines but followed by different trailing params, not `) {` directly, so there's no ambiguity.)

In the `JobsTabHost(...)` call inside `MultiBackStackNavigation` (lines 744-778), add the new argument. Change:

```kotlin
                        onUiVisibilityChanged = { viewerUiVisible = it },
                        active = selectedTab == TopLevelTab.JOBS
                    )
```

to:

```kotlin
                        onUiVisibilityChanged = { viewerUiVisible = it },
                        active = selectedTab == TopLevelTab.JOBS,
                        unifiedEngine = liveIndexEngine
                    )
```

- [ ] **Step 3: Pass the engine from `AppNavigation` into `MultiBackStackNavigation`**

In `AppNavigation`'s call to `MultiBackStackNavigation` (lines 310-350), add the new argument. Change the closing of that call:

```kotlin
                onThemeFollowSyncedDefaultChanged = onThemeFollowSyncedDefaultChanged,
                onThemeOverrideChanged = onThemeOverrideChanged,
                onThemeCatalogReload = onThemeCatalogReload
            )
        } else {
```

to:

```kotlin
                onThemeFollowSyncedDefaultChanged = onThemeFollowSyncedDefaultChanged,
                onThemeOverrideChanged = onThemeOverrideChanged,
                onThemeCatalogReload = onThemeCatalogReload,
                liveIndexEngine = liveIndexEngine
            )
        } else {
```

- [ ] **Step 4: Compile-check**

Run: `./gradlew compileDebugKotlin`
Expected: Still fails, now only on the `LegacySingleStackNavigation` call in `AppNavigation` (missing the new required parameter) and inside `LegacySingleStackNavigation` itself (Task 8's scope) — confirm no errors remain in `MultiBackStackNavigation` or `JobsTabHost`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/navigation/NavGraph.kt
git commit -m "feat: thread live-index engine through the multi-back-stack jobs path"
```

---

## Task 8: Thread the live engine through the legacy single-stack path

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/navigation/NavGraph.kt`

`LegacySingleStackNavigation` (starts line 1979) has the identical pattern: a local `val unifiedEngine = remember { UnifiedMetadataEngineRegistry.getOrCreate(File(basePath), isDebugBuild) }` at line 2183, used by name at four call sites (2371, 2389, 2406, 2425). Same fix: add a parameter named `unifiedEngine`, delete the local `remember`, no changes needed at the four call sites. Unlike `JobsTabHost`, `AppNavigation` calls this composable directly (no intermediate function), so only one call site needs the new argument.

- [ ] **Step 1: Add a parameter to `LegacySingleStackNavigation` and delete its local engine `remember`**

In `LegacySingleStackNavigation`'s signature (ends at line 2016-2018), change:

```kotlin
    onThemeFollowSyncedDefaultChanged: (Boolean) -> Unit,
    onThemeOverrideChanged: (String?) -> Unit,
    onThemeCatalogReload: () -> Unit
) {
```

to:

```kotlin
    onThemeFollowSyncedDefaultChanged: (Boolean) -> Unit,
    onThemeOverrideChanged: (String?) -> Unit,
    onThemeCatalogReload: () -> Unit,
    unifiedEngine: UnifiedMetadataEngine
) {
```

Then delete this line (2183):

```kotlin
    val unifiedEngine = remember { UnifiedMetadataEngineRegistry.getOrCreate(File(basePath), isDebugBuild) }
```

- [ ] **Step 2: Pass the engine from `AppNavigation` into `LegacySingleStackNavigation`**

In `AppNavigation`'s call to `LegacySingleStackNavigation` (lines 352-391), change its closing:

```kotlin
                onThemeFollowSyncedDefaultChanged = onThemeFollowSyncedDefaultChanged,
                onThemeOverrideChanged = onThemeOverrideChanged,
                onThemeCatalogReload = onThemeCatalogReload
            )
        }
    }
}
```

to:

```kotlin
                onThemeFollowSyncedDefaultChanged = onThemeFollowSyncedDefaultChanged,
                onThemeOverrideChanged = onThemeOverrideChanged,
                onThemeCatalogReload = onThemeCatalogReload,
                unifiedEngine = liveIndexEngine
            )
        }
    }
}
```

- [ ] **Step 3: Compile-check**

Run: `./gradlew compileDebugKotlin`
Expected: PASS — this was the last unwired call site.

- [ ] **Step 4: Run the full unit test suite**

Run: `./gradlew testDebugUnitTest`
Expected: PASS — including the existing `StaticCachePollerTest` (untouched logic, only its start/stop callers changed) and all tests added in Tasks 1-5.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/navigation/NavGraph.kt
git commit -m "feat: thread live-index engine through the legacy single-stack jobs path"
```

---

## Task 9: Deployment verification

**Files:** none (manual verification against a real device/server, no code changes)

This is the design doc's own required verification pass — it cannot be expressed as a unit test because it depends on Hours Tracker's backend and a real tablet on the shop LAN.

- [ ] **Step 1: Build and install a debug APK on a release/test tablet**

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

- [ ] **Step 2: Confirm the socket connects**

With Hours Tracker running and reachable at the tablet's configured admin-sync IP (Settings screen, same IP used for `AdminSyncConfig`), open the Jobs list. Confirm via `adb logcat` (filter on tag `LiveIndexClient`) that no repeated `onFailure`/backoff log lines appear — a healthy connection should show only the initial connect.

- [ ] **Step 3: Confirm a live update**

Trigger a CNC or hardwoods tracker action from another tablet (or via the backend directly) for a job visible on the test tablet's jobs list. Confirm the jobs list's progress indicator updates within one worker refresh cycle, without a manual pull-to-refresh or the 20-second poll interval elapsing.

- [ ] **Step 4: Confirm the fallback path**

Stop the Hours Tracker backend (or block the socket port at the network level). Confirm the jobs list remains populated and usable — reading from `StaticCachePoller`'s file-based path — with no crash and no blank screen. Restart the backend and confirm the tablet reconnects and resumes live updates within the backoff window (30s worst case).

- [ ] **Step 5: Confirm background/foreground behavior**

Background the app (home button) and foreground it again. Confirm the jobs list is still populated immediately, and that a live socket reconnect happens (per the `ON_START`/`ON_STOP` lifecycle wiring in Task 6).

No commit for this task — it's verification only. If any step fails, open a bug against the specific task above rather than patching ad hoc; note the failure in the plan's execution log so the responsible task can be revisited.

---

## Self-Review Notes

- **Spec coverage:** every section of `2026-08-18-live-cache-index-tablet-client-design.md` maps to a task — `LiveIndexClient` behavior (Tasks 1-4), `LiveAwareUnifiedMetadataEngine` behavior (Task 5), wiring (Tasks 6-8), fallback semantics (exercised by Task 9, and by Task 5's `setConnected(false)` test), testing (Tasks 1-5's unit tests plus Task 9's deployment verification list, matching the spec's Testing section item-for-item).
- **Non-goals respected:** no task touches job-detail/viewer screens, no new settings surface is added (Task 6 reuses `AdminSyncConfig`), no tablet-to-server action submission is added, and `StaticCachePoller`'s own polling logic (`app/src/main/java/com/kkc/sheettracker/data/StaticCachePoller.kt`) is never edited — only who calls `start()`/`stop()` on it changes.
- **Type consistency checked:** `CacheIndexRoot`/`CacheIndexJobInfo`/`CacheIndexProgressSummary` (Task 2/5) match the real definitions in `app/src/main/java/com/kkc/sheettracker/data/models/CacheIndexModels.kt`; `UnifiedJobInfo`'s constructor (Task 5) matches `app/src/main/java/com/kkc/sheettracker/data/unified/UnifiedMetadataTypes.kt:19-33`; the sort comparator (Task 5) matches `FileBackedUnifiedMetadataEngine.kt:380-384` exactly; `AdminSyncConfig.getManualIp()`/`buildAdminSyncUrl()` (Task 3) match `app/src/main/java/com/kkc/sheettracker/data/AdminSyncConfig.kt` exactly as they exist today.
- **Known limitation carried forward from planning, not resolved here:** the live socket's server-side "production-visible jobs" filter has no client-visible equivalent of `DeploymentGateRules`'s debug-build allowance for `hiddenFromProduction` jobs (`app/src/main/java/com/kkc/sheettracker/data/DeploymentGate.kt:49-59`). While connected, a debug build will not see hidden-from-production jobs that it would see via the file-based fallback. This is a debug-build-only QA visibility gap, not a release-build correctness issue, and matches the design doc's principle that the client trusts the server's filtering rather than re-implementing it. If debug-build parity is needed later, it requires a separate, explicitly-scoped change to the server-side `hydrate()` call or a client-side gate re-check — out of scope here.
