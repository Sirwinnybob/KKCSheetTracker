# Syncthing Foreground-Only Idle Pause Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restrict idle-triggered Syncthing pauses to a foreground KKCSheetTracker activity, resuming immediately when the app backgrounds.

**Architecture:** `MainActivity` publishes its visible lifecycle through `SyncthingSupervisor.setAppForeground(Boolean)`. The supervisor incorporates that state into its existing desired-versus-actual idle pause reconciliation, so all pause, resume, retry, and PAUSED-status behavior retains one owner. Unit tests exercise the supervisor directly; no Activity test framework is needed.

**Tech Stack:** Kotlin, Android `ComponentActivity` lifecycle callbacks, Kotlin coroutines and `StateFlow`, JUnit4.

## Global Constraints

- Do not pause Syncthing unless `onStart` has marked the app foregrounded and the observed idle phase is `SYNC_PAUSED`.
- `onStop` must immediately remove the idle pause request and resume Syncthing when it was idle-paused.
- Returning to foreground must reconcile the already-observed phase without resetting the user’s idle timer.
- Retain the existing API-key, watchdog, retry, and PAUSED-status behavior.
- Never uninstall the Android app during verification.

---

### Task 1: Gate idle pause reconciliation on app foreground lifecycle

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/sync/SyncthingSupervisor.kt`
- Modify: `app/src/main/java/com/kkc/sheettracker/MainActivity.kt`
- Test: `app/src/test/java/com/kkc/sheettracker/sync/SyncthingSupervisorTest.kt`

**Interfaces:**
- Produces: `SyncthingSupervisor.setAppForeground(isForeground: Boolean): Unit`.
- Consumes: `MainActivity.onStart()` and `MainActivity.onStop()` lifecycle callbacks.
- Invariant: idle pause is desired exactly when `isAppForeground && observedIdlePhase == IdlePhase.SYNC_PAUSED`.

- [ ] **Step 1: Write the failing supervisor tests**

Append these tests to `SyncthingSupervisorTest.kt`. Use the existing `FakeSyncthingPreferencesStore`, `FakeSyncController`, `MutableStateFlow`, and `waitUntil` helpers.

```kotlin
@Test
fun `background idle phase does not pause syncthing`() = runBlocking {
    val controller = FakeSyncController(running = true)
    val phase = MutableStateFlow(IdlePhase.ACTIVE)
    val supervisor = foregroundTestSupervisor(controller)

    supervisor.startMonitoring()
    waitUntil(2_000L) { supervisor.apiKey.value == "api-key" }
    supervisor.setAppForeground(false)
    supervisor.observeIdlePhase(phase)
    phase.value = IdlePhase.SYNC_PAUSED
    delay(100L)

    assertEquals(0, controller.pauseCalls)
    supervisor.close()
}

@Test
fun `background resumes an idle-paused syncthing service`() = runBlocking {
    val controller = FakeSyncController(running = true)
    val phase = MutableStateFlow(IdlePhase.SYNC_PAUSED)
    val supervisor = foregroundTestSupervisor(controller)

    supervisor.startMonitoring()
    waitUntil(2_000L) { supervisor.apiKey.value == "api-key" }
    supervisor.setAppForeground(true)
    supervisor.observeIdlePhase(phase)
    waitUntil(2_000L) { controller.pauseCalls == 1 }

    supervisor.setAppForeground(false)
    waitUntil(2_000L) { controller.resumeCalls == 1 }

    assertEquals(SyncthingServiceStatus.RUNNING, supervisor.status.value.status)
    supervisor.close()
}

@Test
fun `foreground reentry pauses only when current phase remains idle`() = runBlocking {
    val controller = FakeSyncController(running = true)
    val phase = MutableStateFlow(IdlePhase.SYNC_PAUSED)
    val supervisor = foregroundTestSupervisor(controller)

    supervisor.startMonitoring()
    waitUntil(2_000L) { supervisor.apiKey.value == "api-key" }
    supervisor.setAppForeground(false)
    supervisor.observeIdlePhase(phase)
    supervisor.setAppForeground(true)
    waitUntil(2_000L) { controller.pauseCalls == 1 }

    supervisor.setAppForeground(false)
    waitUntil(2_000L) { controller.resumeCalls == 1 }
    phase.value = IdlePhase.ACTIVE
    supervisor.setAppForeground(true)
    delay(100L)

    assertEquals(1, controller.pauseCalls)
    supervisor.close()
}
```

Add the test-only helper inside the same test file:

```kotlin
private fun foregroundTestSupervisor(controller: FakeSyncController) = SyncthingSupervisor(
    context = null,
    runtimeConfig = SyncthingRuntimeConfig(
        watchdog = SyncthingWatchdogConfig(intervalMs = 10_000L, autoStartOnFailure = false)
    ),
    preferencesStore = FakeSyncthingPreferencesStore("api-key"),
    scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    managerFactory = { controller }
)
```

- [ ] **Step 2: Run the focused test class and verify RED**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.sync.SyncthingSupervisorTest"`

Expected: compilation fails because `setAppForeground` is undefined.

- [ ] **Step 3: Implement the foreground gate in the supervisor**

In `SyncthingSupervisor.kt`, add a private foreground flag initialized to `false` next to the existing observed idle state. Add this public method beside `observeIdlePhase`:

```kotlin
fun setAppForeground(isForeground: Boolean) {
    scope.launch {
        idleReconcileMutex.withLock {
            appIsForeground = isForeground
            idlePauseDesired = appIsForeground && observedIdlePhase == IdlePhase.SYNC_PAUSED
        }
        val currentApiKey = _apiKey.value.trim()
        if (currentApiKey.isBlank()) return@launch
        reconcileIdleSync(currentApiKey)
        publishIdleStatusIfKnown()
    }
}
```

Update both `observeIdlePhase` and `reconcileIdleSync` to calculate the desired pause as `appIsForeground && phase == IdlePhase.SYNC_PAUSED`. Do not issue an idle pause while the app is backgrounded; a background transition must instead reconcile any successful prior pause to resume.

- [ ] **Step 4: Wire Android activity lifecycle**

In `MainActivity.onStart()`, call `syncthingSupervisor.setAppForeground(true)` before `checkNow()`. Add `onStop()`:

```kotlin
override fun onStop() {
    if (::syncthingSupervisor.isInitialized) {
        syncthingSupervisor.setAppForeground(false)
    }
    super.onStop()
}
```

Do not reset `IdleActivityTracker` in either lifecycle callback.

- [ ] **Step 5: Run focused tests and verify GREEN**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.sync.SyncthingSupervisorTest"`

Expected: PASS, including the three foreground lifecycle tests.

- [ ] **Step 6: Run regression verification**

Run: `./gradlew.bat testDebugUnitTest` and then `./gradlew.bat assembleRelease`.

Expected: both commands exit successfully.

- [ ] **Step 7: Commit the task**

```bash
git add app/src/main/java/com/kkc/sheettracker/sync/SyncthingSupervisor.kt \
        app/src/main/java/com/kkc/sheettracker/MainActivity.kt \
        app/src/test/java/com/kkc/sheettracker/sync/SyncthingSupervisorTest.kt
git commit -m "fix: gate idle sync pause to foreground"
```
