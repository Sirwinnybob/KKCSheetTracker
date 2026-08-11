# Idle Power Saving Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** After a configurable idle period, force dark theme + dark PDFs app-wide (without touching the user's real theme prefs), throttle job-scan polling, and pause Syncthing via REST — all reverting instantly on any touch/key interaction.

**Architecture:** A new `IdleActivityTracker` (pure Kotlin, testable) ticks once a second, comparing elapsed time since last interaction against two DataStore-persisted thresholds, and exposes `phase: StateFlow<IdlePhase>` + `pollIntervalOverrideMs: StateFlow<Long?>`. `MainActivity` owns the tracker (created in `onCreate`, reset from `onUserInteraction()`), threads the two StateFlows down via `CompositionLocal` (same pattern as the existing `LocalLowEndMode`), and overrides the *effective* `isDarkTheme`/`useStandardSheets` values passed into `KKCTheme`/`AppNavigation` — the real SharedPreferences values are never written. `TrackerChangeMonitor`/`StaticCachePoller` gain an `intervalOverrideMs: StateFlow<Long?>` constructor param they read fresh each poll tick. `SyncthingSupervisor` gains `observeIdlePhase()`, calling new REST-based `pauseSync()`/`resumeSync()` on `SyncController` (not the existing broadcast start/stop) so the watchdog's health check keeps succeeding during a pause.

**Tech Stack:** Kotlin, Jetpack Compose, Jetpack DataStore (Preferences), Kotlin Coroutines/Flow, JUnit4 (existing `app/src/test` suite, no Robolectric/coroutines-test in this project).

---

## Spec Reference

Full design: [`docs/superpowers/specs/2026-08-11-idle-power-saving-design.md`](../specs/2026-08-11-idle-power-saving-design.md)

## Note on Test Coverage

This project's existing DataStore-backed stores (`ScannerSettingsStore`, `TimecardBgStore`, etc.) have **no direct Context/DataStore unit tests** — they're exercised manually/via the app. This plan follows that convention for `IdlePowerSaveStore`. Likewise, the project has **no Compose UI test suite** — all `MainActivity.kt`/`NavGraph.kt`/`SettingsScreen.kt` changes here are manually verified (Task 12), matching how `LowEndModeCompositionLocal` and other recent Compose-layer features were verified. The project also doesn't use `kotlinx-coroutines-test`, so real-time-based coroutine loops (the `TrackerChangeMonitor`/`StaticCachePoller` poll loops) aren't unit-tested for their *timing* — only their pure logic is. Where a task's logic can be extracted into a pure, deterministically-testable function, this plan does so (see Task 2's `computeIdlePhase`).

---

### Task 1: IdlePowerSaveStore (DataStore)

**Files:**
- Create: `app/src/main/java/com/kkc/sheettracker/data/IdlePowerSaveStore.kt`

No test for this task — see "Note on Test Coverage" above (matches `ScannerSettingsStore`, which also has no test file).

- [ ] **Step 1: Create the store**

```kotlin
package com.kkc.sheettracker.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.idlePowerSaveDataStore by preferencesDataStore(name = "screensaver_settings")

data class IdlePowerSaveConfig(
    val enabled: Boolean = true,
    val idleTimeoutSeconds: Int = 300,
    val syncthingPauseTimeoutSeconds: Int = 1800
)

class IdlePowerSaveStore(private val context: Context) {

    private val dataStore = context.idlePowerSaveDataStore

    val configFlow: Flow<IdlePowerSaveConfig> = dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs ->
            IdlePowerSaveConfig(
                enabled = prefs[KEY_ENABLED] ?: true,
                idleTimeoutSeconds = prefs[KEY_IDLE_TIMEOUT_SECONDS] ?: 300,
                syncthingPauseTimeoutSeconds = prefs[KEY_SYNCTHING_PAUSE_TIMEOUT_SECONDS] ?: 1800
            )
        }

    suspend fun setEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[KEY_ENABLED] = enabled }
    }

    suspend fun setIdleTimeoutSeconds(seconds: Int) {
        dataStore.edit { prefs -> prefs[KEY_IDLE_TIMEOUT_SECONDS] = seconds.coerceAtLeast(5) }
    }

    suspend fun setSyncthingPauseTimeoutSeconds(seconds: Int) {
        dataStore.edit { prefs -> prefs[KEY_SYNCTHING_PAUSE_TIMEOUT_SECONDS] = seconds.coerceAtLeast(5) }
    }

    companion object {
        private val KEY_ENABLED = booleanPreferencesKey("enabled")
        private val KEY_IDLE_TIMEOUT_SECONDS = intPreferencesKey("idle_timeout_seconds")
        private val KEY_SYNCTHING_PAUSE_TIMEOUT_SECONDS = intPreferencesKey("syncthing_pause_timeout_seconds")
    }
}
```

- [ ] **Step 2: Compile check**

Run: `cd C:\Scripts\KKCSheetTracker; .\gradlew.bat compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/data/IdlePowerSaveStore.kt
git commit -m "feat(idle): add IdlePowerSaveStore DataStore"
```

---

### Task 2: IdlePhase + computeIdlePhase + IdleActivityTracker

**Files:**
- Create: `app/src/main/java/com/kkc/sheettracker/data/IdleActivityTracker.kt`
- Test: `app/src/test/java/com/kkc/sheettracker/data/IdleActivityTrackerTest.kt`

- [ ] **Step 1: Write the failing test for the pure phase-computation function**

```kotlin
package com.kkc.sheettracker.data

import org.junit.Assert.assertEquals
import org.junit.Test

class IdleActivityTrackerTest {

    private val config = IdlePowerSaveConfig(
        enabled = true,
        idleTimeoutSeconds = 5,
        syncthingPauseTimeoutSeconds = 30
    )

    @Test
    fun `stays active before idle timeout`() {
        assertEquals(IdlePhase.ACTIVE, computeIdlePhase(elapsedMs = 4_000L, config = config))
    }

    @Test
    fun `dims exactly at idle timeout`() {
        assertEquals(IdlePhase.DIMMED, computeIdlePhase(elapsedMs = 5_000L, config = config))
    }

    @Test
    fun `stays dimmed before syncthing pause timeout`() {
        assertEquals(IdlePhase.DIMMED, computeIdlePhase(elapsedMs = 29_000L, config = config))
    }

    @Test
    fun `pauses syncthing exactly at pause timeout`() {
        assertEquals(IdlePhase.SYNC_PAUSED, computeIdlePhase(elapsedMs = 30_000L, config = config))
    }

    @Test
    fun `disabled config always stays active regardless of elapsed time`() {
        val disabled = config.copy(enabled = false)
        assertEquals(IdlePhase.ACTIVE, computeIdlePhase(elapsedMs = 999_999L, config = disabled))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd C:\Scripts\KKCSheetTracker; .\gradlew.bat testDebugUnitTest --tests "com.kkc.sheettracker.data.IdleActivityTrackerTest"`
Expected: FAIL to compile — `computeIdlePhase`, `IdlePhase`, `IdlePowerSaveConfig` symbols not found (IdlePowerSaveConfig exists from Task 1; `IdlePhase`/`computeIdlePhase` don't yet)

- [ ] **Step 3: Implement IdlePhase, computeIdlePhase, and IdleActivityTracker**

```kotlin
package com.kkc.sheettracker.data

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class IdlePhase { ACTIVE, DIMMED, SYNC_PAUSED }

internal fun computeIdlePhase(elapsedMs: Long, config: IdlePowerSaveConfig): IdlePhase {
    if (!config.enabled) return IdlePhase.ACTIVE
    val elapsedSeconds = elapsedMs / 1000L
    return when {
        elapsedSeconds >= config.syncthingPauseTimeoutSeconds -> IdlePhase.SYNC_PAUSED
        elapsedSeconds >= config.idleTimeoutSeconds -> IdlePhase.DIMMED
        else -> IdlePhase.ACTIVE
    }
}

/**
 * Ticks once a second comparing elapsed time since [reset] against [config]'s two thresholds.
 * [reset] itself also recomputes synchronously so a touch reverts phase to ACTIVE immediately,
 * without waiting for the next tick.
 */
class IdleActivityTracker(
    private val config: StateFlow<IdlePowerSaveConfig>,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val nowMs: () -> Long = { SystemClock.elapsedRealtime() },
    private val tickMs: Long = 1_000L
) {
    private var lastInteractionAtMs = nowMs()
    private var tickJob: Job? = null

    private val _phase = MutableStateFlow(IdlePhase.ACTIVE)
    val phase: StateFlow<IdlePhase> = _phase.asStateFlow()

    private val _pollIntervalOverrideMs = MutableStateFlow<Long?>(null)
    val pollIntervalOverrideMs: StateFlow<Long?> = _pollIntervalOverrideMs.asStateFlow()

    fun reset() {
        lastInteractionAtMs = nowMs()
        recompute()
    }

    fun start() {
        if (tickJob?.isActive == true) return
        tickJob = scope.launch {
            while (isActive) {
                recompute()
                delay(tickMs)
            }
        }
    }

    fun stop() {
        tickJob?.cancel()
        tickJob = null
    }

    private fun recompute() {
        val currentConfig = config.value
        val newPhase = computeIdlePhase(nowMs() - lastInteractionAtMs, currentConfig)
        _phase.value = newPhase
        _pollIntervalOverrideMs.value = if (newPhase == IdlePhase.ACTIVE) {
            null
        } else {
            currentConfig.idleTimeoutSeconds * 1000L
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd C:\Scripts\KKCSheetTracker; .\gradlew.bat testDebugUnitTest --tests "com.kkc.sheettracker.data.IdleActivityTrackerTest"`
Expected: PASS (5 tests)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/data/IdleActivityTracker.kt app/src/test/java/com/kkc/sheettracker/data/IdleActivityTrackerTest.kt
git commit -m "feat(idle): add IdlePhase state machine with tested phase computation"
```

---

### Task 3: CompositionLocals for idle phase + poll interval override

**Files:**
- Create: `app/src/main/java/com/kkc/sheettracker/ui/components/IdlePhaseCompositionLocal.kt`

No test — Compose CompositionLocal declarations aren't unit-tested in this project (see `LowEndModeCompositionLocal.kt`, which has none).

- [ ] **Step 1: Create the file**

```kotlin
package com.kkc.sheettracker.ui.components

import androidx.compose.runtime.staticCompositionLocalOf
import com.kkc.sheettracker.data.IdlePhase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

val LocalIdlePhase = staticCompositionLocalOf<StateFlow<IdlePhase>> {
    MutableStateFlow(IdlePhase.ACTIVE)
}

val LocalIdlePollIntervalOverrideMs = staticCompositionLocalOf<StateFlow<Long?>> {
    MutableStateFlow(null)
}

/** Backstop reset callback for the pointerInput touch listener wrapping AppNavigation's root (Task 9) — belt-and-suspenders alongside MainActivity.onUserInteraction(). */
val LocalIdleReset = staticCompositionLocalOf<() -> Unit> { {} }
```

- [ ] **Step 2: Compile check**

Run: `cd C:\Scripts\KKCSheetTracker; .\gradlew.bat compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/components/IdlePhaseCompositionLocal.kt
git commit -m "feat(idle): add LocalIdlePhase and LocalIdlePollIntervalOverrideMs"
```

---

### Task 4: TrackerChangeMonitor poll interval override

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/data/TrackerChangeMonitor.kt:16-26,75-89`

No new test — see "Note on Test Coverage": this is a real-time coroutine loop with no `kotlinx-coroutines-test` in the project, and the existing test suite exercises this class via `FileObserver` (inotify) detection, not poll timing, so a timing-based test here would be flaky/confounded by the same `FileObserver`. Verified manually in Task 12.

- [ ] **Step 1: Add the `intervalOverrideMs` constructor param**

In `TrackerChangeMonitor.kt`, change the constructor (lines 16-26):

```kotlin
class TrackerChangeMonitor(
    private val baseDir: File,
    private val progressStore: ProgressStore,
    private val hardwoodsProgressStore: HardwoodsProgressStore,
    private val specialtyProgressStore: SpecialtyProgressStore? = null,
    private val viewerInteraction: StateFlow<Boolean> = ViewerInteractionSignal.isViewerInteracting,
    private val activeJobFolderName: StateFlow<String?> = MutableStateFlow(null),
    private val onWatcherRefreshRequested: (() -> Unit)? = null,
    private val onCncJobsChanged: ((Set<String>) -> Unit)? = null,
    private val pollingIntervalMs: Long = POLLING_INTERVAL_MS,
    private val intervalOverrideMs: StateFlow<Long?> = MutableStateFlow(null)
) {
```

- [ ] **Step 2: Read the override in the poll loop**

Change line 77 inside `start()`'s `pollJob`:

```kotlin
        pollJob = scope.launch {
            while (isActive) {
                delay(intervalOverrideMs.value ?: pollingIntervalMs)
                if (viewerInteraction.value) {
                    continue // Skip polling during active 3D viewer interaction
                }
```

- [ ] **Step 3: Run existing tests to confirm no regression**

Run: `cd C:\Scripts\KKCSheetTracker; .\gradlew.bat testDebugUnitTest --tests "com.kkc.sheettracker.data.TrackerChangeMonitorEventsTest" --tests "com.kkc.sheettracker.data.TrackerChangeMonitorSpecialtyTest"`
Expected: PASS (unchanged — `intervalOverrideMs` defaults to a `StateFlow(null)`, so `pollingIntervalMs` behavior is unaffected)

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/data/TrackerChangeMonitor.kt
git commit -m "feat(idle): let TrackerChangeMonitor's poll interval be overridden while idle"
```

---

### Task 5: StaticCachePoller poll interval override

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/data/StaticCachePoller.kt`

No test file exists for this class today; none added here (same rationale as Task 4).

- [ ] **Step 1: Add imports and the constructor param**

Add imports after line 11 (`import java.io.File`):

```kotlin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
```

Change the constructor (lines 22-26):

```kotlin
class StaticCachePoller(
    baseDir: File,
    private val onJobCacheUpdated: (folderName: String) -> Unit,
    private val pollIntervalMs: Long = POLL_INTERVAL_MS,
    private val intervalOverrideMs: StateFlow<Long?> = MutableStateFlow(null)
) {
```

- [ ] **Step 2: Read the override in the poll loop**

Change line 52:

```kotlin
            while (isActive) {
                delay(intervalOverrideMs.value ?: pollIntervalMs)
                checkForChanges()
            }
```

- [ ] **Step 3: Compile check**

Run: `cd C:\Scripts\KKCSheetTracker; .\gradlew.bat compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/data/StaticCachePoller.kt
git commit -m "feat(idle): let StaticCachePoller's poll interval be overridden while idle"
```

---

### Task 6: Syncthing REST pause/resume

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/sync/SyncController.kt`
- Test: `app/src/test/java/com/kkc/sheettracker/sync/SyncthingManagerTest.kt`

- [ ] **Step 1: Write the failing tests**

Append to `SyncthingManagerTest.kt` (inside the `SyncthingManagerTest` class, after the last existing `@Test` function, before the closing `}` at line 121):

```kotlin

    @Test
    fun `pauseSync returns true on 200`() = runBlocking {
        val manager = SyncthingManager(
            context = null,
            config = SyncthingRuntimeConfig(apiKey = "abc123"),
            connectionFactory = SyncthingConnectionFactory {
                FakeHttpURLConnection(it, HttpURLConnection.HTTP_OK)
            },
            commandSender = NoOpCommandSender()
        )

        assertTrue(manager.pauseSync())
    }

    @Test
    fun `resumeSync returns false on non-200`() = runBlocking {
        val manager = SyncthingManager(
            context = null,
            config = SyncthingRuntimeConfig(),
            connectionFactory = SyncthingConnectionFactory {
                FakeHttpURLConnection(it, HttpURLConnection.HTTP_INTERNAL_ERROR)
            },
            commandSender = NoOpCommandSender()
        )

        assertFalse(manager.resumeSync())
    }

    @Test
    fun `pauseSync returns false when connection fails`() = runBlocking {
        val manager = SyncthingManager(
            context = null,
            config = SyncthingRuntimeConfig(),
            connectionFactory = SyncthingConnectionFactory {
                throw ConnectException("refused")
            },
            commandSender = NoOpCommandSender()
        )

        assertFalse(manager.pauseSync())
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd C:\Scripts\KKCSheetTracker; .\gradlew.bat testDebugUnitTest --tests "com.kkc.sheettracker.sync.SyncthingManagerTest"`
Expected: FAIL to compile — `pauseSync`/`resumeSync` not defined on `SyncthingManager`

- [ ] **Step 3: Add `pausePath`/`resumePath` to `SyncthingEndpointConfig`**

In `SyncController.kt`, change (lines 30-33):

```kotlin
data class SyncthingEndpointConfig(
    val baseUrl: String = "http://127.0.0.1:8384",
    val pingPath: String = "/rest/system/ping",
    val pausePath: String = "/rest/system/pause",
    val resumePath: String = "/rest/system/resume"
)
```

- [ ] **Step 4: Add `pauseSync`/`resumeSync` to the `SyncController` interface**

Change (lines 17-21):

```kotlin
interface SyncController {
    suspend fun isServiceRunning(): Boolean
    fun startService()
    fun stopService()
    suspend fun pauseSync(): Boolean
    suspend fun resumeSync(): Boolean
}
```

- [ ] **Step 5: Implement in `SyncthingManager`, refactoring `buildPingUrl` to share the URL-building logic**

Replace `buildPingUrl()` (lines 131-135):

```kotlin
    private fun buildPingUrl(): URL = buildCommandUrl(config.endpoint.pingPath)

    private fun buildCommandUrl(path: String): URL {
        val base = config.endpoint.baseUrl.trimEnd('/')
        val trimmedPath = path.trimStart('/')
        return URL("$base/$trimmedPath")
    }
```

Add after `stopService()` (after line 129, before `private fun buildPingUrl`):

```kotlin
    override suspend fun pauseSync(): Boolean = postCommand(config.endpoint.pausePath)

    override suspend fun resumeSync(): Boolean = postCommand(config.endpoint.resumePath)

    private suspend fun postCommand(path: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val connection = connectionFactory.open(buildCommandUrl(path))
            try {
                connection.requestMethod = "POST"
                connection.instanceFollowRedirects = false
                connection.connectTimeout = config.network.timeoutMs
                connection.readTimeout = config.network.timeoutMs
                val apiKey = config.apiKey.trim()
                if (apiKey.isNotEmpty()) {
                    connection.setRequestProperty("X-API-Key", apiKey)
                }
                val responseCode = connection.responseCode
                when {
                    responseCode == HttpURLConnection.HTTP_OK -> true
                    isLocalTlsRedirect(responseCode, connection) -> {
                        postViaLocalTls(
                            location = connection.getHeaderField("Location"),
                            apiKey = apiKey
                        )
                    }
                    else -> false
                }
            } finally {
                connection.disconnect()
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun postViaLocalTls(location: String?, apiKey: String): Boolean {
        if (!config.network.allowInsecureLocalTls || location.isNullOrBlank()) return false
        val httpsUrl = URL(location)
        val connection = connectionFactory.open(httpsUrl)
        return try {
            if (connection is HttpsURLConnection) {
                configureInsecureLoopbackTls(connection)
            }
            connection.requestMethod = "POST"
            connection.connectTimeout = config.network.timeoutMs
            connection.readTimeout = config.network.timeoutMs
            if (apiKey.isNotEmpty()) {
                connection.setRequestProperty("X-API-Key", apiKey)
            }
            connection.responseCode == HttpURLConnection.HTTP_OK
        } finally {
            connection.disconnect()
        }
    }

```

- [ ] **Step 6: Run tests to verify they pass**

Run: `cd C:\Scripts\KKCSheetTracker; .\gradlew.bat testDebugUnitTest --tests "com.kkc.sheettracker.sync.SyncthingManagerTest"`
Expected: PASS (10 tests — 7 existing + 3 new)

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/sync/SyncController.kt app/src/test/java/com/kkc/sheettracker/sync/SyncthingManagerTest.kt
git commit -m "feat(idle): add Syncthing REST pause/resume to SyncController"
```

---

### Task 7: SyncthingSupervisor.observeIdlePhase

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/sync/SyncthingSupervisor.kt`
- Test: `app/src/test/java/com/kkc/sheettracker/sync/SyncthingSupervisorTest.kt`

- [ ] **Step 1: Update `FakeSyncController` to implement the new interface members**

In `SyncthingSupervisorTest.kt`, change `FakeSyncController` (lines 106-122):

```kotlin
private class FakeSyncController(
    private val running: Boolean
) : SyncController {
    var checkCalls: Int = 0
    var startCalls: Int = 0
    var pauseCalls: Int = 0
    var resumeCalls: Int = 0

    override suspend fun isServiceRunning(): Boolean {
        checkCalls++
        return running
    }

    override fun startService() {
        startCalls++
    }

    override fun stopService() = Unit

    override suspend fun pauseSync(): Boolean {
        pauseCalls++
        return true
    }

    override suspend fun resumeSync(): Boolean {
        resumeCalls++
        return true
    }
}
```

- [ ] **Step 2: Write the failing test**

Add import at the top of `SyncthingSupervisorTest.kt` (after line 8 `import kotlinx.coroutines.flow.MutableStateFlow`):

```kotlin
import com.kkc.sheettracker.data.IdlePhase
```

Add a new test inside the `SyncthingSupervisorTest` class (after the `missing key produces api key required status` test, before the closing `}` at line 92):

```kotlin

    @Test
    fun `idle phase pause and resume call syncthing once per transition`() = runBlocking {
        val store = FakeSyncthingPreferencesStore("api-key")
        val controller = FakeSyncController(running = true)
        val phase = MutableStateFlow(IdlePhase.ACTIVE)
        val supervisor = SyncthingSupervisor(
            context = null,
            runtimeConfig = SyncthingRuntimeConfig(
                watchdog = SyncthingWatchdogConfig(intervalMs = 10_000L, autoStartOnFailure = false)
            ),
            preferencesStore = store,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            managerFactory = { controller }
        )

        supervisor.startMonitoring()
        waitUntil(2_000L) { supervisor.apiKey.value == "api-key" }
        supervisor.observeIdlePhase(phase)

        phase.value = IdlePhase.SYNC_PAUSED
        waitUntil(2_000L) { controller.pauseCalls == 1 }
        assertEquals(1, controller.pauseCalls)

        phase.value = IdlePhase.SYNC_PAUSED // repeat, already paused — must not double-fire
        delay(50L)
        assertEquals(1, controller.pauseCalls)

        phase.value = IdlePhase.ACTIVE
        waitUntil(2_000L) { controller.resumeCalls == 1 }
        assertEquals(1, controller.resumeCalls)

        supervisor.close()
    }
```

- [ ] **Step 3: Run test to verify it fails**

Run: `cd C:\Scripts\KKCSheetTracker; .\gradlew.bat testDebugUnitTest --tests "com.kkc.sheettracker.sync.SyncthingSupervisorTest"`
Expected: FAIL to compile — `observeIdlePhase` not defined on `SyncthingSupervisor`

- [ ] **Step 4: Implement `observeIdlePhase`**

In `SyncthingSupervisor.kt`, add import after line 18 (`import kotlinx.coroutines.withContext`):

```kotlin
import com.kkc.sheettracker.data.IdlePhase
```

Add fields after `private var watchdogJob: Job? = null` (line 54):

```kotlin
    private var idlePhaseObserverJob: Job? = null
    private var pausedForIdle = false
```

Add the method after `startNow()` (after line 160, before `suspend fun saveApiKey`):

```kotlin

    fun observeIdlePhase(phase: StateFlow<IdlePhase>) {
        idlePhaseObserverJob?.cancel()
        idlePhaseObserverJob = scope.launch {
            phase.collect { p ->
                val currentApiKey = _apiKey.value.trim()
                if (currentApiKey.isBlank()) return@collect
                if (p == IdlePhase.SYNC_PAUSED && !pausedForIdle) {
                    pausedForIdle = true
                    managerFactory(currentApiKey).pauseSync()
                } else if (p != IdlePhase.SYNC_PAUSED && pausedForIdle) {
                    pausedForIdle = false
                    managerFactory(currentApiKey).resumeSync()
                }
            }
        }
    }
```

Change `close()` (lines 166-169) to also cancel the observer:

```kotlin
    fun close() {
        stopMonitoring()
        idlePhaseObserverJob?.cancel()
        scope.cancel()
    }
```

- [ ] **Step 5: Run test to verify it passes**

Run: `cd C:\Scripts\KKCSheetTracker; .\gradlew.bat testDebugUnitTest --tests "com.kkc.sheettracker.sync.SyncthingSupervisorTest"`
Expected: PASS (4 tests — 3 existing + 1 new)

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/sync/SyncthingSupervisor.kt app/src/test/java/com/kkc/sheettracker/sync/SyncthingSupervisorTest.kt
git commit -m "feat(idle): pause/resume Syncthing on idle phase transitions"
```

---

### Task 8: MainActivity wiring

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/MainActivity.kt`

No automated test — Compose/Activity wiring, verified manually in Task 12.

- [ ] **Step 1: Add imports**

After line 55 (`import com.kkc.sheettracker.data.ClockInState`), add:

```kotlin
import com.kkc.sheettracker.data.IdleActivityTracker
import com.kkc.sheettracker.data.IdlePhase
import com.kkc.sheettracker.data.IdlePowerSaveConfig
import com.kkc.sheettracker.data.IdlePowerSaveStore
```

After line 58 (`import com.kkc.sheettracker.ui.components.LocalScrollPreviewLabelOnly`), add:

```kotlin
import com.kkc.sheettracker.ui.components.LocalIdlePhase
import com.kkc.sheettracker.ui.components.LocalIdlePollIntervalOverrideMs
import com.kkc.sheettracker.ui.components.LocalIdleReset
```

After line 82 (`import kotlinx.coroutines.launch`), add:

```kotlin
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
```

- [ ] **Step 2: Add the `idleActivityTracker` field**

Change the `lateinit var` block (lines 84-90) to add one more field:

```kotlin
class MainActivity : ComponentActivity() {
    private lateinit var updateManager: UpdateManager
    private lateinit var scanCoordinator: ScanCoordinator
    private lateinit var appStateStore: AppStateStore
    private lateinit var syncthingSupervisor: SyncthingSupervisor
    private lateinit var clockInState: ClockInState
    private lateinit var supplySubscriptionManager: SupplySubscriptionManager
    private lateinit var idleActivityTracker: IdleActivityTracker
```

- [ ] **Step 3: Construct and start the tracker in `onCreate`'s main flow**

In `onCreate`, right after `syncthingSupervisor.startMonitoring()` (line 250), add:

```kotlin
        syncthingSupervisor.startMonitoring()

        val idlePowerSaveStore = IdlePowerSaveStore(applicationContext)
        val idlePowerSaveConfig = idlePowerSaveStore.configFlow.stateIn(
            lifecycleScope, SharingStarted.Eagerly, IdlePowerSaveConfig()
        )
        idleActivityTracker = IdleActivityTracker(config = idlePowerSaveConfig)
        idleActivityTracker.start()
        syncthingSupervisor.observeIdlePhase(idleActivityTracker.phase)
```

- [ ] **Step 4: Override `onUserInteraction()`**

Add this override right after `onWindowFocusChanged` (after line 610, before `override fun onStart()`):

```kotlin
    override fun onUserInteraction() {
        super.onUserInteraction()
        if (::idleActivityTracker.isInitialized) {
            idleActivityTracker.reset()
        }
    }

```

- [ ] **Step 5: Stop the tracker in `onDestroy`**

Change `onDestroy` (lines 641-649):

```kotlin
    override fun onDestroy() {
        if (::syncthingSupervisor.isInitialized) {
            syncthingSupervisor.close()
        }
        if (::supplySubscriptionManager.isInitialized) {
            supplySubscriptionManager.close()
        }
        if (::idleActivityTracker.isInitialized) {
            idleActivityTracker.stop()
        }
        super.onDestroy()
    }
```

- [ ] **Step 6: Read idle phase in `setContent` and override effective theme values**

In the main `setContent` block, right after `val systemDark = androidx.compose.foundation.isSystemInDarkTheme()` (line 275), add:

```kotlin
            val idlePhase by idleActivityTracker.phase.collectAsState()
```

Change the `isDarkTheme` computation (line 278):

```kotlin
            val isDarkTheme = if (idlePhase != IdlePhase.ACTIVE) {
                true
            } else if (followSystemTheme) {
                systemDark
            } else {
                darkThemeOverride
            }
```

- [ ] **Step 7: Provide the CompositionLocals**

Change the `CompositionLocalProvider` block (lines 323-326):

```kotlin
                    androidx.compose.runtime.CompositionLocalProvider(
                        LocalLowEndMode provides lowEndFlags,
                        LocalScrollPreviewLabelOnly provides scrollPreviewLabelOnly,
                        LocalIdlePhase provides idleActivityTracker.phase,
                        LocalIdlePollIntervalOverrideMs provides idleActivityTracker.pollIntervalOverrideMs,
                        LocalIdleReset provides idleActivityTracker::reset
                    ) {
```

- [ ] **Step 8: Override the effective `useStandardSheets` value passed to `AppNavigation`**

Change line 340 (`useStandardSheets = useStandardSheets,`) inside the `AppNavigation(...)` call:

```kotlin
                        useStandardSheets = if (idlePhase != IdlePhase.ACTIVE) false else useStandardSheets,
```

- [ ] **Step 9: Compile check**

Run: `cd C:\Scripts\KKCSheetTracker; .\gradlew.bat compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 10: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/MainActivity.kt
git commit -m "feat(idle): wire IdleActivityTracker into MainActivity theme + lifecycle"
```

---

### Task 9: NavGraph.kt poller wiring

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/navigation/NavGraph.kt:159-274`

No automated test — Compose wiring, verified manually in Task 12.

- [ ] **Step 1: Add imports**

Near the other `com.kkc.sheettracker.ui.components` imports at the top of `NavGraph.kt`, add:

```kotlin
import com.kkc.sheettracker.ui.components.LocalIdlePollIntervalOverrideMs
import com.kkc.sheettracker.ui.components.LocalIdleReset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
```

- [ ] **Step 2: Read the override and reset callback inside `AppNavigation`**

Right after `val lifecycleOwner = LocalLifecycleOwner.current` (line 209), add:

```kotlin
    val idlePollIntervalOverrideMs = LocalIdlePollIntervalOverrideMs.current
    val onIdleReset = LocalIdleReset.current
```

- [ ] **Step 3: Pass it into `TrackerChangeMonitor`**

Change the `TrackerChangeMonitor(...)` construction (lines 220-233) to add one line:

```kotlin
        TrackerChangeMonitor(
            baseDir = File(basePath),
            progressStore = progressStore,
            hardwoodsProgressStore = sharedHardwoodsProgressStore,
            specialtyProgressStore = sharedSpecialtyProgressStore,
            activeJobFolderName = activeJobFolderName,
            intervalOverrideMs = idlePollIntervalOverrideMs,
            onWatcherRefreshRequested = {
                watcherRefreshSignal.value = System.currentTimeMillis()
            },
            onCncJobsChanged = { jobFolderNames ->
                jobFolderNames.forEach { scanCoordinator.unifiedEngine.invalidateJob(it) }
                watcherRefreshSignal.value = System.currentTimeMillis()
            }
        )
```

- [ ] **Step 4: Pass it into `StaticCachePoller`**

Change the `StaticCachePoller(...)` construction (lines 254-260):

```kotlin
    val staticCachePoller = remember(basePath, watcherRefreshSignal) {
        StaticCachePoller(
            baseDir = File(basePath),
            intervalOverrideMs = idlePollIntervalOverrideMs,
            onJobCacheUpdated = { _ ->
                watcherRefreshSignal.value = System.currentTimeMillis()
            }
        )
    }
```

- [ ] **Step 5: Add the pointerInput backstop on AppNavigation's root Box**

This is belt-and-suspenders alongside `MainActivity.onUserInteraction()` (Task 8) — catches touch even if the Activity callback is unreliable on some OEM skin, per the design doc's risk mitigation. Change the root `Box` (line 674, directly inside the outer `CompositionLocalProvider(LocalOnOpenSettings ...)`):

```kotlin
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(onIdleReset) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial)
                        onIdleReset()
                    }
                }
            }
    ) {
```

- [ ] **Step 6: Compile check**

Run: `cd C:\Scripts\KKCSheetTracker; .\gradlew.bat compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/navigation/NavGraph.kt
git commit -m "feat(idle): thread poll interval override and touch backstop into AppNavigation"
```

---

### Task 10: Settings UI

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/settings/SettingsScreen.kt`
- Modify: `app/src/main/java/com/kkc/sheettracker/navigation/NavGraph.kt:1875,3034`

No automated test — Compose UI, verified manually in Task 12.

- [ ] **Step 1: Add imports to `SettingsScreen.kt`**

After line 29 (`import com.kkc.sheettracker.data.UiPreferencesStore`), add:

```kotlin
import com.kkc.sheettracker.data.IdlePowerSaveConfig
import com.kkc.sheettracker.data.IdlePowerSaveStore
```

- [ ] **Step 2: Add the `idlePowerSaveStore` parameter**

Change the `SettingsScreen` signature — after `uiPreferencesStore: UiPreferencesStore,` (line 80), add:

```kotlin
    idlePowerSaveStore: IdlePowerSaveStore,
```

- [ ] **Step 3: Read config + local edit state**

After `val timecardScope = rememberCoroutineScope()` (line 118), add:

```kotlin
    val idlePowerSaveConfig by idlePowerSaveStore.configFlow.collectAsState(initial = IdlePowerSaveConfig())
    val idlePowerSaveScope = rememberCoroutineScope()
    var idleTimeoutSecondsText by remember(idlePowerSaveConfig.idleTimeoutSeconds) {
        mutableStateOf(idlePowerSaveConfig.idleTimeoutSeconds.toString())
    }
    var syncthingPauseTimeoutSecondsText by remember(idlePowerSaveConfig.syncthingPauseTimeoutSeconds) {
        mutableStateOf(idlePowerSaveConfig.syncthingPauseTimeoutSeconds.toString())
    }
```

- [ ] **Step 4: Add the "Idle Power Saving" card**

Insert after the Performance card's closing `}` (after line 512, before the `// ── Tablet ──` comment on line 514):

```kotlin

            // ── Idle Power Saving ───────────────────────────────────────────
            SettingsCard(title = "Idle Power Saving") {
                ToggleRow(
                    label = "Enable Idle Power Saving",
                    checked = idlePowerSaveConfig.enabled,
                    onCheckedChange = { enabled ->
                        idlePowerSaveScope.launch { idlePowerSaveStore.setEnabled(enabled) }
                    },
                    subtitle = "Switches to dark sheets + black background to save battery on tablets left on but idle. Reverts instantly on touch."
                )

                if (idlePowerSaveConfig.enabled) {
                    OutlinedTextField(
                        value = idleTimeoutSecondsText,
                        onValueChange = { text ->
                            idleTimeoutSecondsText = text
                            text.toIntOrNull()?.let { seconds ->
                                idlePowerSaveScope.launch { idlePowerSaveStore.setIdleTimeoutSeconds(seconds) }
                            }
                        },
                        label = { Text("Dim after (seconds)") },
                        supportingText = { Text("Lower values (e.g. 5) are useful for testing. Default 300 (5 min).") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        colors = filledFieldColors(),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )

                    OutlinedTextField(
                        value = syncthingPauseTimeoutSecondsText,
                        onValueChange = { text ->
                            syncthingPauseTimeoutSecondsText = text
                            text.toIntOrNull()?.let { seconds ->
                                idlePowerSaveScope.launch { idlePowerSaveStore.setSyncthingPauseTimeoutSeconds(seconds) }
                            }
                        },
                        label = { Text("Pause Syncthing after (seconds)") },
                        supportingText = { Text("Default 1800 (30 min).") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        colors = filledFieldColors(),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )
                }
            }
```

- [ ] **Step 5: Wire both `SettingsScreen(...)` call sites in `NavGraph.kt`**

Change line 1875:

```kotlin
                    uiPreferencesStore = UiPreferencesStore(LocalContext.current),
                    idlePowerSaveStore = IdlePowerSaveStore(LocalContext.current),
                )
```

Change line 3034:

```kotlin
                        uiPreferencesStore = UiPreferencesStore(LocalContext.current),
                        idlePowerSaveStore = IdlePowerSaveStore(LocalContext.current),
                    )
```

Add the import near the top of `NavGraph.kt` if not already present from Task 9 (`com.kkc.sheettracker.data` imports are already grouped there):

```kotlin
import com.kkc.sheettracker.data.IdlePowerSaveStore
```

- [ ] **Step 6: Compile check**

Run: `cd C:\Scripts\KKCSheetTracker; .\gradlew.bat compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/settings/SettingsScreen.kt app/src/main/java/com/kkc/sheettracker/navigation/NavGraph.kt
git commit -m "feat(idle): add Idle Power Saving settings card"
```

---

### Task 11: Fix raw isSystemInDarkTheme() call sites to respect idle override

**Why:** `HardwoodsWorkspaceScreen.kt` and `SpecialtyJobDetailScreen.kt` each have 3 call sites that call `isSystemInDarkTheme()` directly for row-backdrop tinting, bypassing the app's centrally-computed dark-theme value entirely (including this feature's idle override, and — pre-existing — the user's manual `dark_theme`/`follow_system_theme` toggle). `LocalKKCIsDarkTheme` (`ui/theme/KKCThemeTokens.kt:65`) already holds the correct, already-idle-aware value once Task 8 lands (it's what `KKCTheme` receives as `darkTheme`). Swapping these 6 sites to read it fixes both this feature's coverage and the pre-existing inconsistency, with no new plumbing.

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/hardwoods/HardwoodsWorkspaceScreen.kt:1888,2692,2956`
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/specialty/SpecialtyJobDetailScreen.kt:185,381,519`

No automated test — cosmetic backdrop-tint logic, verified manually in Task 12.

- [ ] **Step 1: Fix `HardwoodsWorkspaceScreen.kt`**

Replace the import `import androidx.compose.foundation.isSystemInDarkTheme` with:

```kotlin
import com.kkc.sheettracker.ui.theme.LocalKKCIsDarkTheme
```

Replace all 3 occurrences of `val isDark = isSystemInDarkTheme()` (lines 1888, 2692, 2956) with:

```kotlin
    val isDark = LocalKKCIsDarkTheme.current
```

(Match each occurrence's existing indentation — line 1888 uses 4-space indent, lines 2692/2956 use deeper nested indent; only the right-hand side changes.)

- [ ] **Step 2: Fix `SpecialtyJobDetailScreen.kt`**

Replace the import `import androidx.compose.foundation.isSystemInDarkTheme` with:

```kotlin
import com.kkc.sheettracker.ui.theme.LocalKKCIsDarkTheme
```

Replace line 185 (`val isDarkTheme = isSystemInDarkTheme()`) with:

```kotlin
    val isDarkTheme = LocalKKCIsDarkTheme.current
```

Replace lines 381 and 519 (`val isDark = isSystemInDarkTheme()`) with:

```kotlin
                        val isDark = LocalKKCIsDarkTheme.current
```

(Match each occurrence's existing indentation.)

- [ ] **Step 3: Compile check**

Run: `cd C:\Scripts\KKCSheetTracker; .\gradlew.bat compileDebugKotlin`
Expected: BUILD SUCCESSFUL (no unused-import warnings for `isSystemInDarkTheme` in either file)

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/hardwoods/HardwoodsWorkspaceScreen.kt app/src/main/java/com/kkc/sheettracker/ui/specialty/SpecialtyJobDetailScreen.kt
git commit -m "fix(idle): route backdrop tint through LocalKKCIsDarkTheme instead of raw system theme"
```

---

### Task 12: Full build, full test suite, manual device verification

**Files:** None (verification only)

- [ ] **Step 1: Run the full unit test suite**

Run: `cd C:\Scripts\KKCSheetTracker; .\gradlew.bat testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests pass (including the pre-existing known-flaky `PdfMarkup` MotionEvent test failure off-device — see project memory, not a regression from this work)

- [ ] **Step 2: Build and deploy debug APK to a connected tablet**

Run: `cd C:\Scripts\KKCSheetTracker; .\gradlew.bat assembleDebug; adb install -r app\build\outputs\apk\debug\app-debug.apk`
Expected: Install succeeds

- [ ] **Step 3: Manual test — dim timeout**

In Settings → Idle Power Saving, set "Dim after (seconds)" to `5`. Leave the tablet untouched. Confirm the screen switches to dark theme + dark PDFs (if a job/sheet is open) within ~5-6 seconds. Touch the screen — confirm instant revert to the prior light/dark state.

- [ ] **Step 4: Manual test — Syncthing pause**

Set "Pause Syncthing after (seconds)" to `15` (temporary test value). Leave the tablet untouched for 15+ seconds. In Settings → Syncthing, confirm the status badge no longer shows RUNNING (it should reflect the paused state on the next status check). Touch the screen, then tap "Check Now" — confirm it reports RUNNING again.

- [ ] **Step 5: Manual test — scan throttle doesn't break freshness**

With the tablet idle past the dim timeout, make a change to a job's tracker files from another tablet/location (or touch a `.tracker` JSON file's mtime directly). Confirm the change still appears on the idle tablet within a few seconds (via `FileObserver`, unaffected by the poll throttle) — not stuck at the 5-minute-scale poll interval.

- [ ] **Step 6: Manual test — master toggle off**

Turn off "Enable Idle Power Saving" in Settings. Leave the tablet untouched well past the configured timeouts. Confirm no dimming, no poll throttling, no Syncthing pause occurs.

- [ ] **Step 7: Restore production defaults and reinstall release build**

In Settings, reset "Dim after (seconds)" to `300` and "Pause Syncthing after (seconds)" to `1800` (the test tablet's values, so it doesn't linger on the 5s/15s test settings).

Run these two steps directly rather than `adb-install-release.ps1` — that script contains unicode `Write-Host` chars that Windows PowerShell 5.1 misparses when invoked non-interactively (see project memory `adb-release-script-encoding`):

```
cd C:\Scripts\KKCSheetTracker
.\gradlew.bat assembleRelease
adb install -r app\build\outputs\apk\release\app-release.apk
```

Expected: Release build installs cleanly (per project convention: tablets run release builds — see `CLAUDE.md`). If running this step interactively at a real PowerShell prompt (not through an automated tool), `.\adb-install-release.ps1` is also fine and is the documented default.
