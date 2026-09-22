# Centralized Update System Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the unprompted update dialogs in KKCSheetTracker and Hours Tracker with a single quiet badge dot on Sheet Tracker's Settings nav icon and a "Pending Updates" section at the top of Sheet Tracker's Settings screen, from which the admin can update either app or both (Hours Tracker first) on their own schedule.

**Architecture:** Sheet Tracker's existing `UpdateManager` already scans the shared `.Updates` folder for both its own APK and Hours Tracker's APK. Only the *presentation* changes: scan results stop triggering `AlertDialog`s and instead become plain state (`pendingUpdateApk`, `pendingExternalUpdates`) that drives a nav-bar badge and a Settings section. Hours Tracker's own update-scanning/dialog code is removed entirely; it keeps only its `reinstallLatest()` manual fallback.

**Tech Stack:** Kotlin, Jetpack Compose, two separate Gradle/git projects — `C:\Scripts\KKCSheetTracker` and `C:\Scripts\Hours Tracker\AndroidApp`.

**Reference spec:** `docs/superpowers/specs/2026-09-22-centralized-update-system-design.md`

---

## Before you start

Two separate git repositories are touched:
- `C:\Scripts\KKCSheetTracker` (Tasks 1–8)
- `C:\Scripts\Hours Tracker\AndroidApp` (Tasks 9–10)

Each task's commands assume the working directory is the repo root for that task — `cd` there explicitly before running `git`/`gradlew` commands if your shell doesn't already default there. On Windows, quote the Hours Tracker path (it contains a space): `cd "C:\Scripts\Hours Tracker\AndroidApp"`.

Run unit tests with:
```bash
cd C:\Scripts\KKCSheetTracker && .\gradlew.bat test --tests "com.kkc.sheettracker.*"
```
or, from Hours Tracker:
```bash
cd "C:\Scripts\Hours Tracker\AndroidApp" && .\gradlew.bat test --tests "com.example.timecard.*"
```

These are plain JVM unit tests (JUnit, no Robolectric/instrumentation) reading source files as text — the same style as the existing `app/src/test/java/com/kkc/sheettracker/navigation/LegacyStandardsTransitionWiringTest.kt`. This project doesn't have a way to unit-test Activity lifecycle or Compose rendering directly, so "wiring tests" that assert specific code patterns exist/don't exist in the source are the established substitute. After all wiring tasks land, a manual on-tablet smoke test closes the loop (see Task 8's manual verification note).

---

### Task 1: Remove Sheet Tracker's silent self-update path and its dialog

**Files:**
- Create: `app/src/test/java/com/kkc/sheettracker/testutil/SourceFiles.kt`
- Create: `app/src/test/java/com/kkc/sheettracker/MainActivityUpdateWiringTest.kt`
- Modify: `app/src/main/java/com/kkc/sheettracker/update/UpdateManager.kt`
- Modify: `app/src/main/java/com/kkc/sheettracker/MainActivity.kt`
- Delete: `app/src/main/java/com/kkc/sheettracker/update/DeviceOwnerUpdateFallback.kt`
- Delete: `app/src/main/java/com/kkc/sheettracker/ui/timecard/ClockForUpdateOverlay.kt`

- [x] **Step 1: Write the test helper for locating main-source files from a unit test**

Create `app/src/test/java/com/kkc/sheettracker/testutil/SourceFiles.kt`:

```kotlin
package com.kkc.sheettracker.testutil

import java.io.File

/** Locates a file under `app/src/main/java/` by its path relative to that root, walking up from the working directory. Used by source-text "wiring" tests that assert specific code patterns exist (or don't) without needing to run the full Compose/Activity stack. */
object SourceFiles {
    fun mainSource(relativePath: String): File {
        var dir = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(6) {
            val candidate = File(dir, "app/src/main/java/$relativePath")
            if (candidate.exists()) return candidate
            val direct = File(dir, "src/main/java/$relativePath")
            if (direct.exists()) return direct
            dir = dir.parentFile ?: return@repeat
        }
        error("Unable to locate $relativePath from ${System.getProperty("user.dir")}")
    }
}
```

- [x] **Step 2: Write the failing test**

Create `app/src/test/java/com/kkc/sheettracker/MainActivityUpdateWiringTest.kt`:

```kotlin
package com.kkc.sheettracker

import com.kkc.sheettracker.testutil.SourceFiles
import org.junit.Assert.assertFalse
import org.junit.Test

private fun String.containsWord(word: String): Boolean = Regex("\\b${Regex.escape(word)}\\b").containsMatchIn(this)

class MainActivityUpdateWiringTest {

    @Test
    fun silentSelfUpdatePathIsRemoved() {
        val mainActivitySource = SourceFiles.mainSource("com/kkc/sheettracker/MainActivity.kt").readText()
        val updateManagerSource = SourceFiles.mainSource("com/kkc/sheettracker/update/UpdateManager.kt").readText()

        assertFalse("MainActivity.kt must not reference DeviceOwnerUpdateFallback", mainActivitySource.contains("DeviceOwnerUpdateFallback"))
        assertFalse("MainActivity.kt must not reference ClockForUpdateOverlay", mainActivitySource.contains("ClockForUpdateOverlay"))
        assertFalse("MainActivity.kt must not track showClockForUpdate", mainActivitySource.contains("showClockForUpdate"))
        assertFalse("UpdateManager.kt must not expose isSilentUpdateSupported", updateManagerSource.contains("isSilentUpdateSupported"))
        assertFalse("UpdateManager.kt must not expose installPendingUpdateSilently", updateManagerSource.contains("installPendingUpdateSilently"))
    }
}
```

- [x] **Step 3: Run test to verify it fails**

Run: `cd C:\Scripts\KKCSheetTracker && .\gradlew.bat test --tests "com.kkc.sheettracker.MainActivityUpdateWiringTest"`
Expected: FAIL — `silentSelfUpdatePathIsRemoved` fails because `DeviceOwnerUpdateFallback`/`ClockForUpdateOverlay`/`isSilentUpdateSupported`/`installPendingUpdateSilently` are all still present.

- [x] **Step 4: Remove the silent-update state and method from UpdateManager.kt**

In `app/src/main/java/com/kkc/sheettracker/update/UpdateManager.kt`, delete this property (currently lines 68):

```kotlin
    var isSilentUpdateSupported by mutableStateOf(false)
```

Delete this whole method (currently lines 86–112):

```kotlin
    fun installPendingUpdateSilently() {
        try {
            val intent = Intent("com.kkc.updateragent.TRIGGER_UPDATE").apply {
                setPackage("com.kkc.updateragent")
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            }
            activity.sendBroadcast(intent)
            Toast.makeText(activity, "Silent update triggered. App will close shortly.", Toast.LENGTH_LONG).show()

            val currentBasePath = basePath
            val currentTabletId = tabletId
            if (currentBasePath != null && currentTabletId != null) {
                val handler = android.os.Handler(android.os.Looper.getMainLooper())
                handler.postDelayed({
                    val fallbackFile = File(currentBasePath, ".appupdates/$currentTabletId/updater-fallback-required.json")
                    if (fallbackFile.isFile) {
                        Log.w(TAG, "Silent update failed/fell back. Reverting to legacy prompt.")
                        isSilentUpdateSupported = false
                    }
                }, 5000L)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send update broadcast", e)
            Toast.makeText(activity, "Failed to trigger silent update: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
```

- [x] **Step 5: Remove the self-update dialog, its clock-out overlay, and the device-owner check from MainActivity.kt**

In `app/src/main/java/com/kkc/sheettracker/MainActivity.kt`, replace this block (currently lines 190–207):

```kotlin
        val useLegacyUpdatePrompt = DeviceOwnerUpdateFallback(this)
            .shouldUseLegacyPrompt(basePath = basePath, tabletId = tabletId)
        updateManager = UpdateManager(
            activity = this,
            onRequestInstallPermission = { onGranted ->
                pendingSettingsReturnAction = {
                    if (packageManager.canRequestPackageInstalls()) {
                        onGranted()
                    }
                }
                launchOnboardingSettingsIntent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            }
        ).apply {
            this.basePath = basePath
            this.tabletId = tabletId
            isSilentUpdateSupported = !useLegacyUpdatePrompt
        }
        updateManager.checkForUpdates(checkSelf = true)
```

with:

```kotlin
        updateManager = UpdateManager(
            activity = this,
            onRequestInstallPermission = { onGranted ->
                pendingSettingsReturnAction = {
                    if (packageManager.canRequestPackageInstalls()) {
                        onGranted()
                    }
                }
                launchOnboardingSettingsIntent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            }
        ).apply {
            this.basePath = basePath
            this.tabletId = tabletId
        }
        updateManager.checkForUpdates(checkSelf = true)
```

Then remove this block (currently lines 451–458):

```kotlin
                    var showClockForUpdate by rememberSaveable { mutableStateOf(false) }

                    if (showClockForUpdate) {
                        ClockForUpdateOverlay(
                            basePath = basePath,
                            onFinished = { showClockForUpdate = false }
                        )
                    }

```

Then replace this block (currently lines 460–501):

```kotlin
                    if (updateManager.pendingUpdateApk != null && !showClockForUpdate) {
                        val isSilent = updateManager.isSilentUpdateSupported
                        AlertDialog(
                            onDismissRequest = {},
                            title = { Text(if (isSilent) "Update Ready" else "Update Available") },
                            text = {
                                Column {
                                    Text(
                                        if (isSilent) "A new version of KKC Sheet Tracker is ready to install. Update now? (The app will close and update silently)"
                                        else "A new version of KKC Sheet Tracker is available. Install now?"
                                    )
                                    Spacer(Modifier.height(16.dp))
                                    FilledTonalButton(
                                        onClick = { showClockForUpdate = true },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text("Clock In / Out First")
                                    }
                                }
                            },
                            confirmButton = {
                                Button(
                                    onClick = {
                                        if (isSilent) {
                                            updateManager.installPendingUpdateSilently()
                                        } else {
                                            updateManager.installPendingUpdate()
                                        }
                                    }
                                ) {
                                    Text(if (isSilent) "Update" else "Install")
                                }
                            },
                            dismissButton = if (isSilent) {
                                {
                                    TextButton(onClick = { updateManager.installPendingUpdate() }) {
                                        Text("Manual Install")
                                    }
                                }
                            } else null
                        )
                    }

```

with nothing (delete it entirely — the badge/Settings section added in later tasks replaces this dialog).

Finally, remove these two now-unused imports:

```kotlin
import com.kkc.sheettracker.ui.timecard.ClockForUpdateOverlay
import com.kkc.sheettracker.update.DeviceOwnerUpdateFallback
```

- [x] **Step 6: Delete the two now-unused files**

```bash
rm "app/src/main/java/com/kkc/sheettracker/update/DeviceOwnerUpdateFallback.kt"
rm "app/src/main/java/com/kkc/sheettracker/ui/timecard/ClockForUpdateOverlay.kt"
```

- [x] **Step 7: Run test to verify it passes**

Run: `cd C:\Scripts\KKCSheetTracker && .\gradlew.bat test --tests "com.kkc.sheettracker.MainActivityUpdateWiringTest"`
Expected: PASS

- [x] **Step 8: Compile the app to catch anything the text-only test missed**

Run: `cd C:\Scripts\KKCSheetTracker && .\gradlew.bat compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [x] **Step 9: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/update/UpdateManager.kt app/src/main/java/com/kkc/sheettracker/MainActivity.kt app/src/test/java/com/kkc/sheettracker/testutil/SourceFiles.kt app/src/test/java/com/kkc/sheettracker/MainActivityUpdateWiringTest.kt
git rm app/src/main/java/com/kkc/sheettracker/update/DeviceOwnerUpdateFallback.kt app/src/main/java/com/kkc/sheettracker/ui/timecard/ClockForUpdateOverlay.kt
git commit -m "feat: remove silent self-update popup path from Sheet Tracker"
```

---

### Task 2: Remove Sheet Tracker's external-update dialog and skip-version logic

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/update/UpdateManager.kt`
- Modify: `app/src/main/java/com/kkc/sheettracker/MainActivity.kt`
- Modify: `app/src/test/java/com/kkc/sheettracker/MainActivityUpdateWiringTest.kt`

- [x] **Step 1: Add the failing test**

Add this method to `app/src/test/java/com/kkc/sheettracker/MainActivityUpdateWiringTest.kt` (inside the existing class, alongside `silentSelfUpdatePathIsRemoved`):

```kotlin
    @Test
    fun externalUpdateSkipLogicIsRemoved() {
        val mainActivitySource = SourceFiles.mainSource("com/kkc/sheettracker/MainActivity.kt").readText()
        val updateManagerSource = SourceFiles.mainSource("com/kkc/sheettracker/update/UpdateManager.kt").readText()

        assertFalse("MainActivity.kt must not render the external-update AlertDialog", mainActivitySource.containsWord("pendingExternalUpdate"))
        assertFalse("UpdateManager.kt must not expose canSkip", updateManagerSource.contains("canSkip"))
        assertFalse("UpdateManager.kt must not expose skipExternalUpdate", updateManagerSource.contains("skipExternalUpdate"))
        assertFalse("UpdateManager.kt must not persist skipped versions", updateManagerSource.contains("skipped_version_"))
    }
```

- [x] **Step 2: Run test to verify it fails**

Run: `cd C:\Scripts\KKCSheetTracker && .\gradlew.bat test --tests "com.kkc.sheettracker.MainActivityUpdateWiringTest"`
Expected: FAIL on `externalUpdateSkipLogicIsRemoved`.

- [x] **Step 3: Remove skip logic and the `canSkip` field from UpdateManager.kt**

Replace the `ExternalAppUpdate` data class (currently lines 27–34):

```kotlin
data class ExternalAppUpdate(
    val packageName: String,
    val appName: String,
    val apkFile: File,
    val versionCode: Long,
    val versionName: String,
    val canSkip: Boolean
)
```

with:

```kotlin
data class ExternalAppUpdate(
    val packageName: String,
    val appName: String,
    val apkFile: File,
    val versionCode: Long,
    val versionName: String
)
```

Remove this field (currently line 60):

```kotlin
    private val skippedExternalPackagesInSession = mutableSetOf<String>()
```

Remove this method (currently lines 119–124):

```kotlin
    fun skipExternalUpdate(update: ExternalAppUpdate) {
        val prefs = activity.getSharedPreferences(PREFS_NAME, Activity.MODE_PRIVATE)
        prefs.edit().putLong("skipped_version_${update.packageName}", update.versionCode).apply()
        skippedExternalPackagesInSession.add(update.packageName)
        pendingExternalUpdates = pendingExternalUpdates.filter { it.packageName != update.packageName }
    }
```

In `computeExternalUpdates`, replace this block (currently lines 304–330):

```kotlin
        val prefs = activity.getSharedPreferences(PREFS_NAME, Activity.MODE_PRIVATE)
        val externalList = mutableListOf<ExternalAppUpdate>()
        for (app in externalApps) {
            val installedVersion = getInstalledVersionCode(app.packageName)
            if (installedVersion == -1L) {
                continue
            }

            if (skippedExternalPackagesInSession.contains(app.packageName)) {
                continue
            }

            val appInfo = newestMap[app.packageName]
            if (appInfo != null && appInfo.versionCode > installedVersion) {
                val persistedSkippedVersion = prefs.getLong("skipped_version_${app.packageName}", -1L)
                val canSkip = appInfo.versionCode != persistedSkippedVersion

                externalList.add(
                    ExternalAppUpdate(
                        packageName = app.packageName,
                        appName = app.appName,
                        apkFile = appInfo.file,
                        versionCode = appInfo.versionCode,
                        versionName = appInfo.versionName,
                        canSkip = canSkip
                    )
                )
            }
        }
        return externalList
```

with:

```kotlin
        val externalList = mutableListOf<ExternalAppUpdate>()
        for (app in externalApps) {
            val installedVersion = getInstalledVersionCode(app.packageName)
            if (installedVersion == -1L) {
                continue
            }

            val appInfo = newestMap[app.packageName]
            if (appInfo != null && appInfo.versionCode > installedVersion) {
                externalList.add(
                    ExternalAppUpdate(
                        packageName = app.packageName,
                        appName = app.appName,
                        apkFile = appInfo.file,
                        versionCode = appInfo.versionCode,
                        versionName = appInfo.versionName
                    )
                )
            }
        }
        return externalList
```

- [x] **Step 4: Remove the external-update dialog from MainActivity.kt**

Delete this block (currently lines 503–530):

```kotlin
                    val pendingExternal = updateManager.pendingExternalUpdate
                    if (pendingExternal != null) {
                        AlertDialog(
                            onDismissRequest = {},
                            title = { Text("Update Available") },
                            text = { Text("A new version of ${pendingExternal.appName} (${pendingExternal.versionName}) is available. Install now?") },
                            confirmButton = {
                                Button(
                                    onClick = {
                                        updateManager.installExternalUpdate(pendingExternal)
                                    }
                                ) {
                                    Text("Install")
                                }
                            },
                            dismissButton = if (pendingExternal.canSkip) {
                                {
                                    TextButton(
                                        onClick = {
                                            updateManager.skipExternalUpdate(pendingExternal)
                                        }
                                    ) {
                                        Text("Skip")
                                    }
                                }
                            } else null
                        )
                    }

```

- [x] **Step 5: Run test to verify it passes**

Run: `cd C:\Scripts\KKCSheetTracker && .\gradlew.bat test --tests "com.kkc.sheettracker.MainActivityUpdateWiringTest"`
Expected: PASS (both test methods)

- [x] **Step 6: Compile**

Run: `cd C:\Scripts\KKCSheetTracker && .\gradlew.bat compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [x] **Step 7: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/update/UpdateManager.kt app/src/main/java/com/kkc/sheettracker/MainActivity.kt app/src/test/java/com/kkc/sheettracker/MainActivityUpdateWiringTest.kt
git commit -m "feat: remove external-update popup and skip-version tracking from Sheet Tracker"
```

---

### Task 3: Add periodic update re-scan while Sheet Tracker is foregrounded

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/MainActivity.kt`
- Modify: `app/src/test/java/com/kkc/sheettracker/MainActivityUpdateWiringTest.kt`

- [x] **Step 1: Add the failing test**

Add this method to `MainActivityUpdateWiringTest`:

```kotlin
    @Test
    fun periodicRescanLoopIsWired() {
        val mainActivitySource = SourceFiles.mainSource("com/kkc/sheettracker/MainActivity.kt").readText()

        assertTrue("MainActivity.kt must define UPDATE_RESCAN_INTERVAL_MS", mainActivitySource.contains("UPDATE_RESCAN_INTERVAL_MS"))
        assertTrue("MainActivity.kt must repeat the update scan on STARTED", mainActivitySource.contains("repeatOnLifecycle(Lifecycle.State.STARTED)"))
    }
```

Add the matching import at the top of the test file:

```kotlin
import org.junit.Assert.assertTrue
```

- [x] **Step 2: Run test to verify it fails**

Run: `cd C:\Scripts\KKCSheetTracker && .\gradlew.bat test --tests "com.kkc.sheettracker.MainActivityUpdateWiringTest"`
Expected: FAIL on `periodicRescanLoopIsWired`.

- [x] **Step 3: Add the interval constant**

In `app/src/main/java/com/kkc/sheettracker/MainActivity.kt`, replace (currently lines 118–121):

```kotlin
    private companion object {
        const val EXTRA_VIEW_ONLY_MODE = "extra_view_only_mode"
        const val SYNCTHING_PROMPT_INTERVAL_MS = 12 * 60 * 60 * 1000L
    }
```

with:

```kotlin
    private companion object {
        const val EXTRA_VIEW_ONLY_MODE = "extra_view_only_mode"
        const val SYNCTHING_PROMPT_INTERVAL_MS = 12 * 60 * 60 * 1000L
        const val UPDATE_RESCAN_INTERVAL_MS = 20 * 60 * 1000L
    }
```

- [x] **Step 4: Add the periodic re-scan loop right after the initial scan**

Replace (the line, now relocated by Task 1's edit, immediately following the `UpdateManager(...).apply { ... }` block):

```kotlin
        updateManager.checkForUpdates(checkSelf = true)
```

with:

```kotlin
        updateManager.checkForUpdates(checkSelf = true)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    delay(UPDATE_RESCAN_INTERVAL_MS)
                    updateManager.checkForUpdates(checkSelf = true)
                }
            }
        }
```

- [x] **Step 5: Add the two new imports**

Add near the existing `androidx.lifecycle.lifecycleScope` import:

```kotlin
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
```

- [x] **Step 6: Run test to verify it passes**

Run: `cd C:\Scripts\KKCSheetTracker && .\gradlew.bat test --tests "com.kkc.sheettracker.MainActivityUpdateWiringTest"`
Expected: PASS (all three test methods)

- [x] **Step 7: Compile**

Run: `cd C:\Scripts\KKCSheetTracker && .\gradlew.bat compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [x] **Step 8: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/MainActivity.kt app/src/test/java/com/kkc/sheettracker/MainActivityUpdateWiringTest.kt
git commit -m "feat: periodically re-scan for updates while Sheet Tracker is foregrounded"
```

---

### Task 4: Add the pending-updates badge dot to the Settings nav icon

**Files:**
- Create: `app/src/test/java/com/kkc/sheettracker/ui/components/AppScaffoldUpdateBadgeWiringTest.kt`
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/components/AppScaffold.kt`

This task only touches `AppScaffold.kt`. Every new parameter gets a `= false` default so nothing else in the codebase needs to change yet — `NavGraph.kt` is wired to pass a real value in Task 6.

- [x] **Step 1: Write the failing test**

Create `app/src/test/java/com/kkc/sheettracker/ui/components/AppScaffoldUpdateBadgeWiringTest.kt`:

```kotlin
package com.kkc.sheettracker.ui.components

import com.kkc.sheettracker.testutil.SourceFiles
import org.junit.Assert.assertTrue
import org.junit.Test

class AppScaffoldUpdateBadgeWiringTest {

    @Test
    fun settingsIconRendersUpdateDot() {
        val source = SourceFiles.mainSource("com/kkc/sheettracker/ui/components/AppScaffold.kt").readText()

        assertTrue("AppBottomNavBar must accept hasPendingUpdates", source.contains("hasPendingUpdates: Boolean = false"))
        assertTrue("MorphingNavIconRow must branch on NavDestination.SETTINGS with an empty dot badge", source.contains("dest == NavDestination.SETTINGS && hasPendingUpdates"))
    }
}
```

- [x] **Step 2: Run test to verify it fails**

Run: `cd C:\Scripts\KKCSheetTracker && .\gradlew.bat test --tests "com.kkc.sheettracker.ui.components.AppScaffoldUpdateBadgeWiringTest"`
Expected: FAIL

- [x] **Step 3: Thread `hasPendingUpdates` through `AppBottomNavBar` → `MorphingNavBar` → `MorphingNavIconRow`**

In `app/src/main/java/com/kkc/sheettracker/ui/components/AppScaffold.kt`, in the `AppBottomNavBar` signature, replace:

```kotlin
    supplyNotificationCount: Int = 0,
    safetyNotificationCount: Int = 0,
    hazeState: HazeState? = null,
```

with:

```kotlin
    supplyNotificationCount: Int = 0,
    safetyNotificationCount: Int = 0,
    hasPendingUpdates: Boolean = false,
    hazeState: HazeState? = null,
```

In `AppBottomNavBar`'s body, in its call to `MorphingNavBar`, replace:

```kotlin
            supplyNotificationCount = supplyNotificationCount,
            safetyNotificationCount = safetyNotificationCount,
            hazeState = hazeState,
```

with:

```kotlin
            supplyNotificationCount = supplyNotificationCount,
            safetyNotificationCount = safetyNotificationCount,
            hasPendingUpdates = hasPendingUpdates,
            hazeState = hazeState,
```

In the `MorphingNavBar` signature, replace:

```kotlin
    supplyNotificationCount: Int,
    safetyNotificationCount: Int = 0,
    hazeState: HazeState? = null,
```

with:

```kotlin
    supplyNotificationCount: Int,
    safetyNotificationCount: Int = 0,
    hasPendingUpdates: Boolean = false,
    hazeState: HazeState? = null,
```

In `MorphingNavBar`'s body, in its call to `MorphingNavIconRow`, replace:

```kotlin
                        supplyNotificationCount = supplyNotificationCount,
                        safetyNotificationCount = safetyNotificationCount,
                        onNavigate              = onNavigate,
```

with:

```kotlin
                        supplyNotificationCount = supplyNotificationCount,
                        safetyNotificationCount = safetyNotificationCount,
                        hasPendingUpdates       = hasPendingUpdates,
                        onNavigate              = onNavigate,
```

In the `MorphingNavIconRow` signature, replace:

```kotlin
    supplyNotificationCount: Int,
    safetyNotificationCount: Int = 0,
    onNavigate: (NavDestination) -> Unit,
```

with:

```kotlin
    supplyNotificationCount: Int,
    safetyNotificationCount: Int = 0,
    hasPendingUpdates: Boolean = false,
    onNavigate: (NavDestination) -> Unit,
```

- [x] **Step 4: Render the dot badge on the Settings icon**

Still in `AppScaffold.kt`, inside `MorphingNavIconRow`, replace:

```kotlin
                        val badgeCount = when (dest) {
                            NavDestination.SUPPLY -> supplyNotificationCount
                            NavDestination.STANDARDS -> safetyNotificationCount
                            else -> 0
                        }
                        if (badgeCount > 0) {
                            BadgedBox(badge = { Badge { Text(badgeCount.toString()) } }) {
                                iconContent()
                            }
                        } else {
                            iconContent()
                        }
```

with:

```kotlin
                        val badgeCount = when (dest) {
                            NavDestination.SUPPLY -> supplyNotificationCount
                            NavDestination.STANDARDS -> safetyNotificationCount
                            else -> 0
                        }
                        val showUpdateDot = dest == NavDestination.SETTINGS && hasPendingUpdates
                        if (showUpdateDot) {
                            BadgedBox(badge = { Badge {} }) {
                                iconContent()
                            }
                        } else if (badgeCount > 0) {
                            BadgedBox(badge = { Badge { Text(badgeCount.toString()) } }) {
                                iconContent()
                            }
                        } else {
                            iconContent()
                        }
```

- [x] **Step 5: Run test to verify it passes**

Run: `cd C:\Scripts\KKCSheetTracker && .\gradlew.bat test --tests "com.kkc.sheettracker.ui.components.AppScaffoldUpdateBadgeWiringTest"`
Expected: PASS

- [x] **Step 6: Compile**

Run: `cd C:\Scripts\KKCSheetTracker && .\gradlew.bat compileDebugKotlin`
Expected: BUILD SUCCESSFUL (all params defaulted, no other call site needs changes yet)

- [x] **Step 7: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/components/AppScaffold.kt app/src/test/java/com/kkc/sheettracker/ui/components/AppScaffoldUpdateBadgeWiringTest.kt
git commit -m "feat: add pending-updates dot badge to the Settings nav icon"
```

---

### Task 5: Add the "Pending Updates" section to SettingsScreen

**Files:**
- Create: `app/src/test/java/com/kkc/sheettracker/ui/settings/SettingsScreenPendingUpdatesWiringTest.kt`
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/settings/SettingsScreen.kt`

This task only touches `SettingsScreen.kt`. New params default to "nothing pending" so no caller needs to change yet — `NavGraph.kt` is wired in Task 7.

- [x] **Step 1: Write the failing test**

Create `app/src/test/java/com/kkc/sheettracker/ui/settings/SettingsScreenPendingUpdatesWiringTest.kt`:

```kotlin
package com.kkc.sheettracker.ui.settings

import com.kkc.sheettracker.testutil.SourceFiles
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsScreenPendingUpdatesWiringTest {

    @Test
    fun pendingUpdatesSectionIsWired() {
        val source = SourceFiles.mainSource("com/kkc/sheettracker/ui/settings/SettingsScreen.kt").readText()

        assertTrue("SettingsScreen must accept pendingSelfUpdate", source.contains("pendingSelfUpdate: File? = null"))
        assertTrue("SettingsScreen must accept pendingExternalUpdates", source.contains("pendingExternalUpdates: List<ExternalAppUpdate> = emptyList()"))
        assertTrue("SettingsScreen must accept onInstallSelfUpdate", source.contains("onInstallSelfUpdate: () -> Unit = {}"))
        assertTrue("SettingsScreen must accept onInstallExternalUpdate", source.contains("onInstallExternalUpdate: (ExternalAppUpdate) -> Unit = {}"))
        assertTrue("SettingsScreen must accept onInstallAll", source.contains("onInstallAll: () -> Unit = {}"))
        assertTrue("SettingsScreen must render a Pending Updates card", source.contains("SettingsCard(title = \"Pending Updates\")"))
        assertTrue("SettingsScreen must render an Update All button", source.contains("Update All"))
    }
}
```

- [x] **Step 2: Run test to verify it fails**

Run: `cd C:\Scripts\KKCSheetTracker && .\gradlew.bat test --tests "com.kkc.sheettracker.ui.settings.SettingsScreenPendingUpdatesWiringTest"`
Expected: FAIL

- [x] **Step 3: Add the new parameters**

In `app/src/main/java/com/kkc/sheettracker/ui/settings/SettingsScreen.kt`, replace:

```kotlin
    uiPreferencesStore: UiPreferencesStore,
    idlePowerSaveStore: IdlePowerSaveStore,
) {
```

with:

```kotlin
    uiPreferencesStore: UiPreferencesStore,
    idlePowerSaveStore: IdlePowerSaveStore,
    pendingSelfUpdate: File? = null,
    pendingExternalUpdates: List<ExternalAppUpdate> = emptyList(),
    onInstallSelfUpdate: () -> Unit = {},
    onInstallExternalUpdate: (ExternalAppUpdate) -> Unit = {},
    onInstallAll: () -> Unit = {},
) {
```

Add the import (alongside the other `com.kkc.sheettracker.data.*` imports near the top of the file):

```kotlin
import com.kkc.sheettracker.update.ExternalAppUpdate
```

- [x] **Step 4: Render the Pending Updates section at the top of the list**

Replace:

```kotlin
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ── Work Mode ────────────────────────────────────────────────
```

with:

```kotlin
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ── Pending Updates ─────────────────────────────────────────
            val hasSelfUpdate = pendingSelfUpdate != null
            val hasExternalUpdates = pendingExternalUpdates.isNotEmpty()
            if (hasSelfUpdate || hasExternalUpdates) {
                SettingsCard(title = "Pending Updates") {
                    if (hasSelfUpdate && hasExternalUpdates) {
                        Button(
                            onClick = onInstallAll,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Update All")
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    if (hasSelfUpdate) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("KKC Sheet Tracker update available")
                            Button(onClick = onInstallSelfUpdate) {
                                Text("Update")
                            }
                        }
                    }
                    pendingExternalUpdates.forEach { update ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("${update.appName} ${update.versionName} available")
                            Button(onClick = { onInstallExternalUpdate(update) }) {
                                Text("Update")
                            }
                        }
                    }
                }
            }

            // ── Work Mode ────────────────────────────────────────────────
```

- [x] **Step 5: Run test to verify it passes**

Run: `cd C:\Scripts\KKCSheetTracker && .\gradlew.bat test --tests "com.kkc.sheettracker.ui.settings.SettingsScreenPendingUpdatesWiringTest"`
Expected: PASS

- [x] **Step 6: Compile**

Run: `cd C:\Scripts\KKCSheetTracker && .\gradlew.bat compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [x] **Step 7: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/settings/SettingsScreen.kt app/src/test/java/com/kkc/sheettracker/ui/settings/SettingsScreenPendingUpdatesWiringTest.kt
git commit -m "feat: add Pending Updates section to Settings screen"
```

---

### Task 6: Thread `hasPendingUpdates` from AppNavigation down to both nav bars

**Files:**
- Create: `app/src/test/java/com/kkc/sheettracker/navigation/UpdateBadgeWiringTest.kt`
- Modify: `app/src/main/java/com/kkc/sheettracker/navigation/NavGraph.kt`

Sheet Tracker has two parallel navigation implementations selected by a feature flag: `MultiBackStackNavigation` and `LegacySingleStackNavigation` (production default per `CLAUDE.md`). Both already forward an existing `onReinstallLatest: () -> Unit` callback the same way from `AppNavigation` down to their own `AppBottomNavBar` call — this task clones that exact pattern for `hasPendingUpdates`.

- [x] **Step 1: Write the failing test**

Create `app/src/test/java/com/kkc/sheettracker/navigation/UpdateBadgeWiringTest.kt`:

```kotlin
package com.kkc.sheettracker.navigation

import com.kkc.sheettracker.testutil.SourceFiles
import org.junit.Assert.assertEquals
import org.junit.Test

class UpdateBadgeWiringTest {

    @Test
    fun hasPendingUpdatesReachesBothAppBottomNavBarCalls() {
        val source = SourceFiles.mainSource("com/kkc/sheettracker/navigation/NavGraph.kt").readText()

        val paramDeclarations = Regex("hasPendingUpdates: Boolean = false").findAll(source).count()
        // AppNavigation, MultiBackStackNavigation, LegacySingleStackNavigation
        assertEquals("hasPendingUpdates must be declared on all three navigation functions", 3, paramDeclarations)

        val hasPendingUpdatesForwards = Regex("hasPendingUpdates = hasPendingUpdates").findAll(source).count()
        // AppNavigation forwards it to both MultiBackStackNavigation and LegacySingleStackNavigation (2),
        // and each of those forwards it again into its own AppBottomNavBar call (2 more).
        assertEquals("hasPendingUpdates must be forwarded at every hop in both chains", 4, hasPendingUpdatesForwards)
    }
}
```

- [x] **Step 2: Run test to verify it fails**

Run: `cd C:\Scripts\KKCSheetTracker && .\gradlew.bat test --tests "com.kkc.sheettracker.navigation.UpdateBadgeWiringTest"`
Expected: FAIL (0 declarations found, 0 forwards found)

- [x] **Step 3: Add the parameter to `AppNavigation` and forward it to both branches**

In `app/src/main/java/com/kkc/sheettracker/navigation/NavGraph.kt`, in the `AppNavigation` signature, replace:

```kotlin
    onReinstallLatest: () -> Unit,
    onBasePathChanged: (String) -> Unit,
    onTabletIdChanged: (String) -> Unit,
    syncthingApiKey: String,
    syncthingStatus: SyncthingStatusUiState,
    onSyncthingApiKeySave: (String) -> Unit,
    onSyncthingCheckNow: () -> Unit,
    onSyncthingStartNow: () -> Unit,
    supplySubscriptionManager: SupplySubscriptionManager,
    themeCatalog: KKCThemeCatalog,
    onThemeFollowSyncedDefaultChanged: (Boolean) -> Unit,
    onThemeOverrideChanged: (String?) -> Unit,
    onThemeCatalogReload: () -> Unit,
    hardwoodsProgressStore: HardwoodsProgressStore? = null,
    specialtyProgressStore: SpecialtyProgressStore? = null
) {
```

with:

```kotlin
    onReinstallLatest: () -> Unit,
    onBasePathChanged: (String) -> Unit,
    onTabletIdChanged: (String) -> Unit,
    syncthingApiKey: String,
    syncthingStatus: SyncthingStatusUiState,
    onSyncthingApiKeySave: (String) -> Unit,
    onSyncthingCheckNow: () -> Unit,
    onSyncthingStartNow: () -> Unit,
    supplySubscriptionManager: SupplySubscriptionManager,
    themeCatalog: KKCThemeCatalog,
    onThemeFollowSyncedDefaultChanged: (Boolean) -> Unit,
    onThemeOverrideChanged: (String?) -> Unit,
    onThemeCatalogReload: () -> Unit,
    hasPendingUpdates: Boolean = false,
    hardwoodsProgressStore: HardwoodsProgressStore? = null,
    specialtyProgressStore: SpecialtyProgressStore? = null
) {
```

In `AppNavigation`'s body, in its call to `MultiBackStackNavigation`, replace:

```kotlin
                onThemeFollowSyncedDefaultChanged = onThemeFollowSyncedDefaultChanged,
                onThemeOverrideChanged = onThemeOverrideChanged,
                onThemeCatalogReload = onThemeCatalogReload,
                liveIndexEngine = liveIndexEngine
            )
        } else {
```

with:

```kotlin
                onThemeFollowSyncedDefaultChanged = onThemeFollowSyncedDefaultChanged,
                onThemeOverrideChanged = onThemeOverrideChanged,
                onThemeCatalogReload = onThemeCatalogReload,
                hasPendingUpdates = hasPendingUpdates,
                liveIndexEngine = liveIndexEngine
            )
        } else {
```

And in its call to `LegacySingleStackNavigation`, replace:

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

with:

```kotlin
                onThemeFollowSyncedDefaultChanged = onThemeFollowSyncedDefaultChanged,
                onThemeOverrideChanged = onThemeOverrideChanged,
                onThemeCatalogReload = onThemeCatalogReload,
                hasPendingUpdates = hasPendingUpdates,
                unifiedEngine = liveIndexEngine
            )
        }
    }
}
```

- [x] **Step 4: Add the parameter to `MultiBackStackNavigation` and forward it into its `AppBottomNavBar` call**

In the `MultiBackStackNavigation` signature, replace:

```kotlin
    onThemeFollowSyncedDefaultChanged: (Boolean) -> Unit,
    onThemeOverrideChanged: (String?) -> Unit,
    onThemeCatalogReload: () -> Unit,
    liveIndexEngine: UnifiedMetadataEngine
) {
```

with:

```kotlin
    onThemeFollowSyncedDefaultChanged: (Boolean) -> Unit,
    onThemeOverrideChanged: (String?) -> Unit,
    onThemeCatalogReload: () -> Unit,
    hasPendingUpdates: Boolean = false,
    liveIndexEngine: UnifiedMetadataEngine
) {
```

In `MultiBackStackNavigation`'s body, in its call to `AppBottomNavBar`, replace:

```kotlin
                supplyNotificationCount = supplyNotificationCount,
                safetyNotificationCount = safetyNotificationCount,
                searchDecoration = navBarDeco.searchDecoration,
                cncDecoration = navBarDeco.cncDecoration,
                specialtyDecoration = navBarDeco.specialtyDecoration,
                penDecoration = navBarDeco.penDecoration,
                extendedControls = navBarDeco.extendedControls,
                onNavigate = { dest ->
                    if (dest == NavDestination.HOURS) {
                        launchTimecardApp(context, employeeName.takeIf { it.isNotBlank() })
                    } else {
```

with:

```kotlin
                supplyNotificationCount = supplyNotificationCount,
                safetyNotificationCount = safetyNotificationCount,
                hasPendingUpdates = hasPendingUpdates,
                searchDecoration = navBarDeco.searchDecoration,
                cncDecoration = navBarDeco.cncDecoration,
                specialtyDecoration = navBarDeco.specialtyDecoration,
                penDecoration = navBarDeco.penDecoration,
                extendedControls = navBarDeco.extendedControls,
                onNavigate = { dest ->
                    if (dest == NavDestination.HOURS) {
                        launchTimecardApp(context, employeeName.takeIf { it.isNotBlank() })
                    } else {
```

- [x] **Step 5: Add the parameter to `LegacySingleStackNavigation` and forward it into its `AppBottomNavBar` call**

In the `LegacySingleStackNavigation` signature, replace:

```kotlin
    onThemeFollowSyncedDefaultChanged: (Boolean) -> Unit,
    onThemeOverrideChanged: (String?) -> Unit,
    onThemeCatalogReload: () -> Unit,
    unifiedEngine: UnifiedMetadataEngine
) {
```

with:

```kotlin
    onThemeFollowSyncedDefaultChanged: (Boolean) -> Unit,
    onThemeOverrideChanged: (String?) -> Unit,
    onThemeCatalogReload: () -> Unit,
    hasPendingUpdates: Boolean = false,
    unifiedEngine: UnifiedMetadataEngine
) {
```

In `LegacySingleStackNavigation`'s body, in its call to `AppBottomNavBar`, replace:

```kotlin
                supplyNotificationCount = supplyNotificationCount,
                safetyNotificationCount = safetyNotificationCount,
                searchDecoration = navBarDeco.searchDecoration,
                cncDecoration = navBarDeco.cncDecoration,
                specialtyDecoration = navBarDeco.specialtyDecoration,
                penDecoration = navBarDeco.penDecoration,
                extendedControls = navBarDeco.extendedControls,
                onNavigate = { dest ->
                    if (dest == NavDestination.HOURS) {
                        launchTimecardApp(legacyContext, employeeName.takeIf { it.isNotBlank() })
                        return@AppBottomNavBar
                    }
```

with:

```kotlin
                supplyNotificationCount = supplyNotificationCount,
                safetyNotificationCount = safetyNotificationCount,
                hasPendingUpdates = hasPendingUpdates,
                searchDecoration = navBarDeco.searchDecoration,
                cncDecoration = navBarDeco.cncDecoration,
                specialtyDecoration = navBarDeco.specialtyDecoration,
                penDecoration = navBarDeco.penDecoration,
                extendedControls = navBarDeco.extendedControls,
                onNavigate = { dest ->
                    if (dest == NavDestination.HOURS) {
                        launchTimecardApp(legacyContext, employeeName.takeIf { it.isNotBlank() })
                        return@AppBottomNavBar
                    }
```

- [x] **Step 6: Run test to verify it passes**

Run: `cd C:\Scripts\KKCSheetTracker && .\gradlew.bat test --tests "com.kkc.sheettracker.navigation.UpdateBadgeWiringTest"`
Expected: PASS

- [x] **Step 7: Compile**

Run: `cd C:\Scripts\KKCSheetTracker && .\gradlew.bat compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [x] **Step 8: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/navigation/NavGraph.kt app/src/test/java/com/kkc/sheettracker/navigation/UpdateBadgeWiringTest.kt
git commit -m "feat: thread hasPendingUpdates through both navigation stacks"
```

---

### Task 7: Thread pending-update state and install callbacks to both SettingsScreen call sites

**Files:**
- Create: `app/src/test/java/com/kkc/sheettracker/navigation/PendingUpdatesSettingsWiringTest.kt`
- Modify: `app/src/main/java/com/kkc/sheettracker/navigation/NavGraph.kt`

Chain A: `AppNavigation → MultiBackStackNavigation → SettingsTabHost → SettingsScreen`.
Chain B: `AppNavigation → LegacySingleStackNavigation → SettingsScreen` (no intermediate host).

- [x] **Step 1: Write the failing test**

Create `app/src/test/java/com/kkc/sheettracker/navigation/PendingUpdatesSettingsWiringTest.kt`:

```kotlin
package com.kkc.sheettracker.navigation

import com.kkc.sheettracker.testutil.SourceFiles
import org.junit.Assert.assertEquals
import org.junit.Test

class PendingUpdatesSettingsWiringTest {

    @Test
    fun pendingUpdateParamsReachBothSettingsScreenCalls() {
        val source = SourceFiles.mainSource("com/kkc/sheettracker/navigation/NavGraph.kt").readText()

        val settingsScreenCallSites = Regex("SettingsScreen\\(").findAll(source).count()
        assertEquals("Expected exactly two SettingsScreen call sites", 2, settingsScreenCallSites)

        val onInstallAllForwards = Regex("onInstallAll = onInstallAll").findAll(source).count()
        // Chain A has 3 hops: AppNavigation -> MultiBackStackNavigation -> SettingsTabHost -> SettingsScreen.
        // Chain B has 2 hops: AppNavigation -> LegacySingleStackNavigation -> SettingsScreen.
        // Total forwarding call sites across both chains: 3 + 2 = 5.
        assertEquals("onInstallAll must be forwarded at every hop in both chains", 5, onInstallAllForwards)

        val pendingSelfUpdateDeclarations = Regex("pendingSelfUpdate: File\\? = null").findAll(source).count()
        // AppNavigation, MultiBackStackNavigation, SettingsTabHost, LegacySingleStackNavigation
        assertEquals("pendingSelfUpdate must be declared on all four functions in both chains", 4, pendingSelfUpdateDeclarations)
    }
}
```

- [x] **Step 2: Run test to verify it fails**

Run: `cd C:\Scripts\KKCSheetTracker && .\gradlew.bat test --tests "com.kkc.sheettracker.navigation.PendingUpdatesSettingsWiringTest"`
Expected: FAIL

- [x] **Step 3: Add an import and the new parameters + forwarding to `AppNavigation`**

Add near the top of `NavGraph.kt`, alongside the other `com.kkc.sheettracker.*` imports:

```kotlin
import com.kkc.sheettracker.update.ExternalAppUpdate
import java.io.File
```

(If `java.io.File` is already imported in this file, skip re-adding it — check first.)

In the `AppNavigation` signature, replace:

```kotlin
    onThemeCatalogReload: () -> Unit,
    hasPendingUpdates: Boolean = false,
    hardwoodsProgressStore: HardwoodsProgressStore? = null,
    specialtyProgressStore: SpecialtyProgressStore? = null
) {
```

with:

```kotlin
    onThemeCatalogReload: () -> Unit,
    hasPendingUpdates: Boolean = false,
    pendingSelfUpdate: File? = null,
    pendingExternalUpdates: List<ExternalAppUpdate> = emptyList(),
    onInstallSelfUpdate: () -> Unit = {},
    onInstallExternalUpdate: (ExternalAppUpdate) -> Unit = {},
    onInstallAll: () -> Unit = {},
    hardwoodsProgressStore: HardwoodsProgressStore? = null,
    specialtyProgressStore: SpecialtyProgressStore? = null
) {
```

In `AppNavigation`'s call to `MultiBackStackNavigation`, replace:

```kotlin
                hasPendingUpdates = hasPendingUpdates,
                liveIndexEngine = liveIndexEngine
            )
        } else {
```

with:

```kotlin
                hasPendingUpdates = hasPendingUpdates,
                pendingSelfUpdate = pendingSelfUpdate,
                pendingExternalUpdates = pendingExternalUpdates,
                onInstallSelfUpdate = onInstallSelfUpdate,
                onInstallExternalUpdate = onInstallExternalUpdate,
                onInstallAll = onInstallAll,
                liveIndexEngine = liveIndexEngine
            )
        } else {
```

In `AppNavigation`'s call to `LegacySingleStackNavigation`, replace:

```kotlin
                hasPendingUpdates = hasPendingUpdates,
                unifiedEngine = liveIndexEngine
            )
        }
    }
}
```

with:

```kotlin
                hasPendingUpdates = hasPendingUpdates,
                pendingSelfUpdate = pendingSelfUpdate,
                pendingExternalUpdates = pendingExternalUpdates,
                onInstallSelfUpdate = onInstallSelfUpdate,
                onInstallExternalUpdate = onInstallExternalUpdate,
                onInstallAll = onInstallAll,
                unifiedEngine = liveIndexEngine
            )
        }
    }
}
```

- [x] **Step 4: Thread through `MultiBackStackNavigation` into its `SettingsTabHost` call**

In the `MultiBackStackNavigation` signature, replace:

```kotlin
    hasPendingUpdates: Boolean = false,
    liveIndexEngine: UnifiedMetadataEngine
) {
```

with:

```kotlin
    hasPendingUpdates: Boolean = false,
    pendingSelfUpdate: File? = null,
    pendingExternalUpdates: List<ExternalAppUpdate> = emptyList(),
    onInstallSelfUpdate: () -> Unit = {},
    onInstallExternalUpdate: (ExternalAppUpdate) -> Unit = {},
    onInstallAll: () -> Unit = {},
    liveIndexEngine: UnifiedMetadataEngine
) {
```

In `MultiBackStackNavigation`'s body, in its call to `SettingsTabHost`, replace:

```kotlin
                        onThemeFollowSyncedDefaultChanged = onThemeFollowSyncedDefaultChanged,
                        onThemeOverrideChanged = onThemeOverrideChanged,
                        onThemeCatalogReload = onThemeCatalogReload
                    )
                }
```

with:

```kotlin
                        onThemeFollowSyncedDefaultChanged = onThemeFollowSyncedDefaultChanged,
                        onThemeOverrideChanged = onThemeOverrideChanged,
                        onThemeCatalogReload = onThemeCatalogReload,
                        pendingSelfUpdate = pendingSelfUpdate,
                        pendingExternalUpdates = pendingExternalUpdates,
                        onInstallSelfUpdate = onInstallSelfUpdate,
                        onInstallExternalUpdate = onInstallExternalUpdate,
                        onInstallAll = onInstallAll
                    )
                }
```

- [x] **Step 5: Thread through `SettingsTabHost` into its `SettingsScreen` call**

In the `SettingsTabHost` signature, replace:

```kotlin
    themeCatalog: KKCThemeCatalog,
    onThemeFollowSyncedDefaultChanged: (Boolean) -> Unit,
    onThemeOverrideChanged: (String?) -> Unit,
    onThemeCatalogReload: () -> Unit
) {
```

with:

```kotlin
    themeCatalog: KKCThemeCatalog,
    onThemeFollowSyncedDefaultChanged: (Boolean) -> Unit,
    onThemeOverrideChanged: (String?) -> Unit,
    onThemeCatalogReload: () -> Unit,
    pendingSelfUpdate: File? = null,
    pendingExternalUpdates: List<ExternalAppUpdate> = emptyList(),
    onInstallSelfUpdate: () -> Unit = {},
    onInstallExternalUpdate: (ExternalAppUpdate) -> Unit = {},
    onInstallAll: () -> Unit = {}
) {
```

In `SettingsTabHost`'s call to `SettingsScreen`, replace:

```kotlin
                onThemeFollowSyncedDefaultChanged = onThemeFollowSyncedDefaultChanged,
                onThemeOverrideChanged = onThemeOverrideChanged,
                onThemeCatalogReload = onThemeCatalogReload,
                onOpenAssemblyViewerDefaults = {
```

with:

```kotlin
                onThemeFollowSyncedDefaultChanged = onThemeFollowSyncedDefaultChanged,
                onThemeOverrideChanged = onThemeOverrideChanged,
                onThemeCatalogReload = onThemeCatalogReload,
                pendingSelfUpdate = pendingSelfUpdate,
                pendingExternalUpdates = pendingExternalUpdates,
                onInstallSelfUpdate = onInstallSelfUpdate,
                onInstallExternalUpdate = onInstallExternalUpdate,
                onInstallAll = onInstallAll,
                onOpenAssemblyViewerDefaults = {
```

- [x] **Step 6: Thread through `LegacySingleStackNavigation` directly into its `SettingsScreen` call**

In the `LegacySingleStackNavigation` signature, replace:

```kotlin
    hasPendingUpdates: Boolean = false,
    unifiedEngine: UnifiedMetadataEngine
) {
```

with:

```kotlin
    hasPendingUpdates: Boolean = false,
    pendingSelfUpdate: File? = null,
    pendingExternalUpdates: List<ExternalAppUpdate> = emptyList(),
    onInstallSelfUpdate: () -> Unit = {},
    onInstallExternalUpdate: (ExternalAppUpdate) -> Unit = {},
    onInstallAll: () -> Unit = {},
    unifiedEngine: UnifiedMetadataEngine
) {
```

In `LegacySingleStackNavigation`'s call to `SettingsScreen`, replace:

```kotlin
                        onThemeFollowSyncedDefaultChanged = onThemeFollowSyncedDefaultChanged,
                        onThemeOverrideChanged = onThemeOverrideChanged,
                        onThemeCatalogReload = onThemeCatalogReload,
                        onOpenAssemblyViewerDefaults = {
```

with:

```kotlin
                        onThemeFollowSyncedDefaultChanged = onThemeFollowSyncedDefaultChanged,
                        onThemeOverrideChanged = onThemeOverrideChanged,
                        onThemeCatalogReload = onThemeCatalogReload,
                        pendingSelfUpdate = pendingSelfUpdate,
                        pendingExternalUpdates = pendingExternalUpdates,
                        onInstallSelfUpdate = onInstallSelfUpdate,
                        onInstallExternalUpdate = onInstallExternalUpdate,
                        onInstallAll = onInstallAll,
                        onOpenAssemblyViewerDefaults = {
```

- [x] **Step 7: Run test to verify it passes**

Run: `cd C:\Scripts\KKCSheetTracker && .\gradlew.bat test --tests "com.kkc.sheettracker.navigation.PendingUpdatesSettingsWiringTest"`
Expected: PASS

- [x] **Step 8: Compile**

Run: `cd C:\Scripts\KKCSheetTracker && .\gradlew.bat compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [x] **Step 9: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/navigation/NavGraph.kt app/src/test/java/com/kkc/sheettracker/navigation/PendingUpdatesSettingsWiringTest.kt
git commit -m "feat: thread pending-update state and install actions into both Settings call sites"
```

---

### Task 8: Wire MainActivity's real update state and callbacks into AppNavigation

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/MainActivity.kt`
- Modify: `app/src/test/java/com/kkc/sheettracker/MainActivityUpdateWiringTest.kt`

This is the task that actually turns the badge and Settings section on — everything before this compiled but did nothing observable, because every new param defaulted to "nothing pending."

- [x] **Step 1: Add the failing test**

Add this method to `MainActivityUpdateWiringTest`:

```kotlin
    @Test
    fun realUpdateStateIsWiredIntoAppNavigation() {
        val mainActivitySource = SourceFiles.mainSource("com/kkc/sheettracker/MainActivity.kt").readText()

        assertTrue("MainActivity.kt must pass hasPendingUpdates into AppNavigation", mainActivitySource.contains("hasPendingUpdates = updateManager.pendingUpdateApk != null || updateManager.pendingExternalUpdates.isNotEmpty()"))
        assertTrue("MainActivity.kt must pass pendingSelfUpdate into AppNavigation", mainActivitySource.contains("pendingSelfUpdate = updateManager.pendingUpdateApk"))
        assertTrue("MainActivity.kt must pass pendingExternalUpdates into AppNavigation", mainActivitySource.contains("pendingExternalUpdates = updateManager.pendingExternalUpdates"))
        assertTrue("MainActivity.kt must wire onInstallAll to install Hours Tracker before itself", mainActivitySource.contains("onInstallAll = {"))
    }
```

- [x] **Step 2: Run test to verify it fails**

Run: `cd C:\Scripts\KKCSheetTracker && .\gradlew.bat test --tests "com.kkc.sheettracker.MainActivityUpdateWiringTest"`
Expected: FAIL on `realUpdateStateIsWiredIntoAppNavigation`.

- [x] **Step 3: Pass the real update state and callbacks into the `AppNavigation` call**

In `app/src/main/java/com/kkc/sheettracker/MainActivity.kt`, inside the `setContent { ... }` block, find the `AppNavigation(...)` call and, immediately after its `onReinstallLatest = { updateManager.reinstallLatest() },` line, insert:

```kotlin
                        hasPendingUpdates = updateManager.pendingUpdateApk != null || updateManager.pendingExternalUpdates.isNotEmpty(),
                        pendingSelfUpdate = updateManager.pendingUpdateApk,
                        pendingExternalUpdates = updateManager.pendingExternalUpdates,
                        onInstallSelfUpdate = { updateManager.installPendingUpdate() },
                        onInstallExternalUpdate = { update -> updateManager.installExternalUpdate(update) },
                        onInstallAll = {
                            // Hours Tracker must install first: installing Sheet Tracker over itself
                            // kills this process, so anything queued after that point won't fire.
                            updateManager.pendingExternalUpdates.firstOrNull()?.let {
                                updateManager.installExternalUpdate(it)
                            }
                            updateManager.installPendingUpdate()
                        },
```

- [x] **Step 4: Run test to verify it passes**

Run: `cd C:\Scripts\KKCSheetTracker && .\gradlew.bat test --tests "com.kkc.sheettracker.MainActivityUpdateWiringTest"`
Expected: PASS (all four test methods)

- [x] **Step 5: Compile**

Run: `cd C:\Scripts\KKCSheetTracker && .\gradlew.bat compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [x] **Step 6: Run the full Sheet Tracker unit test suite**

Run: `cd C:\Scripts\KKCSheetTracker && .\gradlew.bat test`
Expected: BUILD SUCCESSFUL (all tests pass, including the pre-existing `UpdateScanPolicyTest` and `LegacyStandardsTransitionWiringTest`)

- [x] **Step 7: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/MainActivity.kt app/src/test/java/com/kkc/sheettracker/MainActivityUpdateWiringTest.kt
git commit -m "feat: wire real pending-update state into AppNavigation"
```

- [ ] **Step 8: Manual verification on a tablet or emulator (not automatable)**

Deploy the release build (`.\adb-install-release.ps1`, per `CLAUDE.md`) or a debug build to a device with a `.Testing_Updates`/`.Updates` folder containing a newer Sheet Tracker APK, a newer Hours Tracker APK, or both:
- No dialog should pop on launch.
- The Settings nav icon should show a small dot.
- Opening Settings should show the "Pending Updates" card at the top with the right rows and buttons.
- Tapping a single app's "Update" button should launch the system install prompt for just that APK.
- With both pending, "Update All" should launch the Hours Tracker install prompt, then the Sheet Tracker one.

---

### Task 9: Remove Hours Tracker's own update scan/dialog trigger

**Files:**
- Create: `C:\Scripts\Hours Tracker\AndroidApp\app\src\test\java\com\example\timecard\testutil\SourceFiles.kt`
- Create: `C:\Scripts\Hours Tracker\AndroidApp\app\src\test\java\com\example\timecard\MainActivityUpdateCleanupTest.kt`
- Modify: `C:\Scripts\Hours Tracker\AndroidApp\app\src\main\java\com\example\timecard\MainActivity.kt`

This is in the **Hours Tracker** repository (`C:\Scripts\Hours Tracker\AndroidApp`), not KKCSheetTracker. `UpdateManager` and `reinstallLatest()` stay as a manual fallback — only the auto-scan-and-popup trigger is removed.

- [x] **Step 1: Add the test helper**

Create `C:\Scripts\Hours Tracker\AndroidApp\app\src\test\java\com\example\timecard\testutil\SourceFiles.kt`:

```kotlin
package com.example.timecard.testutil

import java.io.File

/** Locates a file under `app/src/main/java/` by its path relative to that root, walking up from the working directory. */
object SourceFiles {
    fun mainSource(relativePath: String): File {
        var dir = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(6) {
            val candidate = File(dir, "app/src/main/java/$relativePath")
            if (candidate.exists()) return candidate
            val direct = File(dir, "src/main/java/$relativePath")
            if (direct.exists()) return direct
            dir = dir.parentFile ?: return@repeat
        }
        error("Unable to locate $relativePath from ${System.getProperty("user.dir")}")
    }
}
```

- [x] **Step 2: Write the failing test**

Create `C:\Scripts\Hours Tracker\AndroidApp\app\src\test\java\com\example\timecard\MainActivityUpdateCleanupTest.kt`:

```kotlin
package com.example.timecard

import com.example.timecard.testutil.SourceFiles
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityUpdateCleanupTest {

    @Test
    fun selfUpdateScanAndDialogWiringIsRemoved() {
        val source = SourceFiles.mainSource("com/example/timecard/MainActivity.kt").readText()

        assertFalse("MainActivity.kt must not call updateManager.checkForUpdates()", source.contains("updateManager.checkForUpdates()"))
        assertFalse("MainActivity.kt must not pass pendingUpdate to TimecardApp", source.contains("pendingUpdate = updateManager.pendingUpdateApk"))
        assertFalse("MainActivity.kt must not pass onInstallUpdate to TimecardApp", source.contains("onInstallUpdate = { updateManager.installPendingUpdate() }"))
        assertTrue("MainActivity.kt must still wire onReinstallLatest as a manual fallback", source.contains("onReinstallLatest = { updateManager.reinstallLatest() }"))
    }
}
```

- [x] **Step 3: Run test to verify it fails**

Run: `cd "C:\Scripts\Hours Tracker\AndroidApp" && .\gradlew.bat test --tests "com.example.timecard.MainActivityUpdateCleanupTest"`
Expected: FAIL (the scan call and dialog params are still present)

- [x] **Step 4: Remove the scan call and dialog wiring**

In `C:\Scripts\Hours Tracker\AndroidApp\app\src\main\java\com\example\timecard\MainActivity.kt`, replace:

```kotlin
        updateManager = UpdateManager(this)
        themeState = ThemeState.create(this)

        updateManager.checkForUpdates()

```

with:

```kotlin
        updateManager = UpdateManager(this)
        themeState = ThemeState.create(this)

```

Then replace:

```kotlin
                onReinstallLatest = { updateManager.reinstallLatest() },
                pendingUpdate = updateManager.pendingUpdateApk,
                onInstallUpdate = { updateManager.installPendingUpdate() }
            )
```

with:

```kotlin
                onReinstallLatest = { updateManager.reinstallLatest() }
            )
```

- [x] **Step 5: Run test to verify it passes**

Run: `cd "C:\Scripts\Hours Tracker\AndroidApp" && .\gradlew.bat test --tests "com.example.timecard.MainActivityUpdateCleanupTest"`
Expected: PASS

- [x] **Step 6: Compile**

Run: `cd "C:\Scripts\Hours Tracker\AndroidApp" && .\gradlew.bat compileDebugKotlin`
Expected: This will fail until Task 10 removes the now-mismatched `pendingUpdate`/`onInstallUpdate` parameters from `TimecardApp` — both params have defaults (`= null`, `= {}`), so a call site that simply omits them compiles fine. Confirm it succeeds; if it doesn't, double check Step 4's replacement matched exactly (no stray trailing comma).

- [x] **Step 7: Commit**

```bash
cd "C:\Scripts\Hours Tracker\AndroidApp"
git add app/src/main/java/com/example/timecard/MainActivity.kt app/src/test/java/com/example/timecard/testutil/SourceFiles.kt app/src/test/java/com/example/timecard/MainActivityUpdateCleanupTest.kt
git commit -m "feat: remove Hours Tracker's own update scan and dialog trigger"
```

---

### Task 10: Remove the now-dead update dialog from TimecardApp

**Files:**
- Create: `C:\Scripts\Hours Tracker\AndroidApp\app\src\test\java\com\example\timecard\TimecardAppUpdateParamsRemovedTest.kt`
- Modify: `C:\Scripts\Hours Tracker\AndroidApp\app\src\main\java\com\example\timecard\TimecardApp.kt`

- [x] **Step 1: Write the failing test**

Create `C:\Scripts\Hours Tracker\AndroidApp\app\src\test\java\com\example\timecard\TimecardAppUpdateParamsRemovedTest.kt`:

```kotlin
package com.example.timecard

import com.example.timecard.testutil.SourceFiles
import org.junit.Assert.assertFalse
import org.junit.Test

class TimecardAppUpdateParamsRemovedTest {

    @Test
    fun deadUpdateDialogParamsAreRemoved() {
        val source = SourceFiles.mainSource("com/example/timecard/TimecardApp.kt").readText()

        assertFalse("TimecardApp must not accept pendingUpdate", source.contains("pendingUpdate: java.io.File?"))
        assertFalse("TimecardApp must not accept onInstallUpdate", source.contains("onInstallUpdate: () -> Unit"))
        assertFalse("TimecardApp must not render the update AlertDialog", source.contains("Update dialog — shown when a newer APK is found"))
    }
}
```

- [x] **Step 2: Run test to verify it fails**

Run: `cd "C:\Scripts\Hours Tracker\AndroidApp" && .\gradlew.bat test --tests "com.example.timecard.TimecardAppUpdateParamsRemovedTest"`
Expected: FAIL

- [x] **Step 3: Remove the dead parameters**

In `C:\Scripts\Hours Tracker\AndroidApp\app\src\main\java\com\example\timecard\TimecardApp.kt`, replace:

```kotlin
    launchedByKkc: Boolean = false,
    onReinstallLatest: () -> Unit,
    pendingUpdate: java.io.File? = null,
    onInstallUpdate: () -> Unit = {}
) {
```

with:

```kotlin
    launchedByKkc: Boolean = false,
    onReinstallLatest: () -> Unit
) {
```

- [x] **Step 4: Remove the dead dialog block**

Replace:

```kotlin
        // Update dialog — shown when a newer APK is found on the network share
        if (pendingUpdate != null) {
            AlertDialog(
                onDismissRequest = {},
                title = { Text("Update Available") },
                text = { Text("A new version of the app is available. Install now?") },
                confirmButton = {
                    Button(
                        onClick = onInstallUpdate,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colors.accent
                        )
                    ) {
                        Text("Install")
                    }
                },
                containerColor = colors.surface,
                titleContentColor = colors.textHeading,
                textContentColor = colors.textPrimary
            )
        }
    }
```

with:

```kotlin
    }
```

- [x] **Step 5: Run test to verify it passes**

Run: `cd "C:\Scripts\Hours Tracker\AndroidApp" && .\gradlew.bat test --tests "com.example.timecard.TimecardAppUpdateParamsRemovedTest"`
Expected: PASS

- [x] **Step 6: Run the full Hours Tracker unit test suite and compile**

Run: `cd "C:\Scripts\Hours Tracker\AndroidApp" && .\gradlew.bat test`
Expected: BUILD SUCCESSFUL (all tests pass, including the pre-existing `GamificationEngineTest`, `ProfileViewModelTest`, etc.)

Run: `cd "C:\Scripts\Hours Tracker\AndroidApp" && .\gradlew.bat compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [x] **Step 7: Commit**

```bash
cd "C:\Scripts\Hours Tracker\AndroidApp"
git add app/src/main/java/com/example/timecard/TimecardApp.kt app/src/test/java/com/example/timecard/TimecardAppUpdateParamsRemovedTest.kt
git commit -m "feat: remove dead update-dialog code from TimecardApp"
```

---

## Manual end-to-end verification (after Task 10)

Repeat Task 8 Step 8's manual check with both apps freshly rebuilt and deployed, and additionally confirm:
- Hours Tracker no longer shows any update dialog of its own when a newer Hours Tracker APK is dropped in the shared folder — only Sheet Tracker's badge/Settings section reacts to it.
- Hours Tracker's own admin/settings screen (wherever `onReinstallLatest` is exposed there) still works as a manual fallback.
