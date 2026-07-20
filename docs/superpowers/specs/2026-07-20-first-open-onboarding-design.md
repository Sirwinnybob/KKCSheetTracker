# First-Open Onboarding Flow — Design

## Problem

Fresh tablet installs currently hit a broken first-run experience:

1. **All-files-access permission requested up to 3 times.** `MainActivity.onCreate` calls
   `requestStoragePermissions()` (`MainActivity.kt:568`), which silently fires
   `ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION` with no rationale. Immediately after, in the
   same `onCreate`, `updateManager.checkForUpdates()` runs and independently re-checks
   `isExternalStorageManager()` (`UpdateManager.kt:136`) — if still ungranted, it shows its own
   "Permission Required" dialog and fires the *same* Settings intent again. `onStart()` calls
   `checkForUpdates()` again on every foreground, re-firing the dialog if the grant hasn't landed
   yet. Two independent, mutually-unaware code paths asking for the same permission.

2. **Install-unknown-apps permission doesn't take effect until restart.** `UpdateManager.installApk()`
   (`UpdateManager.kt:436-440`) only checks `canRequestPackageInstalls()` reactively, when an update
   APK has already been found and the user taps Install. Granting it in Settings and returning to
   the app doesn't re-trigger the install — nothing calls `installApk()` again until the app is
   force-closed and reopened, at which point `checkForUpdates()` runs fresh and the (now-granted)
   permission finally lets the install proceed.

3. **Syncthing API key prompt fires unconditionally on first frame.** `MainActivity.kt:397` shows
   the key-entry dialog the moment `syncthingApiKey.isBlank()`, stacking on top of permission and
   update dialogs before the user has even gotten through basic setup.

4. **Migration/basePath checks race the storage permission grant.** `basePath` discovery and the
   `migrationMarkerPath.isFile` check run unconditionally at the top of `onCreate`, before storage
   access is confirmed — a fresh install can flash `MigrationRequiredScreen` ("no data found")
   purely because the permission grant hasn't landed yet, conflating two unrelated problems.

No onboarding/first-run screen exists today — this is greenfield.

## Out of scope

Removing the device-owner silent-update path (`DeviceOwnerUpdateFallback`, `updater-agent`'s
device-owner role) is a separate, previously-abandoned approach the user wants ripped out. That
work lands first, as its own change, before this one. This design assumes it is already done:
**all update installs go through the single legacy `installApk()` prompt path** — there is no
dual silent/legacy branching to account for here. If that removal hasn't landed yet when this is
implemented, treat it as a blocking prerequisite, not something to re-add here.

## Approach

Sequential dialog controller, no new screen. Rejected alternative: a dedicated full-screen
Step-1/2/3 wizard — more UI investment for no real gain, since the person running first-open setup
(the shop owner) is already familiar with the app and just wants it to stop double-asking and
breaking without a restart.

## Design

### PermissionFlowController

A plain Kotlin class (no Compose, no new Activity/screen) owned by `MainActivity`, constructed
before any file-system, migration, or update-check code runs in `onCreate`.

Fixed step order:

1. `POST_NOTIFICATIONS` (Android 13+ / `TIRAMISU`) — system dialog directly, no custom rationale
   needed (it's a single lightweight OS prompt).
2. `MANAGE_EXTERNAL_STORAGE` (Android 11+ / `R`) — custom `AlertDialog` rationale ("Sheet Tracker
   needs full storage access to read job files and sync data. Tap OK to grant it in the next
   screen.") before launching `Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION`.
3. Install-unknown-apps (`canRequestPackageInstalls()`) — custom `AlertDialog` rationale ("Needed
   to install app updates when they're released.") before launching
   `Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES`.

The controller has no persisted "onboarding complete" flag. It re-derives state from the real OS
permission APIs on every `onCreate`, `onStart`, and Settings-return — already-granted steps are
skipped silently. This makes it naturally idempotent across force-closes, app reinstalls, and
factory resets, with no stale-flag bugs to worry about.

`MainActivity.requestStoragePermissions()` is deleted. `UpdateManager.checkForUpdates()`'s own
`isExternalStorageManager()` check (`UpdateManager.kt:136`) is deleted — `UpdateManager` becomes a
pure consumer, not invoked at all until the controller reports all three steps satisfied.

### Settings-return handling (fixes the restart bug)

Each Settings intent is launched via `ActivityResultContracts.StartActivityForResult` (registered
in `onCreate`), not a bare `startActivity()` call. These particular Settings screens don't return a
reliable result code, so the callback ignores the result code and instead re-checks the real
permission state directly, then either advances to the next unsatisfied step or finishes the flow.

This is what fixes the install-permission bug: granting install-unknown-apps and returning to the
app now re-triggers the pending `installApk()` call directly — no force-close/reopen needed.

`onStart()` also re-runs the controller's check (covers backing out of Settings without granting,
or granting while backgrounded) — but only re-prompts for steps still actually ungranted, never a
satisfied one. Net effect: each permission is asked once per still-missing grant, not repeatedly.

### Migration/basePath gating

`basePath` discovery and `migrationMarkerPath.isFile` no longer run at the very top of `onCreate`.
They run only after the controller reports storage access granted. Until then, `MainActivity`
renders a minimal "Setting up..." state — not `MigrationRequiredScreen`, which stays reserved for
"permission is fine, data genuinely isn't there." This stops a fresh install from misleadingly
flashing the no-data screen while the storage grant is still in flight.

### Syncthing key deferral

`showSyncthingSetupPrompt` no longer shows unconditionally on the first compose frame. It shows
only after the permission controller finishes, and only if `syncthingApiKey.isBlank()`. The
existing "Later" dismiss stays. A new `lastSyncthingPromptAtMs` prefs value throttles the re-nag to
once per app-foreground-after-N-hours (not every launch) until a key is actually saved; once saved,
the prompt never shows again. The Settings screen's manual key-entry field is unchanged, for setting
it later without waiting on the nag.

### Error handling

- User denies or backs out of a Settings screen: that step stays pending, app continues in a
  degraded-but-usable state (e.g., no storage access yet → nothing to scan, `AppNavigation` shows
  empty/loading rather than crashing). Re-prompted on next `onStart`.
- Pre-API-30/33 devices: existing `Build.VERSION.SDK_INT` guards around each check are preserved —
  steps below the relevant API level skip cleanly, as today.
- Settings intent fails to resolve on some OEM tablets: existing try/catch fallback to
  `ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION` is preserved.

### Testing

- Unit test: the controller's "given this permission-state snapshot, what's the next step"
  decision logic as a pure function, independent of Android framework calls — same pattern as
  `SyncthingSupervisorTest.kt`.
- Manual/on-device: factory-reset test tablet — confirm a single clean walk through all 3 steps
  with no duplicate asks; confirm an update install auto-resumes after granting install-unknown-apps
  without a restart; confirm the Syncthing prompt doesn't appear until permission steps are done.

## Non-goals

- No changes to the basePath auto-detection logic itself (`findDefaultBasePath()`), only to when it
  runs relative to permission grants.
- No changes to `MigrationRequiredScreen` behavior once storage access is confirmed granted.
- No device-owner / silent pre-grant mechanism — out of scope per above, and unverified whether the
  relevant special permissions (`MANAGE_EXTERNAL_STORAGE`, install-unknown-apps) are even grantable
  via `DevicePolicyManager` the way standard runtime permissions are.
