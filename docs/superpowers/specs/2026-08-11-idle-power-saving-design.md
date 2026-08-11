# Idle Power Saving ("Screensaver") — Design Spec

**Date:** 2026-08-11
**Status:** Draft — awaiting review

---

## Summary

After a configurable idle period (default 5s–300s range, default 300s), the app temporarily forces dark theme + "Standard Sheets" off (dark PDF variant) app-wide — without touching the user's real theme preference — to reduce screen brightness/power draw on shop tablets that stay on but unattended. During the same idle period, background job-scan polling throttles from its normal 10s/20s intervals to a configurable interval (default 300s). After a second, longer idle period (default 1800s), Syncthing sync is paused via its local REST API. Any user interaction (touch, hardware key/button) instantly reverts all three behaviors.

---

## Requirements

| Category | Behavior |
|----------|----------|
| **Scope** | App-wide (all screens, including timeclock) |
| **Trigger** | No user interaction for N seconds (touch or hardware key/button) |
| **Dim behavior** | Force `dark_theme=true`, `use_standard_sheets=false` as *effective* values only — real prefs untouched |
| **Scan throttle** | `TrackerChangeMonitor`/`StaticCachePoller` poll interval raised to configured value; `FileObserver` (inotify) stays live — instant cross-tablet updates still land |
| **Sync pause** | Syncthing paused via REST `/rest/system/pause` (not the existing broadcast STOP intent) so the 1hr watchdog health-check still succeeds and doesn't fight the pause with an auto-restart |
| **Granularity** | Master enable toggle + 2 independent timeouts (seconds), no per-behavior toggles |
| **Revert** | Instant on any interaction — no debounce/delay on the way back to `ACTIVE` |
| **Timeout range** | Full user control in seconds, no enforced minimum above 5s (testing needs 5s to be usable) |
| **Persistence** | DataStore, per-device (`screensaver_settings` file, no Syncthing sync — matches per-tablet nature of the feature) |
| **Default** | Enabled, dim at 300s, sync-pause at 1800s |

---

## Architecture

### Data Layer

**`IdlePowerSaveStore`** (new, `data/IdlePowerSaveStore.kt`) — DataStore file `screensaver_settings`, pattern copied from `ScannerSettingsStore`:

```kotlin
data class IdlePowerSaveConfig(
    val enabled: Boolean = true,
    val idleTimeoutSeconds: Int = 300,
    val syncthingPauseTimeoutSeconds: Int = 1800,
)

class IdlePowerSaveStore(context: Context) {
    val configFlow: Flow<IdlePowerSaveConfig> = dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs -> IdlePowerSaveConfig(...) }
    suspend fun save(config: IdlePowerSaveConfig) { ... }
}
```

### Idle Tracking

**`IdleActivityTracker`** (new, `ui/idle/IdleActivityTracker.kt`):

```kotlin
enum class IdlePhase { ACTIVE, DIMMED, SYNC_PAUSED }

class IdleActivityTracker(private val configFlow: Flow<IdlePowerSaveConfig>) {
    private var lastInteractionAt = SystemClock.elapsedRealtime()
    val phase: StateFlow<IdlePhase>

    fun reset() { lastInteractionAt = SystemClock.elapsedRealtime() }

    // internal coroutine: while(isActive) { delay(1000); recompute() }
    // recompute compares (now - lastInteractionAt) against config's two
    // thresholds (cumulative from the same lastInteractionAt, not restarted
    // at the dim point); if !config.enabled, phase pinned ACTIVE.
}
```

Reset triggers:
1. `MainActivity.onUserInteraction()` override — system-wide touch + hardware key/button events (needed for barcode-scanner trigger buttons per existing `ScannerSettingsStore` torch integration).
2. `Modifier.pointerInput` on `AppNavigation`'s root (`NavGraph.kt`) as a belt-and-suspenders backstop.

**`LocalIdlePhase`** CompositionLocal — provided in `MainActivity.kt`'s existing `CompositionLocalProvider(LocalLowEndMode, ...)` block, consumed via `LocalIdlePhase.current` — same threading precedent as `LocalLowEndMode`.

### Consumers

| Consumer | Behavior on phase change |
|----------|---------------------------|
| `MainActivity` → `KKCTheme(darkTheme=...)` call site | `darkTheme = if (phase >= DIMMED) true else effectivePref`; same override for `use_standard_sheets` effective value passed down to viewer screens |
| `TrackerChangeMonitor` | Holds phase `StateFlow` ref; `pollingIntervalMs` effectively becomes `config.idleTimeoutSeconds`-derived value (5min default) when `phase >= DIMMED`, reverts to 10s on `ACTIVE`. `FileObserver` unaffected. |
| `StaticCachePoller` | Same pattern, base interval 20s |
| `SyncthingSupervisor` | New `pauseNow()`/`resumeNow()` methods, called REST-side (`http://127.0.0.1:8384/rest/system/pause` / `/resume`, same host+API-key path as existing health-check ping). Edge-triggered on `phase` transitions into/out of `SYNC_PAUSED` only, guarded by internal `isPaused` flag to prevent double-fire on rapid phase flicker (relevant at low test timeouts). |

### UI Layer

**Settings → new "Idle Power Saving" card** (`SettingsScreen.kt`, placed near "Performance"):

```
Idle Power Saving                    [☑]  ← master
  Dim after (seconds)                [300]
  Pause Syncthing after (seconds)    [1800]
```

Subtitle text: "Switches to dark sheets + black background to save battery on tablets left on but idle. Reverts instantly on touch. Lower values (e.g. 5) are useful for testing."

---

## Data Flow

1. Interaction → `IdleActivityTracker.reset()` → `lastInteractionAt = now`, phase snaps to `ACTIVE` immediately (no delay).
2. 1s-tick coroutine loop compares elapsed time to `configFlow`'s two thresholds (live-reactive — changing Settings takes effect without app restart).
3. `elapsed >= idleTimeoutSeconds` → phase `DIMMED`. Theme + poll-interval consumers react via `LocalIdlePhase`/direct `StateFlow` collection.
4. `elapsed >= syncthingPauseTimeoutSeconds` → phase `SYNC_PAUSED` (cumulative, not restarted at the `DIMMED` point). `SyncthingSupervisor` fires `pauseNow()` once.
5. Any interaction at any phase → immediate `ACTIVE`, `SyncthingSupervisor` fires `resumeNow()` once if it was paused, theme/poll-interval revert on next recomposition/poll tick.

---

## Error Handling

- `IdlePowerSaveStore` DataStore read failure → falls back to defaults (enabled, 300s/1800s) via existing `catch { IOException -> emptyPreferences() }` pattern — never crashes.
- `pauseNow()`/`resumeNow()` REST failure (network hiccup, Syncthing app not installed, API key missing) → swallow + log, matching existing `runHealthCheck` error handling. Doesn't block the phase transition or retry-loop the UI; if pause silently fails, the 1hr watchdog cycle just finds it still running — harmless.
- `isPaused` guard flag prevents double REST calls on phase flicker.
- Effective-theme-override racing a mid-recomposition screen → worst case one frame of wrong PDF variant before recomposition catches up; no crash risk.

---

## Testing

- Manual: `idleTimeoutSeconds=5`, leave tablet untouched, confirm dim at ~5s, instant revert on touch.
- Manual: `syncthingPauseTimeoutSeconds=10` (temporary test value), confirm Syncthing Settings card status badge reflects paused state, resumes on interaction.
- Unit test `IdleActivityTracker` phase math against a fake clock (pure function, no Android deps).
- Add test asserting `TrackerChangeMonitor`/`StaticCachePoller` interval changes when phase flips (both already accept interval as constructor param).
- No new Compose UI test suite — matches project's existing testing depth.

---

## Implementation Tasks

| # | Task | Files | Test Type |
|---|------|-------|-----------|
| 1 | `IdlePowerSaveStore` DataStore | New file + test | Unit |
| 2 | `IdleActivityTracker` phase state machine | New file + test | Unit |
| 3 | `LocalIdlePhase` CompositionLocal + provide in `MainActivity` | `MainActivity.kt` | Compose |
| 4 | `onUserInteraction()` override + root `pointerInput` reset | `MainActivity.kt`, `NavGraph.kt` | Manual |
| 5 | Theme override wiring (`KKCTheme` call sites) | `MainActivity.kt` | Manual/Screenshot |
| 6 | Poll-interval wiring | `TrackerChangeMonitor.kt`, `StaticCachePoller.kt` + tests | Unit |
| 7 | `SyncthingSupervisor.pauseNow()`/`resumeNow()` via REST | `SyncthingSupervisor.kt`, `SyncController.kt` + test | Unit |
| 8 | Settings UI card | `SettingsScreen.kt` | Manual |
| 9 | Integration test & manual verify on shop tablet | — | Manual |

---

## Acceptance Criteria

1. "Idle Power Saving" card appears in Settings with master toggle + 2 seconds-based timeout fields
2. Master OFF → phase pinned `ACTIVE`, no behavior change from today
3. Idle past `idleTimeoutSeconds` → dark theme + dark PDFs active app-wide, real theme prefs unchanged in storage
4. Idle past `syncthingPauseTimeoutSeconds` → Syncthing paused via REST, watchdog does not auto-restart it
5. Any touch/key interaction at any phase → instant revert of all 3 behaviors
6. `idleTimeoutSeconds` as low as 5 works correctly (for testing)
7. Poll interval throttles to configured value while dimmed; `FileObserver`-driven instant updates unaffected
8. Settings persist across app restart, per-device (no Syncthing sync of this config)
9. No crash on DataStore read failure, REST pause/resume failure, or phase flicker at low timeouts

---

## Non-Goals

- Actual screen dimming/brightness API calls (Android `WindowManager.LayoutParams.screenBrightness`) — out of scope, this is a UI/content-level power save, not a brightness control
- Per-tablet remote config or fleet-wide rollout of settings
- True screen-off/lock — screen must stay on per requirement
- Syncing this config across tablets via Syncthing

---

## Risks & Mitigations

| Risk | Mitigation |
|------|------------|
| `onUserInteraction()` doesn't fire for all input types on some OEM tablets | Backstop `pointerInput` reset on `AppNavigation` root catches touch even if the Activity callback is unreliable |
| Rapid phase flicker at low test timeouts double-fires Syncthing pause/resume | `isPaused` guard flag, edge-triggered calls only |
| Forgetting a `KKCTheme`/`isSystemInDarkTheme()` call site that reads theme independently (several screens do this per existing research) | Audit all `isSystemInDarkTheme()` and raw `dark_theme`/`use_standard_sheets` pref reads found in `SheetViewerScreen.kt`, `SpecialtyJobDetailScreen.kt`, `MoldingDetailOverlay.kt`, `MoldingListScreen.kt`, `HardwoodsWorkspaceScreen.kt` — route them through the same effective-value logic instead of reading raw prefs directly |
| Syncthing REST pause endpoint unavailable on older Syncthing versions | Swallow failure, log; feature degrades to sync-never-pauses, not a crash |

---

## Rollout

1. Merge feature behind master toggle (default ON, defaults 300s/1800s)
2. Deploy to one shop tablet for manual validation (test with 5s timeout first)
3. Confirm no regression in job-data freshness or Syncthing status across both shop locations (VPN link)
4. Roll out to remaining tablets via standard release build deploy
