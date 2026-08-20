# Ready Jobs Archive Library — Tablet Client Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the KKCSheetTracker Android half of the Ready Jobs Archive Library — a read-only Archive tab that connects to the backend's archive-library WebSocket, downloads/caches a selected archived job's complete data, and opens it through the app's existing job screens in a strictly read-only session, plus admin-mode-gated archive/restore trigger buttons.

**Architecture:** New standalone classes under `app/src/main/java/com/kkc/sheettracker/data/` mirror the already-proven `LiveIndexClient`/`AdminSyncClient` patterns exactly — a WebSocket client for the read model, a plain OkHttp+JSON REST client for the mutation triggers. A new `ArchiveSession` wraps the four existing repository/store classes with `readOnly = true` (an already-shipped mechanism, not new plumbing) pointed at an app-private cache directory, so every existing job screen (CNC/Hardwoods/Assembly/Specialty detail, PDF viewer, print flow) works against archived data completely unmodified. A new bottom-nav "Archive" destination and screen are added to both navigation implementations (`MultiBackStackNavigation`, `LegacySingleStackNavigation`).

**Tech Stack:** Kotlin, Jetpack Compose, OkHttp, Gson (WebSocket) / org.json (REST), JUnit4 + mockito-kotlin.

**Spec:** `docs/superpowers/specs/2026-08-12-ready-jobs-archive-design.md` (see "Tablet archive mode" section).

**Depends on:** `docs/superpowers/plans/2026-08-19-ready-jobs-archive-backend.md` (Hours Tracker) — do not start Task 3 (REST client) or later until that plan's Tasks 1-15 are done and its API contract is stable, since this plan's request/response JSON shapes must match exactly what that backend actually serves.

**Scope disclosure:** this plan gets an archived job discoverable, downloadable, and cached in a read-only `ArchiveSession` — but stops short of actually wiring that session into the existing CNC/Hardwoods/Assembly/Specialty detail screens (Task 7's job-detail route is left as an explicit placeholder). The design's "all normal job views for an archive download" requirement is NOT fully met by this plan alone. Wiring each of the four existing, already-complex detail screens to accept an `ArchiveSession` in place of their live `scanCoordinator`/store dependencies is real, separately-sized work — each screen has its own navigation and dependency surface that deserves the same file-by-file tracing this plan gave the nav-graph task, not a rushed pass at the end of an already-large plan. Treat that as this plan's own natural Task 9, to be written once Tasks 1-8 are built and the Archive tab itself is confirmed reachable and downloading correctly.

**Scope disclosure (tablet-triggered archive):** this plan also does not add any tablet-side trigger for archiving a still-live job, even though the design lists it as a peer requirement to tablet-triggered restore ("archive from the Hours Tracker web console and from an administrator-mode tablet; restore from the same two clients"). `ArchiveAdminClient.triggerArchive()` is fully implemented and tested (Task 3) but has zero callers anywhere in the app: `ArchiveLibraryScreen` only exposes a Restore button, for jobs that are already archived, and none of the four live-job detail screens (`JobDetailScreen`, `HardwoodsJobDetailScreen`, `AssemblyJobDetailScreen`, `SpecialtyJobDetailScreen`) has any archive-trigger UI. Deciding where that trigger belongs — a menu action on each live job-detail screen, a swipe/long-press action in the live job list, which of the four screens gets it first — is itself a design decision this plan never made, not just an implementation gap left for a later task. It needs its own scoped follow-up plan, not a bolt-on to Task 3 or Task 6 after the fact.

**Scope disclosure (archive-library HTTP snapshot fallback):** the design requires the tablet archive library to connect "using the existing admin-sync server URL configuration ... with an HTTP snapshot fallback for first connection/reconnect diagnostics." This plan builds only the WebSocket side (`ArchiveLibraryClient`, Task 1) and relies entirely on that client's own auto-reconnect/backoff for every case, including first load. No synchronous HTTP GET of a library snapshot exists anywhere in the tablet code, so there is no fallback path for first-connection or reconnect diagnostics the way the design specifies. In practice, a tablet that can't establish — or that briefly drops — the WebSocket has no secondary way to show or verify the archive list until the socket reconnects on its own; a slow/flaky shop Wi-Fi moment reads as "no archived jobs" or "Connecting…" indefinitely rather than getting a one-shot HTTP snapshot to fall back on. Real remaining work, deferred here rather than silently designed around.

---

### Task 1: `ArchiveJobEntry` model + `ArchiveLibraryClient` WebSocket client

**Files:**
- Create: `app/src/main/java/com/kkc/sheettracker/data/models/ArchiveJobEntry.kt`
- Create: `app/src/main/java/com/kkc/sheettracker/data/ArchiveLibraryClient.kt`
- Test: `app/src/test/java/com/kkc/sheettracker/data/ArchiveLibraryClientTest.kt`

**Why:** Exact structural copy of `LiveIndexClient.kt` (hello/snapshot/delta/reconnect-backoff, injectable `webSocketFactory` for testing), pointed at `/api/ready-jobs-archive/library/live` instead of `/api/ready-jobs-worker/live-index`, carrying the archive-library payload shape instead of `CacheIndexRoot`.

- [ ] **Step 1: Write the failing test**

```kotlin
// app/src/test/java/com/kkc/sheettracker/data/ArchiveLibraryClientTest.kt
package com.kkc.sheettracker.data

import com.kkc.sheettracker.data.models.ArchiveJobEntry
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
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
    on { runBlocking { getManualIp() } } doReturn ip
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd "C:\Scripts\KKCSheetTracker" && .\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.data.ArchiveLibraryClientTest"`
Expected: FAIL — `ArchiveLibraryClient`/`ArchiveJobEntry` don't exist yet.

- [ ] **Step 3: Write the model**

```kotlin
// app/src/main/java/com/kkc/sheettracker/data/models/ArchiveJobEntry.kt
package com.kkc.sheettracker.data.models

data class ArchiveJobEntry(
    val archiveJobId: String,
    val folderName: String,
    val jobNumber: String,
    val jobName: String,
    val archivedAt: String,
    val contentVersion: String
)
```

- [ ] **Step 4: Write `ArchiveLibraryClient.kt`** (structural copy of `LiveIndexClient.kt`)

```kotlin
// app/src/main/java/com/kkc/sheettracker/data/ArchiveLibraryClient.kt
package com.kkc.sheettracker.data

import android.util.Log
import com.google.gson.Gson
import com.kkc.sheettracker.data.models.ArchiveJobEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "ArchiveLibraryClient"

internal fun nextArchiveLibraryBackoffDelayMs(attempt: Int): Long =
    (1_000L shl attempt.coerceIn(0, 5)).coerceAtMost(30_000L)

internal data class ArchiveLibraryDeltaWire(
    val type: String? = null,
    val archiveJobId: String? = null,
    val revision: Long? = null,
    val entry: ArchiveJobEntry? = null
)

internal data class ArchiveLibraryEnvelope(
    val type: String? = null,
    val serverInstanceId: String? = null,
    val revision: Long? = null,
    val archives: Map<String, ArchiveJobEntry>? = null,
    val delta: ArchiveLibraryDeltaWire? = null,
    val message: String? = null
)

class ArchiveLibraryClient(
    private val config: AdminSyncConfig,
    private val tabletId: String,
    private val onSnapshot: (archives: Map<String, ArchiveJobEntry>) -> Unit,
    private val onDelta: (archiveJobId: String, entry: ArchiveJobEntry?) -> Unit,
    private val onConnectionState: (connected: Boolean) -> Unit,
    private val reconnectDelayMs: (attempt: Int) -> Long = ::nextArchiveLibraryBackoffDelayMs,
    private val webSocketFactory: (Request, WebSocketListener) -> WebSocket = { request, listener ->
        sharedClient.newWebSocket(request, listener)
    }
) {
    private val gson = Gson()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var running = false
    @Volatile private var socket: WebSocket? = null
    private val attempt = AtomicInteger(0)
    private val reconnectPending = AtomicBoolean(false)
    private var pendingJob: Job? = null

    companion object {
        private val sharedClient = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(15, TimeUnit.SECONDS)
            .build()
    }

    fun start() {
        if (running) return
        running = true
        attempt.set(0)
        reconnectPending.set(false)
        connectNow()
    }

    fun stop() {
        running = false
        reconnectPending.set(false)
        pendingJob?.cancel()
        pendingJob = null
        socket?.close(1000, "client stop")
        socket = null
    }

    private fun connectNow() {
        val baseUrl = runBlocking { buildAdminSyncUrl(config.getManualIp()) } ?: run {
            scheduleReconnect()
            return
        }
        val wsUrl = baseUrl.replaceFirst("http://", "ws://") + "/api/ready-jobs-archive/library/live"
        val request = Request.Builder().url(wsUrl).build()
        socket = webSocketFactory(request, Listener())
    }

    private fun scheduleReconnect() {
        if (!running) return
        if (!reconnectPending.compareAndSet(false, true)) {
            Log.d(TAG, "Reconnect already pending; ignoring duplicate schedule request")
            return
        }
        val currentAttempt = attempt.getAndIncrement()
        val delayMs = reconnectDelayMs(currentAttempt)
        Log.d(TAG, "Scheduling reconnect: attempt=$currentAttempt delayMs=$delayMs")
        pendingJob = scope.launch {
            kotlinx.coroutines.delay(delayMs)
            reconnectPending.set(false)
            if (running) connectNow()
        }
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocket.send("""{"type":"hello","tabletId":"$tabletId"}""")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val envelope = runCatching { gson.fromJson(text, ArchiveLibraryEnvelope::class.java) }.getOrNull() ?: return
            when (envelope.type) {
                "snapshot" -> {
                    attempt.set(0)
                    onConnectionState(true)
                    onSnapshot(envelope.archives.orEmpty())
                }
                "delta" -> {
                    val delta = envelope.delta ?: return
                    val archiveJobId = delta.archiveJobId ?: return
                    onDelta(archiveJobId, if (delta.type == "remove") null else delta.entry)
                }
                "not_running", "error" -> {
                    onConnectionState(false)
                }
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            onConnectionState(false)
            scheduleReconnect()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            onConnectionState(false)
            scheduleReconnect()
        }
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.data.ArchiveLibraryClientTest"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/data/models/ArchiveJobEntry.kt app/src/main/java/com/kkc/sheettracker/data/ArchiveLibraryClient.kt app/src/test/java/com/kkc/sheettracker/data/ArchiveLibraryClientTest.kt
git commit -m "feat: add ArchiveLibraryClient WebSocket client for the archive-library read model"
```

---

### Task 2: `ArchiveLibraryStore` — in-memory snapshot/delta reducer

**Files:**
- Create: `app/src/main/java/com/kkc/sheettracker/data/ArchiveLibraryStore.kt`
- Test: `app/src/test/java/com/kkc/sheettracker/data/ArchiveLibraryStoreTest.kt`

**Why:** `ArchiveLibraryClient` delivers raw snapshot/delta events; something needs to reduce those into a `StateFlow<List<ArchiveJobEntry>>` the Archive screen can `collectAsState()` directly, plus track connection state. Deliberately much simpler than `LiveAwareUnifiedMetadataEngine` (no file-backed fallback delegate needed — there's no local/offline archive data to fall back to; when disconnected, the list is simply whatever was last known, which is the correct behavior per the design's "labels expired/not-present archive cache as unavailable" rule applying to individual cached entries, not the list itself).

- [ ] **Step 1: Write the failing test**

```kotlin
// app/src/test/java/com/kkc/sheettracker/data/ArchiveLibraryStoreTest.kt
package com.kkc.sheettracker.data

import com.kkc.sheettracker.data.models.ArchiveJobEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchiveLibraryStoreTest {

    private fun entry(id: String) = ArchiveJobEntry(id, id, "100", "Alpha", "2026-08-19T00:00:00Z", "v1")

    @Test
    fun `applySnapshot replaces the full list and marks connected`() {
        val store = ArchiveLibraryStore()
        store.applySnapshot(mapOf("100 - Alpha" to entry("100 - Alpha")))
        assertEquals(listOf(entry("100 - Alpha")), store.entries.value)
        assertTrue(store.connected.value)
    }

    @Test
    fun `applyDelta upserts a new entry`() {
        val store = ArchiveLibraryStore()
        store.applySnapshot(emptyMap())
        store.applyDelta("100 - Alpha", entry("100 - Alpha"))
        assertEquals(listOf(entry("100 - Alpha")), store.entries.value)
    }

    @Test
    fun `applyDelta with null entry removes it`() {
        val store = ArchiveLibraryStore()
        store.applySnapshot(mapOf("100 - Alpha" to entry("100 - Alpha")))
        store.applyDelta("100 - Alpha", null)
        assertEquals(emptyList<ArchiveJobEntry>(), store.entries.value)
    }

    @Test
    fun `setConnected false clears the connected flag but keeps the last known entries`() {
        val store = ArchiveLibraryStore()
        store.applySnapshot(mapOf("100 - Alpha" to entry("100 - Alpha")))
        store.setConnected(false)
        assertFalse(store.connected.value)
        assertEquals(listOf(entry("100 - Alpha")), store.entries.value)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.data.ArchiveLibraryStoreTest"`
Expected: FAIL — `ArchiveLibraryStore` doesn't exist.

- [ ] **Step 3: Write the implementation**

```kotlin
// app/src/main/java/com/kkc/sheettracker/data/ArchiveLibraryStore.kt
package com.kkc.sheettracker.data

import com.kkc.sheettracker.data.models.ArchiveJobEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

class ArchiveLibraryStore {
    private val liveMap = ConcurrentHashMap<String, ArchiveJobEntry>()
    private val _entries = MutableStateFlow<List<ArchiveJobEntry>>(emptyList())
    val entries: StateFlow<List<ArchiveJobEntry>> = _entries.asStateFlow()
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    fun applySnapshot(archives: Map<String, ArchiveJobEntry>) {
        liveMap.clear()
        liveMap.putAll(archives)
        publish()
        _connected.value = true
    }

    fun applyDelta(archiveJobId: String, entry: ArchiveJobEntry?) {
        if (entry == null) liveMap.remove(archiveJobId) else liveMap[archiveJobId] = entry
        publish()
    }

    fun setConnected(value: Boolean) {
        _connected.value = value
    }

    private fun publish() {
        _entries.value = liveMap.values.sortedBy { it.jobNumber }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.data.ArchiveLibraryStoreTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/data/ArchiveLibraryStore.kt app/src/test/java/com/kkc/sheettracker/data/ArchiveLibraryStoreTest.kt
git commit -m "feat: add ArchiveLibraryStore snapshot/delta reducer"
```

---

### Task 3: `ArchiveAdminClient` — REST trigger client

**Files:**
- Create: `app/src/main/java/com/kkc/sheettracker/data/ArchiveAdminClient.kt`
- Test: `app/src/test/java/com/kkc/sheettracker/data/ArchiveAdminClientTest.kt`

**Why:** Exact structural copy of `AdminSyncClient.kt`'s pattern (plain OkHttp + `org.json.JSONObject`, `runCatching { ... }.getOrNull()`, one shared `OkHttpClient` with a short timeout) — for the archive/restore collision-preview and trigger endpoints. **Do not start this task until the backend plan's Task 15 (`routes/ready_jobs_archive_lifecycle.py`) is done** — the request/response JSON shapes here must match that route file exactly.

- [ ] **Step 1: Write the failing test**

```kotlin
// app/src/test/java/com/kkc/sheettracker/data/ArchiveAdminClientTest.kt
package com.kkc.sheettracker.data

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import kotlinx.coroutines.runBlocking

class ArchiveAdminClientTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `previewArchiveCollision parses a no-collision response`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"collision":false,"validResolutions":[]}""").setResponseCode(200))
        val client = ArchiveAdminClient(server.url("/").toString())
        val result = client.previewArchiveCollision("100 - Alpha")
        assertEquals(false, result?.collision)
    }

    @Test
    fun `previewArchiveCollision returns null on non-2xx`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))
        val client = ArchiveAdminClient(server.url("/").toString())
        assertNull(client.previewArchiveCollision("100 - Alpha"))
    }

    @Test
    fun `triggerArchive posts the request and returns the operationId`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"operationId":"op-123"}""").setResponseCode(202))
        val client = ArchiveAdminClient(server.url("/").toString())
        val operationId = client.triggerArchive(
            folderName = "100 - Alpha", initiator = "tablet-7", collisionChoice = "timestamp",
        )
        assertEquals("op-123", operationId)
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals(true, recorded.path?.contains("archive/100%20-%20Alpha") == true || recorded.path?.contains("archive/100 - Alpha") == true)
    }

    @Test
    fun `getOperationStatus parses state`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"operationId":"op-123","state":"succeeded"}""").setResponseCode(200))
        val client = ArchiveAdminClient(server.url("/").toString())
        val status = client.getOperationStatus("op-123")
        assertEquals("succeeded", status?.state)
    }
}
```

Add the `mockwebserver` test dependency if not already present — check `app/build.gradle.kts`'s `dependencies {}` block for `com.squareup.okhttp3:mockwebserver`; if absent, add `testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")` (match the existing `okhttp3` version already used elsewhere in that same file — use the same version string, don't introduce a second OkHttp version).

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.data.ArchiveAdminClientTest"`
Expected: FAIL — `ArchiveAdminClient` doesn't exist (and/or a missing mockwebserver dependency error, resolved by Step 1's gradle change).

- [ ] **Step 3: Write the implementation**

```kotlin
// app/src/main/java/com/kkc/sheettracker/data/ArchiveAdminClient.kt
package com.kkc.sheettracker.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class CollisionPreview(val collision: Boolean, val validResolutions: List<String>)
data class OperationStatus(val operationId: String, val state: String, val errorSummary: String?)

/**
 * REST trigger client for archive/restore, structural copy of
 * AdminSyncClient's direct-write pattern: every method returns null on
 * any failure (timeout, connection refused, non-2xx) rather than
 * throwing -- callers must treat null as "could not complete right
 * now," matching how AdminSyncClient's own callers already handle a
 * null/false result.
 */
class ArchiveAdminClient(serverUrl: String) {
    private val baseUrl = serverUrl.trimEnd('/')
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    companion object {
        private val client = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    private fun encodedFolder(folderName: String): String = URLEncoder.encode(folderName, "UTF-8")

    suspend fun previewArchiveCollision(folderName: String): CollisionPreview? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$baseUrl/api/ready-jobs-archive/archive/${encodedFolder(folderName)}/collision-preview")
            .post("".toRequestBody(null))
            .build()
        runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val obj = JSONObject(response.body?.string() ?: return@use null)
                CollisionPreview(
                    collision = obj.getBoolean("collision"),
                    validResolutions = obj.optJSONArray("validResolutions")?.let { arr ->
                        (0 until arr.length()).map { arr.getString(it) }
                    }.orEmpty(),
                )
            }
        }.getOrNull()
    }

    suspend fun previewRestoreCollision(folderName: String): CollisionPreview? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$baseUrl/api/ready-jobs-archive/restore/${encodedFolder(folderName)}/collision-preview")
            .post("".toRequestBody(null))
            .build()
        runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val obj = JSONObject(response.body?.string() ?: return@use null)
                CollisionPreview(
                    collision = obj.getBoolean("collision"),
                    validResolutions = obj.optJSONArray("validResolutions")?.let { arr ->
                        (0 until arr.length()).map { arr.getString(it) }
                    }.orEmpty(),
                )
            }
        }.getOrNull()
    }

    private suspend fun trigger(
        direction: String, folderName: String, initiator: String, collisionChoice: String,
        renameTo: String?, overwriteConfirmed: Boolean,
    ): String? = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("initiator", initiator)
            put("collisionChoice", collisionChoice)
            renameTo?.let { put("renameTo", it) }
            put("overwriteConfirmed", overwriteConfirmed)
        }.toString().toRequestBody(jsonMediaType)
        val request = Request.Builder()
            .url("$baseUrl/api/ready-jobs-archive/$direction/${encodedFolder(folderName)}")
            .post(body)
            .build()
        runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                JSONObject(response.body?.string() ?: return@use null).getString("operationId")
            }
        }.getOrNull()
    }

    suspend fun triggerArchive(
        folderName: String, initiator: String, collisionChoice: String,
        renameTo: String? = null, overwriteConfirmed: Boolean = false,
    ): String? = trigger("archive", folderName, initiator, collisionChoice, renameTo, overwriteConfirmed)

    suspend fun triggerRestore(
        folderName: String, initiator: String, collisionChoice: String,
        renameTo: String? = null, overwriteConfirmed: Boolean = false,
    ): String? = trigger("restore", folderName, initiator, collisionChoice, renameTo, overwriteConfirmed)

    suspend fun getOperationStatus(operationId: String): OperationStatus? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$baseUrl/api/ready-jobs-worker/operations/$operationId")
            .get()
            .build()
        runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val obj = JSONObject(response.body?.string() ?: return@use null)
                OperationStatus(
                    operationId = obj.getString("operationId"),
                    state = obj.getString("state"),
                    errorSummary = obj.optString("errorSummary").takeIf { it.isNotBlank() },
                )
            }
        }.getOrNull()
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.data.ArchiveAdminClientTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/build.gradle.kts app/src/main/java/com/kkc/sheettracker/data/ArchiveAdminClient.kt app/src/test/java/com/kkc/sheettracker/data/ArchiveAdminClientTest.kt
git commit -m "feat: add ArchiveAdminClient REST trigger client for archive/restore"
```

---

### Task 4: `ArchiveCacheManager` — package download, ZIP validation, atomic cache promotion

**Files:**
- Create: `app/src/main/java/com/kkc/sheettracker/data/ArchiveCacheManager.kt`
- Test: `app/src/test/java/com/kkc/sheettracker/data/ArchiveCacheManagerTest.kt`

**Why:** No existing download-to-storage or ZIP-extraction code exists anywhere in this app (confirmed during design research). Written from scratch: streams `GET /library/{archiveJobId}/package`, validates the manifest (every extracted file's SHA-256 matches, no unexpected extra/missing files), rejects zip-slip and symlink entries, extracts to a unique incomplete directory, then atomically promotes it to the cache entry only after full validation — matching the design's exact "An incomplete, corrupt, or cancelled entry is never passed to a job screen" requirement.

- [ ] **Step 1: Write the failing test**

```kotlin
// app/src/test/java/com/kkc/sheettracker/data/ArchiveCacheManagerTest.kt
package com.kkc.sheettracker.data

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private fun buildTestZip(files: Map<String, ByteArray>): ByteArray {
    val out = ByteArrayOutputStream()
    ZipOutputStream(out).use { zip ->
        val manifestEntries = files.entries.map { (path, data) ->
            val sha256 = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
            """{"path":"$path","size":${data.size},"sha256":"$sha256"}"""
        }
        files.forEach { (path, data) ->
            zip.putNextEntry(ZipEntry(path))
            zip.write(data)
            zip.closeEntry()
        }
        val manifestJson = """{"files":[${manifestEntries.joinToString(",")}]}"""
        zip.putNextEntry(ZipEntry("manifest.json"))
        zip.write(manifestJson.toByteArray())
        zip.closeEntry()
    }
    return out.toByteArray()
}

class ArchiveCacheManagerTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `downloadAndExtract validates manifest and promotes a complete cache entry`() = runBlocking {
        val zipBytes = buildTestZip(mapOf("cover.pdf" to "pdf-bytes".toByteArray()))
        server.enqueue(MockResponse().setBody(okio.Buffer().write(zipBytes)).setResponseCode(200))
        val cacheRoot = Files.createTempDirectory("archive-cache-test").toFile()
        val manager = ArchiveCacheManager(cacheRoot, server.url("/").toString())

        val result = manager.downloadAndExtract(archiveJobId = "100 - Alpha", folderName = "100 - Alpha", contentVersion = "v1")

        assertTrue(result is ArchiveCacheResult.Success)
        val jobDir = (result as ArchiveCacheResult.Success).jobDir
        assertEquals("pdf-bytes", jobDir.resolve("cover.pdf").readText())
    }

    @Test
    fun `downloadAndExtract fails when a file's hash does not match the manifest`() = runBlocking {
        val zipBytes = buildTestZip(mapOf("cover.pdf" to "pdf-bytes".toByteArray()))
        // Corrupt the manifest's declared hash by rebuilding with a wrong hash for the same content.
        val corrupted = String(zipBytes, Charsets.ISO_8859_1)
            .replace(Regex("\"sha256\":\"[0-9a-f]+\""), "\"sha256\":\"deadbeef\"")
            .toByteArray(Charsets.ISO_8859_1)
        server.enqueue(MockResponse().setBody(okio.Buffer().write(corrupted)).setResponseCode(200))
        val cacheRoot = Files.createTempDirectory("archive-cache-test").toFile()
        val manager = ArchiveCacheManager(cacheRoot, server.url("/").toString())

        val result = manager.downloadAndExtract(archiveJobId = "100 - Alpha", folderName = "100 - Alpha", contentVersion = "v1")

        assertTrue(result is ArchiveCacheResult.Failure)
    }

    @Test
    fun `downloadAndExtract rejects a zip-slip entry`() = runBlocking {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("../../evil.txt"))
            zip.write("evil".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write("""{"files":[]}""".toByteArray())
            zip.closeEntry()
        }
        server.enqueue(MockResponse().setBody(okio.Buffer().write(out.toByteArray())).setResponseCode(200))
        val cacheRoot = Files.createTempDirectory("archive-cache-test").toFile()
        val manager = ArchiveCacheManager(cacheRoot, server.url("/").toString())

        val result = manager.downloadAndExtract(archiveJobId = "100 - Alpha", folderName = "100 - Alpha", contentVersion = "v1")

        assertTrue(result is ArchiveCacheResult.Failure)
        assertTrue(!cacheRoot.resolve("../../evil.txt").exists())
    }

    @Test
    fun `downloadAndExtract on http failure returns Failure without leaving a partial entry`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))
        val cacheRoot = Files.createTempDirectory("archive-cache-test").toFile()
        val manager = ArchiveCacheManager(cacheRoot, server.url("/").toString())

        val result = manager.downloadAndExtract(archiveJobId = "100 - Alpha", folderName = "100 - Alpha", contentVersion = "v1")

        assertTrue(result is ArchiveCacheResult.Failure)
        assertEquals(0, cacheRoot.listFiles()?.count { it.name == "100 - Alpha" } ?: 0)
    }

    @Test
    fun `getCachedEntry returns null when nothing is cached`() {
        val cacheRoot = Files.createTempDirectory("archive-cache-test").toFile()
        val manager = ArchiveCacheManager(cacheRoot, "http://unused")
        assertNull(manager.getCachedEntry("100 - Alpha"))
    }

    @Test
    fun `pruneExpiredEntries removes an entry whose last access is older than 24 hours`() = runBlocking {
        val zipBytes = buildTestZip(mapOf("cover.pdf" to "pdf-bytes".toByteArray()))
        server.enqueue(MockResponse().setBody(okio.Buffer().write(zipBytes)).setResponseCode(200))
        val cacheRoot = Files.createTempDirectory("archive-cache-test").toFile()
        val manager = ArchiveCacheManager(cacheRoot, server.url("/").toString())
        manager.downloadAndExtract(archiveJobId = "100 - Alpha", folderName = "100 - Alpha", contentVersion = "v1")

        manager.pruneExpiredEntries(nowMs = System.currentTimeMillis() + 25L * 60 * 60 * 1000)

        assertNull(manager.getCachedEntry("100 - Alpha"))
    }
}
```

Add `testImplementation("com.squareup.okio:okio:...")` only if not already transitively available for test use of `okio.Buffer()` in `MockResponse().setBody(Buffer)` — `okhttp3.mockwebserver` already depends on okio, so this should already resolve; if the test fails to compile on `okio.Buffer`, add the explicit test dependency matching whatever okio version `mockwebserver`'s POM already pulls in.

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.data.ArchiveCacheManagerTest"`
Expected: FAIL — `ArchiveCacheManager` doesn't exist.

- [ ] **Step 3: Write the implementation**

```kotlin
// app/src/main/java/com/kkc/sheettracker/data/ArchiveCacheManager.kt
package com.kkc.sheettracker.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

sealed class ArchiveCacheResult {
    data class Success(val jobDir: File) : ArchiveCacheResult()
    data class Failure(val reason: String) : ArchiveCacheResult()
}

data class ArchiveCacheEntry(
    val archiveJobId: String,
    val folderName: String,
    val contentVersion: String,
    val jobDir: File,
    val lastAccessMs: Long,
)

private const val CACHE_EXPIRY_MS = 24L * 60 * 60 * 1000
private const val MANIFEST_FILE_NAME = ".archive_cache_manifest.json"

/**
 * Downloads GET /library/{archiveJobId}/package, validates every file's
 * SHA-256 against the ZIP's own manifest.json entry, rejects zip-slip
 * and absolute-path entries, extracts to a unique "<id>.incomplete"
 * directory, then atomically renames it to the final cache entry only
 * once validation passes -- an incomplete/corrupt/cancelled download
 * never becomes a usable cache entry. No existing code in this app
 * does a byte-stream download-to-storage; written from scratch.
 */
class ArchiveCacheManager(private val cacheRoot: File, private val serverUrl: String) {
    companion object {
        private val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    init {
        cacheRoot.mkdirs()
    }

    suspend fun downloadAndExtract(
        archiveJobId: String, folderName: String, contentVersion: String,
    ): ArchiveCacheResult = withContext(Dispatchers.IO) {
        val baseUrl = serverUrl.trimEnd('/')
        val request = Request.Builder()
            .url("$baseUrl/api/ready-jobs-archive/library/${URLEncoder.encode(archiveJobId, "UTF-8")}/package")
            .get()
            .build()

        val incomplete = File(cacheRoot, "${UUID.randomUUID()}.incomplete")
        incomplete.mkdirs()
        try {
            val response = runCatching { client.newCall(request).execute() }.getOrNull()
                ?: return@withContext ArchiveCacheResult.Failure("network error")
            response.use { resp ->
                if (!resp.isSuccessful) return@withContext ArchiveCacheResult.Failure("http ${resp.code}")
                val body = resp.body ?: return@withContext ArchiveCacheResult.Failure("empty response body")

                val declaredHashes = mutableMapOf<String, Pair<Long, String>>()
                val extractedFiles = mutableSetOf<String>()

                ZipInputStream(body.byteStream()).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        val name = entry.name
                        if (name == "manifest.json") {
                            val manifestJson = zip.readBytes().toString(Charsets.UTF_8)
                            JSONObject(manifestJson).getJSONArray("files").let { arr ->
                                for (i in 0 until arr.length()) {
                                    val fileEntry = arr.getJSONObject(i)
                                    declaredHashes[fileEntry.getString("path")] =
                                        fileEntry.getLong("size") to fileEntry.getString("sha256")
                                }
                            }
                        } else if (!entry.isDirectory) {
                            val destination = resolveSafeEntryPath(incomplete, name)
                                ?: return@withContext ArchiveCacheResult.Failure("unsafe zip entry: $name")
                            destination.parentFile?.mkdirs()
                            val digest = MessageDigest.getInstance("SHA-256")
                            destination.outputStream().use { out ->
                                val buffer = ByteArray(8192)
                                var read = zip.read(buffer)
                                while (read >= 0) {
                                    out.write(buffer, 0, read)
                                    digest.update(buffer, 0, read)
                                    read = zip.read(buffer)
                                }
                            }
                            extractedFiles += name
                            val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
                            val declared = declaredHashes[name]
                            if (declared != null && declared.second != actualHash) {
                                return@withContext ArchiveCacheResult.Failure("hash mismatch for $name")
                            }
                        }
                        entry = zip.nextEntry
                    }
                }

                val missing = declaredHashes.keys - extractedFiles
                if (missing.isNotEmpty()) {
                    return@withContext ArchiveCacheResult.Failure("manifest declared files missing from archive: $missing")
                }
                for ((path, expected) in declaredHashes) {
                    val actualHash = MessageDigest.getInstance("SHA-256")
                        .digest(resolveSafeEntryPath(incomplete, path)!!.readBytes())
                        .joinToString("") { "%02x".format(it) }
                    if (actualHash != expected.second) {
                        return@withContext ArchiveCacheResult.Failure("hash mismatch for $path")
                    }
                }
            }

            val finalDir = File(cacheRoot, archiveJobId)
            if (finalDir.exists()) finalDir.deleteRecursively()
            val jobDir = File(finalDir, folderName)
            finalDir.mkdirs()
            if (!incomplete.renameTo(jobDir)) {
                return@withContext ArchiveCacheResult.Failure("could not promote incomplete download")
            }
            writeManifest(finalDir, ArchiveCacheEntry(archiveJobId, folderName, contentVersion, jobDir, System.currentTimeMillis()))
            ArchiveCacheResult.Success(jobDir)
        } finally {
            if (incomplete.exists()) incomplete.deleteRecursively()
        }
    }

    /** Resolves a ZIP entry name safely under `root` -- rejects any
     * entry whose resolved path escapes root (zip-slip: "../", absolute
     * paths, drive-letter paths). Returns null if unsafe. */
    private fun resolveSafeEntryPath(root: File, entryName: String): File? {
        val candidate = File(root, entryName).canonicalFile
        val rootCanonical = root.canonicalFile
        return if (candidate.path.startsWith(rootCanonical.path + File.separator)) candidate else null
    }

    private fun writeManifest(finalDir: File, entry: ArchiveCacheEntry) {
        val manifest = JSONObject().apply {
            put("archiveJobId", entry.archiveJobId)
            put("folderName", entry.folderName)
            put("contentVersion", entry.contentVersion)
            put("completedAtMs", entry.lastAccessMs)
            put("lastAccessMs", entry.lastAccessMs)
        }
        File(finalDir, MANIFEST_FILE_NAME).writeText(manifest.toString())
    }

    fun getCachedEntry(archiveJobId: String): ArchiveCacheEntry? {
        val finalDir = File(cacheRoot, archiveJobId)
        val manifestFile = File(finalDir, MANIFEST_FILE_NAME)
        if (!manifestFile.exists()) return null
        val manifest = runCatching { JSONObject(manifestFile.readText()) }.getOrNull() ?: return null
        val folderName = manifest.optString("folderName").takeIf { it.isNotBlank() } ?: return null
        val jobDir = File(finalDir, folderName)
        if (!jobDir.isDirectory) return null
        return ArchiveCacheEntry(
            archiveJobId = archiveJobId,
            folderName = folderName,
            contentVersion = manifest.optString("contentVersion"),
            jobDir = jobDir,
            lastAccessMs = manifest.optLong("lastAccessMs"),
        )
    }

    fun touchLastAccess(archiveJobId: String) {
        val entry = getCachedEntry(archiveJobId) ?: return
        writeManifest(File(cacheRoot, archiveJobId), entry.copy(lastAccessMs = System.currentTimeMillis()))
    }

    fun pruneExpiredEntries(nowMs: Long = System.currentTimeMillis()) {
        val entries = cacheRoot.listFiles { file -> file.isDirectory && !file.name.endsWith(".incomplete") } ?: return
        for (dir in entries) {
            val entry = getCachedEntry(dir.name) ?: continue
            if (nowMs - entry.lastAccessMs > CACHE_EXPIRY_MS) {
                dir.deleteRecursively()
            }
        }
    }

    fun removeCachedEntry(archiveJobId: String) {
        File(cacheRoot, archiveJobId).deleteRecursively()
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.data.ArchiveCacheManagerTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/data/ArchiveCacheManager.kt app/src/test/java/com/kkc/sheettracker/data/ArchiveCacheManagerTest.kt
git commit -m "feat: add ArchiveCacheManager for package download, ZIP validation, and 24h cache expiry"
```

---

### Task 5: `ArchiveSession` — read-only session descriptor

**Files:**
- Create: `app/src/main/java/com/kkc/sheettracker/data/ArchiveSession.kt`
- Test: `app/src/test/java/com/kkc/sheettracker/data/ArchiveSessionTest.kt`

**Why:** Confirmed during design research: `ProgressStore`, `HardwoodsProgressStore`, `SpecialtyProgressStore`, and `PdfMarkupStore` already have a `readOnly: Boolean = false` constructor parameter that blocks every write path while leaving reads untouched — the exact mechanism `MainActivity.kt` already uses for view-only mode (`readOnly = isViewOnlyMode`). This task builds the thin wrapper that constructs all four against an archive cache directory with `readOnly = true`, plus a `UnifiedMetadataEngine` via the existing registry (also confirmed portable to an arbitrary `baseDir` with no live-root assumption). No new "archive-aware" store classes are needed.

- [ ] **Step 1: Write the failing test**

```kotlin
// app/src/test/java/com/kkc/sheettracker/data/ArchiveSessionTest.kt
package com.kkc.sheettracker.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class ArchiveSessionTest {
    @Test
    fun `session is constructed with readOnly stores pointed at the cache job dir`() {
        val cacheRoot = Files.createTempDirectory("archive-session-test").toFile()
        val jobDir = cacheRoot.resolve("100 - Alpha").also { it.mkdirs() }

        val session = ArchiveSession.create(
            archiveJobId = "100 - Alpha",
            contentVersion = "v1",
            cacheJobParentDir = cacheRoot,
            tabletId = "tablet-7",
            isDebugBuild = true,
        )

        assertEquals("100 - Alpha", session.archiveJobId)
        assertEquals("v1", session.contentVersion)
        assertTrue(session.readOnly)
        assertEquals(cacheRoot, session.baseDir)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.data.ArchiveSessionTest"`
Expected: FAIL — `ArchiveSession` doesn't exist.

- [ ] **Step 3: Write the implementation**

```kotlin
// app/src/main/java/com/kkc/sheettracker/data/ArchiveSession.kt
package com.kkc.sheettracker.data

import com.kkc.sheettracker.data.unified.UnifiedMetadataEngine
import com.kkc.sheettracker.data.unified.UnifiedMetadataEngineRegistry
import java.io.File

/**
 * Read-only session wrapping every store an archive job screen might
 * need, all constructed against the SAME app-private cache directory
 * with readOnly = true -- the already-shipped mechanism ProgressStore/
 * HardwoodsProgressStore/SpecialtyProgressStore/PdfMarkupStore all
 * expose (see MainActivity's own isViewOnlyMode wiring for the live
 * precedent). No archive-specific write-blocking logic is invented
 * here; this class only wires existing constructors correctly.
 *
 * baseDir is the archive cache job's PARENT directory (not the job
 * folder itself) -- UnifiedMetadataEngineRegistry.listJobs() scans
 * every direct child of baseDir as a job, so baseDir must contain
 * exactly the one archived job folder, never a shared multi-entry
 * cache root, or opening one archive would list every other cached
 * archive entry as a sibling "job."
 */
class ArchiveSession private constructor(
    val archiveJobId: String,
    val contentVersion: String,
    val baseDir: File,
    val folderName: String,
    val readOnly: Boolean,
    val tabletId: String,
    val progressStore: ProgressStore,
    val hardwoodsProgressStore: HardwoodsProgressStore,
    val specialtyProgressStore: SpecialtyProgressStore,
    val pdfMarkupStore: PdfMarkupStore,
    val unifiedEngine: UnifiedMetadataEngine,
) {
    companion object {
        fun create(
            archiveJobId: String,
            contentVersion: String,
            cacheJobParentDir: File,
            tabletId: String,
            isDebugBuild: Boolean,
        ): ArchiveSession {
            val folderName = cacheJobParentDir.listFiles { f -> f.isDirectory }
                ?.firstOrNull()?.name ?: archiveJobId
            val localStateDir = File(cacheJobParentDir, ".state").also { it.mkdirs() }
            return ArchiveSession(
                archiveJobId = archiveJobId,
                contentVersion = contentVersion,
                baseDir = cacheJobParentDir,
                folderName = folderName,
                readOnly = true,
                tabletId = tabletId,
                progressStore = ProgressStore(
                    baseDir = cacheJobParentDir, tabletId = tabletId,
                    localStateDir = localStateDir, readOnly = true,
                ),
                hardwoodsProgressStore = HardwoodsProgressStore(
                    baseDir = cacheJobParentDir, tabletId = tabletId, readOnly = true,
                ),
                specialtyProgressStore = SpecialtyProgressStore(
                    baseDir = cacheJobParentDir, tabletId = tabletId, readOnly = true,
                ),
                pdfMarkupStore = PdfMarkupStore(
                    baseDir = cacheJobParentDir, tabletId = tabletId, readOnly = true,
                ),
                unifiedEngine = UnifiedMetadataEngineRegistry.getOrCreate(
                    baseDir = cacheJobParentDir, isDebugBuild = isDebugBuild,
                ),
            )
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.data.ArchiveSessionTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/data/ArchiveSession.kt app/src/test/java/com/kkc/sheettracker/data/ArchiveSessionTest.kt
git commit -m "feat: add ArchiveSession read-only store wiring for archive job screens"
```

---

### Task 6: `ArchiveLibraryScreen` — list, download, open, archive/restore triggers

**Files:**
- Create: `app/src/main/java/com/kkc/sheettracker/ui/archive/ArchiveLibraryScreen.kt`

**Why:** The user-facing screen. Connects `ArchiveLibraryClient` → `ArchiveLibraryStore`, renders the list, downloads+extracts via `ArchiveCacheManager` on tap, then hands off to the job-detail navigation (Task 7). Archive/restore trigger buttons are gated on `AdminModeController.enabled`, per the design.

This task is UI-only (no automated test — no Compose UI test infra exists in this repo, confirmed during design research; same disclosed reasoning as the earlier dashboard-wiring plan's Hardwoods-dashboard task). Verify manually per Task 8.

- [ ] **Step 1: Write the screen**

```kotlin
// app/src/main/java/com/kkc/sheettracker/ui/archive/ArchiveLibraryScreen.kt
package com.kkc.sheettracker.ui.archive

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.kkc.sheettracker.data.AdminModeController
import com.kkc.sheettracker.data.AdminSyncConfig
import com.kkc.sheettracker.data.ArchiveAdminClient
import com.kkc.sheettracker.data.ArchiveCacheManager
import com.kkc.sheettracker.data.ArchiveCacheResult
import com.kkc.sheettracker.data.ArchiveLibraryClient
import com.kkc.sheettracker.data.ArchiveLibraryStore
import com.kkc.sheettracker.data.buildAdminSyncUrl
import com.kkc.sheettracker.data.models.ArchiveJobEntry
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun ArchiveLibraryScreen(
    tabletId: String,
    isDebugBuild: Boolean,
    onOpenArchiveJob: (archiveJobId: String, folderName: String, contentVersion: String) -> Unit,
) {
    val context = LocalContext.current
    val adminEnabled by AdminModeController.enabled.collectAsState()
    val store = remember { ArchiveLibraryStore() }
    val entries by store.entries.collectAsState()
    val connected by store.connected.collectAsState()
    val scope = rememberCoroutineScope()
    val adminSyncConfig = remember { AdminSyncConfig.create(context) }

    val client = remember {
        ArchiveLibraryClient(
            config = adminSyncConfig,
            tabletId = tabletId,
            onSnapshot = { store.applySnapshot(it) },
            onDelta = { id, entry -> store.applyDelta(id, entry) },
            onConnectionState = { store.setConnected(it) },
        )
    }
    androidx.compose.runtime.DisposableEffect(client) {
        client.start()
        onDispose { client.stop() }
    }

    var downloadingArchiveJobId by remember { mutableStateOf<String?>(null) }
    var downloadError by remember { mutableStateOf<String?>(null) }

    fun openArchive(entry: ArchiveJobEntry) {
        scope.launch {
            downloadingArchiveJobId = entry.archiveJobId
            downloadError = null
            val serverUrl = kotlinx.coroutines.runBlocking { adminSyncConfig.getServerUrl() }
            if (serverUrl == null) {
                downloadError = "No server configured"
                downloadingArchiveJobId = null
                return@launch
            }
            val cacheRoot = File(context.filesDir, "archive-cache")
            val manager = ArchiveCacheManager(cacheRoot, serverUrl)
            val cached = manager.getCachedEntry(entry.archiveJobId)
            if (cached != null && cached.contentVersion == entry.contentVersion) {
                manager.touchLastAccess(entry.archiveJobId)
                onOpenArchiveJob(entry.archiveJobId, cached.folderName, entry.contentVersion)
                downloadingArchiveJobId = null
                return@launch
            }
            when (val result = manager.downloadAndExtract(entry.archiveJobId, entry.folderName, entry.contentVersion)) {
                is ArchiveCacheResult.Success -> {
                    onOpenArchiveJob(entry.archiveJobId, entry.folderName, entry.contentVersion)
                }
                is ArchiveCacheResult.Failure -> {
                    downloadError = result.reason
                }
            }
            downloadingArchiveJobId = null
        }
    }

    Scaffold { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Text(
                if (connected) "Archive Library" else "Archive Library (disconnected)",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(16.dp),
            )
            downloadError?.let {
                Text("Download failed: $it", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp))
            }
            if (entries.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(if (connected) "No archived jobs" else "Connecting…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(entries, key = { it.archiveJobId }) { entry ->
                        Card(
                            modifier = Modifier.fillMaxSize(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        ) {
                            Column(Modifier.padding(16.dp)) {
                                Text("${entry.jobNumber} - ${entry.jobName}", style = MaterialTheme.typography.titleMedium)
                                Text("Archived: ${entry.archivedAt}", style = MaterialTheme.typography.bodySmall)
                                if (downloadingArchiveJobId == entry.archiveJobId) {
                                    CircularProgressIndicator(modifier = Modifier.padding(top = 8.dp))
                                } else {
                                    TextButton(onClick = { openArchive(entry) }) { Text("Open") }
                                }
                                if (adminEnabled) {
                                    RestoreButton(entry, tabletId, adminSyncConfig)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RestoreButton(entry: ArchiveJobEntry, tabletId: String, adminSyncConfig: AdminSyncConfig) {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    TextButton(onClick = {
        scope.launch {
            val serverUrl = adminSyncConfig.getServerUrl() ?: return@launch
            val client = ArchiveAdminClient(serverUrl)
            val preview = client.previewRestoreCollision(entry.folderName)
            val choice = if (preview?.collision == true) "timestamp" else "timestamp"
            val operationId = client.triggerRestore(entry.folderName, tabletId, choice)
            status = if (operationId != null) "Restore queued" else "Restore failed to queue"
        }
    }) {
        Text(status ?: "Restore")
    }
}
```

Note: `RestoreButton`'s collision handling is deliberately minimal (always falls back to `timestamp` on collision, no rename/overwrite dialog) — a fuller collision-resolution dialog is straightforward follow-up UI work once this base flow is verified working end-to-end; not blocking for this plan's first release, matching the design's own "first release" scoping language (it lists archive/restore as in-scope but doesn't mandate every collision UI affordance in the first pass).

- [ ] **Step 2: Compile-check**

Run: `cd "C:\Scripts\KKCSheetTracker" && .\gradlew.bat :app:compileDebugKotlin`
Expected: this will fail until `AdminSyncConfig.create(context)` and `buildAdminSyncUrl` imports resolve correctly against the real `AdminSyncConfig.kt` API — verify the exact companion-object factory method name and the `getServerUrl()` suspend function referenced in this task actually exist (confirmed present in `AdminSyncConfig.kt` per design research: `suspend fun getServerUrl(): String? = buildAdminSyncUrl(getManualIp())`, `companion object { fun create(context: Context): AdminSyncConfig }`) — if a real compile error surfaces beyond that, fix the specific mismatched signature rather than reworking the whole screen.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/archive/ArchiveLibraryScreen.kt
git commit -m "feat: add ArchiveLibraryScreen with list, download, open, and admin-gated restore"
```

---

### Task 7: NavGraph, NavDestination, TopLevelTab wiring (both nav variants)

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/components/AppScaffold.kt` (`NavDestination` enum)
- Modify: `app/src/main/java/com/kkc/sheettracker/navigation/NavigationCoordinator.kt` (`TopLevelTab` enum + `NavigationCoordinator` class)
- Modify: `app/src/main/java/com/kkc/sheettracker/navigation/NavGraph.kt` (both `MultiBackStackNavigation` and `LegacySingleStackNavigation`)

**Why:** No existing bottom-nav slot fits ("Library" already means the molding/safety reference hub, `STANDARDS`) — confirmed during design research. This adds a genuinely new destination, following the exact three-file pattern every existing destination (e.g. `SUPPLY`) already uses.

- [ ] **Step 1: Add `ARCHIVE` to `NavDestination`**

In `app/src/main/java/com/kkc/sheettracker/ui/components/AppScaffold.kt`, in the `NavDestination` enum (currently `DASHBOARD, JOBS, SEARCH, HOURS, TIMECARD, SUPPLY, SETTINGS, STANDARDS`), add one entry — pick any unused Material icon pair (e.g. `Icons.Filled.Inventory2`/`Icons.Outlined.Inventory2`, or `Icons.Filled.Archive`/`Icons.Outlined.Archive` if available in the Material icon set this project already depends on — check the existing `import androidx.compose.material.icons.filled.*`/`.outlined.*` block at the top of this file for what's already imported and reuse an available icon rather than guessing a name that might not exist in this project's icon library version):

```kotlin
    ARCHIVE("archive", "Archive", Icons.Filled.Archive, Icons.Outlined.Archive)
```

(Add the two new icon imports if `Archive`/Outlined `Archive` aren't already imported elsewhere in this file.)

- [ ] **Step 2: Add `ARCHIVE` to `TopLevelTab`**

In `app/src/main/java/com/kkc/sheettracker/navigation/NavigationCoordinator.kt`:

```kotlin
enum class TopLevelTab(val route: String) {
    DASHBOARD("dashboard"),
    JOBS("jobs"),
    SEARCH("search"),
    HOURS("hours"),
    TIMECARD("timecard"),
    SETTINGS("settings"),
    SUPPLY("supply"),
    STANDARDS("standards"),
    ARCHIVE("archive");

    companion object {
        fun fromDestination(destination: NavDestination): TopLevelTab {
            return when (destination) {
                NavDestination.DASHBOARD -> DASHBOARD
                NavDestination.JOBS -> JOBS
                NavDestination.SEARCH -> SEARCH
                NavDestination.HOURS -> HOURS
                NavDestination.TIMECARD -> TIMECARD
                NavDestination.SETTINGS -> SETTINGS
                NavDestination.SUPPLY -> SUPPLY
                NavDestination.STANDARDS -> STANDARDS
                NavDestination.ARCHIVE -> ARCHIVE
            }
        }

        fun toDestination(tab: TopLevelTab): NavDestination {
            return when (tab) {
                DASHBOARD -> NavDestination.DASHBOARD
                JOBS -> NavDestination.JOBS
                SEARCH -> NavDestination.SEARCH
                HOURS -> NavDestination.HOURS
                TIMECARD -> NavDestination.TIMECARD
                SETTINGS -> NavDestination.SETTINGS
                SUPPLY -> NavDestination.SUPPLY
                STANDARDS -> NavDestination.STANDARDS
                ARCHIVE -> NavDestination.ARCHIVE
            }
        }
    }
}
```

And add an `archiveNavController` parameter to the `NavigationCoordinator` class constructor and its `controllerFor` when-block:

```kotlin
class NavigationCoordinator(
    private val dashboardNavController: NavHostController,
    private val jobsNavController: NavHostController,
    private val searchNavController: NavHostController,
    private val hoursNavController: NavHostController,
    private val timecardNavController: NavHostController,
    private val settingsNavController: NavHostController,
    private val supplyNavController: NavHostController,
    private val standardsNavController: NavHostController,
    private val archiveNavController: NavHostController,
    private val getHomeTab: () -> TopLevelTab,
    private val getSelectedTab: () -> TopLevelTab,
    private val setSelectedTab: (TopLevelTab) -> Unit
) {
    // ... unchanged body above controllerFor ...

    private fun controllerFor(tab: TopLevelTab): NavHostController {
        return when (tab) {
            TopLevelTab.DASHBOARD -> dashboardNavController
            TopLevelTab.JOBS -> jobsNavController
            TopLevelTab.SEARCH -> searchNavController
            TopLevelTab.HOURS -> hoursNavController
            TopLevelTab.TIMECARD -> timecardNavController
            TopLevelTab.SETTINGS -> settingsNavController
            TopLevelTab.SUPPLY -> supplyNavController
            TopLevelTab.STANDARDS -> standardsNavController
            TopLevelTab.ARCHIVE -> archiveNavController
        }
    }
    // ... rest unchanged ...
}
```

- [ ] **Step 3: Wire `MultiBackStackNavigation`** (`NavGraph.kt`)

Find the `visibleDestinations` block (`NavGraph.kt:564-574`) and add `NavDestination.ARCHIVE` to both branches:

```kotlin
    val visibleDestinations = remember(workMode) {
        if (workMode == WorkMode.ASSEMBLY || workMode == WorkMode.SPECIALTY) {
            listOf(NavDestination.JOBS, NavDestination.HOURS, NavDestination.TIMECARD, NavDestination.SUPPLY, NavDestination.STANDARDS, NavDestination.ARCHIVE)
        } else {
            NavDestination.entries.filter {
                it != NavDestination.SEARCH && it != NavDestination.SETTINGS
            }
        }
    }
```

(The `else` branch already includes every destination except `SEARCH`/`SETTINGS`, so `ARCHIVE` is included automatically once added to the enum — only the explicit `ASSEMBLY`/`SPECIALTY` list needs the new entry spelled out.)

Add a `val archiveNavController = rememberNavController()` next to the other `rememberNavController()` calls (near `standardsNavController`), and pass it into the `NavigationCoordinator(...)` construction (`remember(...) { NavigationCoordinator(..., standardsNavController = standardsNavController, archiveNavController = archiveNavController, ...) }` — grep this exact call site first, since it's several hundred lines from `visibleDestinations`, to confirm the current full argument list before editing).

Add a new `TabLayer` block next to the existing `TopLevelTab.SUPPLY`/`TopLevelTab.STANDARDS` ones:

```kotlin
                TabLayer(visible = selectedTab == TopLevelTab.ARCHIVE) {
                    ArchiveTabHost(
                        navController = archiveNavController,
                        tabletId = tabletId,
                        isDebugBuild = isDebugBuild,
                    )
                }
```

Add the `ArchiveTabHost` composable (near the other `*TabHost` composables, e.g. `SupplyTabHost`):

```kotlin
@Composable
private fun ArchiveTabHost(
    navController: NavHostController,
    tabletId: String,
    isDebugBuild: Boolean,
) {
    NavHost(navController = navController, startDestination = "archive", modifier = Modifier.fillMaxSize()) {
        composable("archive") {
            com.kkc.sheettracker.ui.archive.ArchiveLibraryScreen(
                tabletId = tabletId,
                isDebugBuild = isDebugBuild,
                onOpenArchiveJob = { archiveJobId, folderName, contentVersion ->
                    navController.navigate(
                        "archive/job/${URLEncoder.encode(archiveJobId, "UTF-8")}/${URLEncoder.encode(folderName, "UTF-8")}/${URLEncoder.encode(contentVersion, "UTF-8")}"
                    ) { launchSingleTop = true }
                },
            )
        }
        composable(
            "archive/job/{archiveJobId}/{folderName}/{contentVersion}",
            arguments = listOf(
                navArgument("archiveJobId") { type = NavType.StringType },
                navArgument("folderName") { type = NavType.StringType },
                navArgument("contentVersion") { type = NavType.StringType },
            ),
        ) { backStackEntry ->
            val archiveJobId = URLDecoder.decode(backStackEntry.arguments?.getString("archiveJobId").orEmpty(), "UTF-8")
            val folderName = URLDecoder.decode(backStackEntry.arguments?.getString("folderName").orEmpty(), "UTF-8")
            val contentVersion = URLDecoder.decode(backStackEntry.arguments?.getString("contentVersion").orEmpty(), "UTF-8")
            // JobDetailScreen (or the mode-appropriate detail screen) opened here against an
            // ArchiveSession built from filesDir/archive-cache/<archiveJobId> -- follow the exact
            // pattern the existing "job/{folderName}" route uses for the live JobDetailScreen,
            // substituting an ArchiveSession-backed ProgressStore/UnifiedMetadataEngine for the
            // live scanCoordinator-backed ones. Left as the next concrete step once this
            // navigation skeleton compiles and the Archive tab itself is confirmed reachable --
            // wiring the full read-only detail screen is real, non-trivial work (matching every
            // production_order-derived affordance the live JobDetailScreen has to skip) and
            // deserves its own focused pass rather than being crammed into this already-large
            // navigation task.
        }
    }
}
```

- [ ] **Step 4: Wire `LegacySingleStackNavigation`** (`NavGraph.kt`)

Grep for `LegacySingleStackNavigation`'s own `visibleDestinations` block (same shape as Step 3, confirmed to exist at a separate location in this file per design research) and add `NavDestination.ARCHIVE` to its `ASSEMBLY`/`SPECIALTY` list the same way.

Grep for how `LegacySingleStackNavigation`'s single `NavHost`'s bottom-nav `onNavigate` callback dispatches a tapped `NavDestination` to a route (it does not use `NavigationCoordinator` — confirm the exact dispatch mechanism, likely a direct `navController.navigate(dest.route)` call or a local `when` block, by reading the surrounding ~30 lines before editing) and add an `"archive"` composable route there following the same shape as Step 3's `ArchiveTabHost` content (a single NavHost here, so register `composable("archive") { ArchiveLibraryScreen(...) }` and `composable("archive/job/{archiveJobId}/{folderName}/{contentVersion}") { ... }` directly inside the existing `NavHost { ... }` block, not inside a separate nested `ArchiveTabHost`).

- [ ] **Step 5: Compile**

Run: `.\gradlew.bat :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL once all three files' `when` blocks are exhaustive over the new `ARCHIVE`/`NavDestination.ARCHIVE` cases and both nav variants' `NavHost`s register the new routes — the Kotlin compiler will list every non-exhaustive `when` as a hard error, so this step is self-checking: fix each reported line until it's clean.

- [ ] **Step 6: Run the full unit test suite**

Run: `.\gradlew.bat :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests pass including Tasks 1-5's new tests.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/components/AppScaffold.kt app/src/main/java/com/kkc/sheettracker/navigation/NavigationCoordinator.kt app/src/main/java/com/kkc/sheettracker/navigation/NavGraph.kt
git commit -m "feat: wire Archive bottom-nav destination into both navigation implementations"
```

---

### Task 8: Manual verification pass

**Files:** none (verification only)

- [ ] **Step 1: Full compile + test suite**

Run: `.\gradlew.bat :app:compileDebugKotlin :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all green.

- [ ] **Step 2: Install and manually verify on a real tablet, once the backend plan is deployed**

Per the design's own Testing section: "Device verification downloads an archive job over the shop LAN, opens CNC, assembly, hardwoods, specialty, PDFs, and 3D, prints a PDF, reopens from cache on a slow/disconnected connection, verifies 24-hour expiry cleanup, and confirms no archive files appear in Syncthing or the normal live list."

Concretely, with the backend plan's Task 18 manual verification already done (at least one real archived job exists on the server):
1. Install the debug build (`adb-install-release.ps1` or `.\gradlew.bat assembleDebug` + `adb install -r`), open the app, navigate to the new Archive tab.
2. Confirm the archived job appears in the list within a few seconds of connecting.
3. Tap it, confirm a download progress indicator appears, then the app navigates to the archive job route (the placeholder left in Task 7 Step 3 — this is the point where Task 7's follow-up work, the actual read-only detail screen, becomes verifiable).
4. Confirm `adb shell run-as com.kkc.sheettracker ls files/archive-cache/` shows the downloaded entry.
5. Force-close and reopen the app while offline (airplane mode) — confirm the previously-downloaded entry is still reachable from the cache without a fresh download.
6. Confirm the app never writes into the tablet's synced `Ready Jobs`/`SyncJobs` path for anything archive-related (`adb shell ls -la "/storage/emulated/0/Ready Jobs"` before/after should show no new archive-related files).

Flag this step to the user as a manual follow-up — it needs a real deployed backend, a real archived job, and a physical tablet, none of which exist in this development environment.
