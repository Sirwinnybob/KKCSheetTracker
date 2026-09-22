# Centralized Update System — Design

## Problem

Both KKCSheetTracker and Hours Tracker (`C:\Scripts\Hours Tracker\AndroidApp`) currently pop an unprompted `AlertDialog` whenever they detect a newer APK in the shared `.Updates` folder. Sheet Tracker already scans for both its own update and Hours Tracker's (it installs Hours Tracker externally today), but the popups interrupt work on the shop floor. We want update management centralized in Sheet Tracker's Settings screen: a quiet badge dot when something's pending, a "Pending Updates" section listing what's available, and per-app / "Update All" buttons — no more forced dialogs.

## Scope

- Sheet Tracker: replace auto-popup update dialogs with a badge + Settings section.
- Hours Tracker: stop its own self-scan/self-popup; keep its `UpdateManager`/`reinstallLatest()` only as a manual fallback, not part of normal detection.
- No change to the underlying detection logic (folder scan, version comparison) or to how an APK is actually installed (system install intent).

## Architecture

Sheet Tracker's `UpdateManager` remains the single owner of update detection and installation for both apps — it already scans the shared `.Updates` folder for its own APK (`computeSelfUpdateApk`) and for known external apps including Hours Tracker (`computeExternalUpdates`). What changes is what happens with a scan result:

- **Today:** a scan result immediately renders an `AlertDialog` in `MainActivity.kt` (self-update dialog, external-update dialog).
- **New:** a scan result only updates state (`pendingUpdateApk`, `pendingExternalUpdates`). A small dot badge appears on the Settings nav icon. Opening Settings shows a "Pending Updates" section at the top with per-app Update buttons and an Update All button when both are pending.

Hours Tracker keeps its own `UpdateManager` class only for `reinstallLatest()` (existing admin fallback reachable from its own settings). It no longer scans on launch or renders any update dialog — it's not a participant in detection anymore, just a manual escape hatch.

## Detection / scan cadence

- Keep the existing triggers: `onStart` (app foreground) already calls `updateManager.checkForUpdates(checkSelf = true)`.
- Add a periodic re-scan while the app stays foregrounded, since shop tablets are often left open all day without triggering `onStart` again. Implemented as a coroutine loop scoped to `lifecycleScope` with `repeatOnLifecycle(Lifecycle.State.STARTED)`, calling `updateManager.checkForUpdates(checkSelf = true)` every 20 minutes. This is a plain delay loop — no new poller class needed.

## State exposed from UpdateManager

The scan/compare logic (`computeSelfUpdateApk`, `computeExternalUpdates`, APK version-code comparison) is unchanged. Two simplifications from current behavior:

- Remove `isSilentUpdateSupported`, `installPendingUpdateSilently()`, and the `DeviceOwnerUpdateFallback` class entirely. Self-update becomes manual-install-intent only — there is no more silent device-owner path, and no more "Clock In/Out First" prompt before installing (that existed to protect an open punch during the silent-restart flow, which no longer exists). `ClockForUpdateOverlay.kt` is deleted as it becomes unused.
- Remove the `canSkip` / persisted-skipped-version logic from `computeExternalUpdates` and delete `skipExternalUpdate()`. That existed to avoid re-nagging with a popup; since there's no more popup, there's nothing to avoid.

`pendingUpdateApk: File?` (self) and `pendingExternalUpdates: List<ExternalAppUpdate>` (external, i.e. Hours Tracker) remain the source of truth. A derived boolean (`pendingUpdateApk != null || pendingExternalUpdates.isNotEmpty()`) drives the badge.

## UI: badge dot on Settings

`AppScaffold.kt` already threads per-destination notification counts (`supplyNotificationCount`, `safetyNotificationCount`) into `AppBottomNavBar` → `MorphingNavIconRow`, rendered via `BadgedBox`/`Badge` on the `SUPPLY` and `STANDARDS` destinations. `SETTINGS` gets the same treatment, but with an empty `Badge {}` — a plain dot, not a count. A new `Boolean` param `hasPendingUpdates` is threaded the same way: `MainActivity` → `AppNavigation` → `AppScaffold` → `AppBottomNavBar` → `MorphingNavIconRow`.

## UI: Pending Updates section in Settings

At the top of `SettingsScreen.kt`, above the existing sections, rendered only when at least one update is pending:

- One row per pending app (Sheet Tracker and/or Hours Tracker): app name, version, an "Update" button that installs just that app.
- When **both** are pending, an "Update All" button appears above the two rows. Tapping it fires the Hours Tracker install intent first, then the Sheet Tracker self-install intent. Hours Tracker must go first because installing Sheet Tracker over itself kills the running process — anything queued after that point would not fire reliably.

New `SettingsScreen` params: `pendingSelfUpdate: File?`, `pendingExternalUpdates: List<ExternalAppUpdate>`, `onInstallSelfUpdate: () -> Unit`, `onInstallExternalUpdate: (ExternalAppUpdate) -> Unit`, `onInstallAll: () -> Unit`.

## Files touched

- **`app/src/main/java/com/kkc/sheettracker/MainActivity.kt`** — remove the self-update and external-update `AlertDialog` blocks, remove `showClockForUpdate` state and the `ClockForUpdateOverlay` call site, add the periodic re-scan loop, wire the new badge/settings params through `AppNavigation`.
- **`app/src/main/java/com/kkc/sheettracker/update/UpdateManager.kt`** — drop silent-install path, `isSilentUpdateSupported`, skip/persisted-skip logic.
- **`app/src/main/java/com/kkc/sheettracker/update/DeviceOwnerUpdateFallback.kt`** — delete (unused once the silent path is gone).
- **`app/src/main/java/com/kkc/sheettracker/ui/timecard/ClockForUpdateOverlay.kt`** — delete (only caller removed).
- **`app/src/main/java/com/kkc/sheettracker/ui/components/AppScaffold.kt`** — add `hasPendingUpdates: Boolean` param, badge dot on `SETTINGS`.
- **`app/src/main/java/com/kkc/sheettracker/ui/settings/SettingsScreen.kt`** — add the Pending Updates section and its new params.
- **`C:\Scripts\Hours Tracker\AndroidApp\app\src\main\java\com\example\timecard\MainActivity.kt`** — remove the `updateManager.checkForUpdates()` call; stop passing `pendingUpdate`/`onInstallUpdate` to `TimecardApp` (keep `updateManager` and `onReinstallLatest` wiring).
- **`C:\Scripts\Hours Tracker\AndroidApp\app\src\main\java\com\example\timecard\TimecardApp.kt`** — remove the now-dead `pendingUpdate`/`onInstallUpdate` params and their dialog block.

## Error handling

Unchanged from today. `installApk()` already toasts on failure and triggers the existing install-permission-request flow if permission is missing. This design relocates *when* install is triggered (user tap in Settings vs. an unprompted dialog), not the install mechanics themselves, so no new error paths are introduced.

## Testing

Mostly manual verification on a shop tablet, since this is Activity lifecycle + system package-install intents (not meaningfully unit-testable). If the periodic re-scan loop can be isolated behind an injectable interval/clock without complicating `MainActivity`, a small test for its cadence is worth adding; otherwise this is verified by hand.
