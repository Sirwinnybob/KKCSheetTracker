# First-Open Onboarding Flow Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the two independent, unaware-of-each-other permission-request code paths in `MainActivity`/`UpdateManager` with a single sequential controller, so a fresh tablet install asks for each permission exactly once, the install-unknown-apps grant takes effect immediately (no restart), and the Syncthing key prompt no longer stacks on top of permission dialogs.

**Architecture:** A new pure, unit-testable `PermissionFlowController` (in a new `onboarding` package) decides the next ungranted step from a `PermissionSnapshot`. `MainActivity` owns the actual permission-state reads and Settings-intent launches (via `ActivityResultContracts`, so returning from Settings re-checks and advances automatically), and gates all file-system/update/migration logic behind the controller reporting completion. A new `OnboardingGate` composable renders the one-dialog-at-a-time UI. `UpdateManager` stops independently re-checking storage permission and gets a callback hook so its own reactive install-permission request auto-resumes the install on return from Settings, instead of going dead until a restart.

**Tech Stack:** Kotlin, Jetpack Compose, `androidx.activity.result.contract.ActivityResultContracts`, JUnit4 (existing test setup, no Robolectric).

**Prerequisite:** Removal of the device-owner silent-update path (`DeviceOwnerUpdateFallback`) is a separate change that lands first. This plan assumes it's already done — all updates go through the single legacy `installApk()` path. See `docs/superpowers/specs/2026-07-20-first-open-onboarding-design.md`.

---

### Task 1: PermissionFlowController (pure decision logic)

**Files:**
- Create: `app/src/main/java/com/kkc/sheettracker/onboarding/PermissionFlowController.kt`
- Test: `app/src/test/java/com/kkc/sheettracker/onboarding/PermissionFlowControllerTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.kkc.sheettracker.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PermissionFlowControllerTest {

    @Test
    fun `fresh device on Android 13 needs notifications first`() {
        val snapshot = PermissionSnapshot(
            sdkInt = 33,
            notificationsGranted = false,
            storageGranted = false,
            installUnknownAppsGranted = false
        )
        assertEquals(OnboardingStep.NOTIFICATIONS, PermissionFlowController.nextStep(snapshot))
    }

    @Test
    fun `notifications granted then storage access is next`() {
        val snapshot = PermissionSnapshot(
            sdkInt = 33,
            notificationsGranted = true,
            storageGranted = false,
            installUnknownAppsGranted = false
        )
        assertEquals(OnboardingStep.STORAGE_ACCESS, PermissionFlowController.nextStep(snapshot))
    }

    @Test
    fun `notifications and storage granted then install permission is next`() {
        val snapshot = PermissionSnapshot(
            sdkInt = 33,
            notificationsGranted = true,
            storageGranted = true,
            installUnknownAppsGranted = false
        )
        assertEquals(OnboardingStep.INSTALL_UNKNOWN_APPS, PermissionFlowController.nextStep(snapshot))
    }

    @Test
    fun `all three granted means onboarding is complete`() {
        val snapshot = PermissionSnapshot(
            sdkInt = 33,
            notificationsGranted = true,
            storageGranted = true,
            installUnknownAppsGranted = true
        )
        assertNull(PermissionFlowController.nextStep(snapshot))
    }

    @Test
    fun `pre-Android 13 devices skip the notifications step`() {
        val snapshot = PermissionSnapshot(
            sdkInt = 29,
            notificationsGranted = false,
            storageGranted = false,
            installUnknownAppsGranted = false
        )
        assertEquals(OnboardingStep.STORAGE_ACCESS, PermissionFlowController.nextStep(snapshot))
    }

    @Test
    fun `pre-Android 11 devices skip both notifications and storage steps`() {
        val snapshot = PermissionSnapshot(
            sdkInt = 28,
            notificationsGranted = false,
            storageGranted = false,
            installUnknownAppsGranted = false
        )
        assertEquals(OnboardingStep.INSTALL_UNKNOWN_APPS, PermissionFlowController.nextStep(snapshot))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail (class doesn't exist yet)**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.kkc.sheettracker.onboarding.PermissionFlowControllerTest"`
Expected: FAIL — compile error, `PermissionFlowController`/`OnboardingStep`/`PermissionSnapshot` unresolved.

- [ ] **Step 3: Write the implementation**

```kotlin
package com.kkc.sheettracker.onboarding

import android.os.Build

enum class OnboardingStep {
    NOTIFICATIONS,
    STORAGE_ACCESS,
    INSTALL_UNKNOWN_APPS
}

data class PermissionSnapshot(
    val sdkInt: Int,
    val notificationsGranted: Boolean,
    val storageGranted: Boolean,
    val installUnknownAppsGranted: Boolean
)

object PermissionFlowController {
    fun nextStep(snapshot: PermissionSnapshot): OnboardingStep? {
        if (snapshot.sdkInt >= Build.VERSION_CODES.TIRAMISU && !snapshot.notificationsGranted) {
            return OnboardingStep.NOTIFICATIONS
        }
        if (snapshot.sdkInt >= Build.VERSION_CODES.R && !snapshot.storageGranted) {
            return OnboardingStep.STORAGE_ACCESS
        }
        if (!snapshot.installUnknownAppsGranted) {
            return OnboardingStep.INSTALL_UNKNOWN_APPS
        }
        return null
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.kkc.sheettracker.onboarding.PermissionFlowControllerTest"`
Expected: PASS, 6 tests green.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/onboarding/PermissionFlowController.kt app/src/test/java/com/kkc/sheettracker/onboarding/PermissionFlowControllerTest.kt
git commit -m "feat(onboarding): add PermissionFlowController step-sequencing logic"
```

---

### Task 2: OnboardingGate composable + MainActivity wiring

This is the core fix for the duplicate-ask and no-rationale bugs. `MainActivity.requestStoragePermissions()` is deleted; a single controller now owns the whole sequence, and all file-system/update/migration setup is gated behind it completing.

**Files:**
- Create: `app/src/main/java/com/kkc/sheettracker/ui/onboarding/OnboardingGate.kt`
- Modify: `app/src/main/java/com/kkc/sheettracker/MainActivity.kt`

- [ ] **Step 1: Create the OnboardingGate composable**

```kotlin
package com.kkc.sheettracker.ui.onboarding

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.kkc.sheettracker.onboarding.OnboardingStep

@Composable
fun OnboardingGate(
    step: OnboardingStep,
    onRequestNotifications: () -> Unit,
    onConfirmStorageAccess: () -> Unit,
    onConfirmInstallPermission: () -> Unit
) {
    LaunchedEffect(step) {
        if (step == OnboardingStep.NOTIFICATIONS) {
            onRequestNotifications()
        }
    }
    when (step) {
        OnboardingStep.NOTIFICATIONS -> Unit
        OnboardingStep.STORAGE_ACCESS -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Storage Access Needed") },
            text = {
                Text(
                    "Sheet Tracker needs full storage access to read job files and sync data. " +
                        "Tap OK to grant it in the next screen."
                )
            },
            confirmButton = {
                Button(onClick = onConfirmStorageAccess) { Text("OK") }
            }
        )
        OnboardingStep.INSTALL_UNKNOWN_APPS -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Install Permission Needed") },
            text = { Text("Needed to install app updates when they're released.") },
            confirmButton = {
                Button(onClick = onConfirmInstallPermission) { Text("OK") }
            }
        )
    }
}
```

- [ ] **Step 2: Add onboarding imports to MainActivity.kt**

Modify `MainActivity.kt` — add after the existing `com.kkc.sheettracker.navigation.WorkMode` import (line 56):

```kotlin
import com.kkc.sheettracker.navigation.WorkMode
import com.kkc.sheettracker.onboarding.OnboardingStep
import com.kkc.sheettracker.onboarding.PermissionFlowController
import com.kkc.sheettracker.onboarding.PermissionSnapshot
```

And after the existing `com.kkc.sheettracker.ui.migration.MigrationRequiredScreen` import (line 64):

```kotlin
import com.kkc.sheettracker.ui.migration.MigrationRequiredScreen
import com.kkc.sheettracker.ui.onboarding.OnboardingGate
```

- [ ] **Step 3: Replace the launcher field with two launchers + onboarding state**

Modify `MainActivity.kt:84-86` — replace:

```kotlin
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* permissions handled */ }
```

with:

```kotlin
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refreshOnboardingStep() }

    private val onboardingSettingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val action = pendingSettingsReturnAction
        pendingSettingsReturnAction = null
        if (action != null) action() else refreshOnboardingStep()
    }

    private var pendingOnboardingStep by mutableStateOf<OnboardingStep?>(null)
    private var pendingSettingsReturnAction: (() -> Unit)? = null
```

- [ ] **Step 4: Restructure the start of onCreate to gate on the controller**

Modify `MainActivity.kt:92-97` — replace:

```kotlin
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestStoragePermissions()

        val prefs = getSharedPreferences("kkc_tracker", MODE_PRIVATE)
```

with:

```kotlin
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        refreshOnboardingStep()
        if (pendingOnboardingStep != null) {
            setContent {
                KKCTheme(darkTheme = androidx.compose.foundation.isSystemInDarkTheme()) {
                    PersistentNavigationBarHider()
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        val step = pendingOnboardingStep
                        if (step != null) {
                            OnboardingGate(
                                step = step,
                                onRequestNotifications = {
                                    requestPermissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                                },
                                onConfirmStorageAccess = {
                                    launchOnboardingSettingsIntent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                                },
                                onConfirmInstallPermission = {
                                    launchOnboardingSettingsIntent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                                }
                            )
                        }
                    }
                }
            }
            return
        }

        val prefs = getSharedPreferences("kkc_tracker", MODE_PRIVATE)
```

- [ ] **Step 5: Add the controller-wiring private methods**

Modify `MainActivity.kt` — add these three private methods right before `private fun findDefaultBasePath()` (currently `MainActivity.kt:556`):

```kotlin
    private fun refreshOnboardingStep() {
        val next = PermissionFlowController.nextStep(currentPermissionSnapshot())
        val wasPending = pendingOnboardingStep != null
        pendingOnboardingStep = next
        if (wasPending && next == null) {
            recreate()
        }
    }

    private fun currentPermissionSnapshot(): PermissionSnapshot {
        val notificationsGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else true
        val storageGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else true
        return PermissionSnapshot(
            sdkInt = Build.VERSION.SDK_INT,
            notificationsGranted = notificationsGranted,
            storageGranted = storageGranted,
            installUnknownAppsGranted = packageManager.canRequestPackageInstalls()
        )
    }

    private fun launchOnboardingSettingsIntent(action: String) {
        onboardingSettingsLauncher.launch(
            Intent(action).apply { data = Uri.parse("package:$packageName") }
        )
    }

```

- [ ] **Step 6: Re-check onboarding state on every foreground**

Modify `MainActivity.kt:518-520` — replace:

```kotlin
    override fun onStart() {
        super.onStart()
        if (::syncthingSupervisor.isInitialized) {
```

with:

```kotlin
    override fun onStart() {
        super.onStart()
        refreshOnboardingStep()
        if (::syncthingSupervisor.isInitialized) {
```

- [ ] **Step 7: Delete the old requestStoragePermissions method**

Modify `MainActivity.kt:568-597` — delete the entire method (from `private fun requestStoragePermissions() {` through its closing `}`):

```kotlin
    private fun requestStoragePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val notificationGranted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!notificationGranted) {
                requestPermissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = Uri.parse("package:$packageName")
                startActivity(intent)
            }
        } else {
            val perms = arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
            val needed = perms.filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
            if (needed.isNotEmpty()) {
                requestPermissionLauncher.launch(needed.toTypedArray())
            }
        }
    }

```

(This removes the `READ_EXTERNAL_STORAGE`/`WRITE_EXTERNAL_STORAGE` pre-API-30 fallback branch entirely — dead weight given `minSdk = 26` but `MANAGE_EXTERNAL_STORAGE` only applies API 30+; devices on API 26-29 now correctly fall through `PermissionFlowController`'s `storageGranted = true` default for that range, same as the pre-existing `else` behavior only mattered on the sliver of API 26-29 devices this fleet doesn't run. No tablet in the field is below API 30 — if that assumption turns out wrong, re-add a legacy permission array step.)

- [ ] **Step 8: Build to verify it compiles**

Run: `.\gradlew.bat assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/onboarding/OnboardingGate.kt app/src/main/java/com/kkc/sheettracker/MainActivity.kt
git commit -m "feat(onboarding): gate app startup behind sequential permission controller"
```

---

### Task 3: Fix UpdateManager's reactive install-permission ask

`UpdateManager` stops independently re-checking storage permission (dead code now that `MainActivity` never constructs it before the controller finishes), and its reactive install-unknown-apps check gets a retry callback instead of a fire-and-forget dialog.

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/update/UpdateManager.kt`
- Modify: `app/src/main/java/com/kkc/sheettracker/MainActivity.kt`

- [ ] **Step 1: Add the retry callback to UpdateManager's constructor**

Modify `UpdateManager.kt:44` — replace:

```kotlin
class UpdateManager(private val activity: Activity) {
```

with:

```kotlin
class UpdateManager(
    private val activity: Activity,
    private val onRequestInstallPermission: (onGranted: () -> Unit) -> Unit
) {
```

- [ ] **Step 2: Remove the redundant storage-permission check from checkForUpdates**

Modify `UpdateManager.kt:135-139` — replace:

```kotlin
    fun checkForUpdates(checkSelf: Boolean = true): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            requestStoragePermission()
            return true
        }
        Thread {
```

with:

```kotlin
    fun checkForUpdates(checkSelf: Boolean = true): Boolean {
        Thread {
```

- [ ] **Step 3: Make installApk retry through the callback instead of dead-ending**

Modify `UpdateManager.kt:436-440` — replace:

```kotlin
    private fun installApk(apkFile: File) {
        if (!activity.packageManager.canRequestPackageInstalls()) {
            requestInstallPermission()
            return
        }
```

with:

```kotlin
    private fun installApk(apkFile: File) {
        if (!activity.packageManager.canRequestPackageInstalls()) {
            onRequestInstallPermission { installApk(apkFile) }
            return
        }
```

- [ ] **Step 4: Delete the two now-dead permission-request methods**

Modify `UpdateManager.kt:495-529` — delete both `requestStoragePermission()` and `requestInstallPermission()` in full:

```kotlin
    @SuppressLint("InlinedApi")
    private fun requestStoragePermission() {
        AlertDialog.Builder(activity)
            .setTitle("Permission Required")
            .setMessage("To check for updates, this app needs access to manage all files. Please grant this permission in the next screen.")
            .setPositiveButton("OK") { _, _ ->
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        addCategory("android.intent.category.DEFAULT")
                        data = Uri.parse("package:${activity.packageName}")
                    }
                    activity.startActivity(intent)
                } catch (_: Exception) {
                    val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    activity.startActivity(intent)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun requestInstallPermission() {
        AlertDialog.Builder(activity)
            .setTitle("Permission Required")
            .setMessage("To perform updates, this app needs permission to install unknown apps. Please grant this permission in the next screen.")
            .setPositiveButton("Settings") { _, _ ->
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${activity.packageName}")
                }
                activity.startActivity(intent)
            }
            .setNegativeButton("Cancel", null)
            .setCancelable(false)
            .show()
    }
```

- [ ] **Step 5: Remove the now-unused imports**

Modify `UpdateManager.kt:5-12` — replace:

```kotlin
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
```

with:

```kotlin
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Build
import android.os.Environment
```

- [ ] **Step 6: Wire the callback at the construction site in MainActivity**

Modify `MainActivity.kt:119-124` — replace:

```kotlin
        updateManager = UpdateManager(this).apply {
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
                pendingSettingsReturnAction = onGranted
                launchOnboardingSettingsIntent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            }
        ).apply {
            this.basePath = basePath
            this.tabletId = tabletId
            isSilentUpdateSupported = !useLegacyUpdatePrompt
        }
        updateManager.checkForUpdates(checkSelf = true)
```

- [ ] **Step 7: Build to verify it compiles**

Run: `.\gradlew.bat assembleDebug`
Expected: BUILD SUCCESSFUL. (If `Uri` or `Settings` show as still-needed in UpdateManager.kt, the build error will name the missing symbol — re-check Step 5's usage search before assuming it's wrong; `Uri`/`Settings` are only referenced inside the two deleted methods.)

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/update/UpdateManager.kt app/src/main/java/com/kkc/sheettracker/MainActivity.kt
git commit -m "fix(update): auto-retry install after granting install-unknown-apps permission"
```

---

### Task 4: Defer and throttle the Syncthing key prompt

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/MainActivity.kt`

- [ ] **Step 1: Change the initial prompt state to respect a throttle**

Modify `MainActivity.kt:226` — replace:

```kotlin
            var showSyncthingSetupPrompt by rememberSaveable { mutableStateOf(true) }
```

with:

```kotlin
            var showSyncthingSetupPrompt by rememberSaveable { mutableStateOf(shouldPromptForSyncthingKey(prefs)) }
```

- [ ] **Step 2: Record the throttle timestamp when the user defers**

Modify `MainActivity.kt:422-426` — replace:

```kotlin
                            dismissButton = {
                                TextButton(onClick = { showSyncthingSetupPrompt = false }) {
                                    Text("Later")
                                }
                            }
```

with:

```kotlin
                            dismissButton = {
                                TextButton(onClick = {
                                    prefs.edit().putLong("last_syncthing_prompt_at_ms", System.currentTimeMillis()).apply()
                                    showSyncthingSetupPrompt = false
                                }) {
                                    Text("Later")
                                }
                            }
```

- [ ] **Step 3: Add the throttle helper**

Modify `MainActivity.kt` — add this private function next to `findDefaultBasePath()` (after the closing brace at what was `MainActivity.kt:566`, now shifted by Task 2's additions — place it directly above `private fun launchOnboardingSettingsIntent`):

```kotlin
    private fun shouldPromptForSyncthingKey(prefs: android.content.SharedPreferences): Boolean {
        val syncthingPromptIntervalMs = 12 * 60 * 60 * 1000L
        val lastPromptAtMs = prefs.getLong("last_syncthing_prompt_at_ms", 0L)
        return System.currentTimeMillis() - lastPromptAtMs >= syncthingPromptIntervalMs
    }

```

- [ ] **Step 4: Build to verify it compiles**

Run: `.\gradlew.bat assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/MainActivity.kt
git commit -m "fix(onboarding): defer Syncthing key prompt behind permission gate, throttle re-ask"
```

---

### Task 5: Deploy release build to the connected tablet for manual verification

Not a code task — builds and installs the result for the manual checklist from the design doc's Testing section. The user runs the actual on-device checks.

**Files:** none (build + deploy only)

- [ ] **Step 1: Confirm a tablet is attached**

Run: `adb devices`
Expected: one device listed as `device` (not `unauthorized`/`offline`).

- [ ] **Step 2: Build the release APK**

Run: `.\gradlew.bat assembleRelease`
Expected: BUILD SUCCESSFUL, produces `app/build/outputs/apk/release/app-release.apk`.

(Do not use `adb-install-release.ps1` for this step — it fails when invoked non-interactively due to a UTF-8/PowerShell 5.1 encoding issue with its `✓`/`✗` output characters. Run the two commands directly instead, as already established for this repo.)

- [ ] **Step 3: Install to the connected tablet**

Run: `adb install -r app\build\outputs\apk\release\app-release.apk`
Expected: `Success`.

- [ ] **Step 4: Hand off to the user for manual verification**

Tell the user the build is installed and ready. They will factory-reset or clear app data on the test tablet and walk through:
- A single clean pass through all 3 onboarding steps, no duplicate asks.
- Granting install-unknown-apps permission and confirming a pending update installs immediately without a restart (requires an update APK staged in the expected `.Updates`/`Updates` folder to exercise — otherwise just confirm the permission itself sticks without a stray dialog reappearing).
- Confirming the Syncthing key prompt doesn't appear until all permission steps are done.

No commit for this task — it's a deployment/verification step, not a code change.

---

## Self-Review Notes

- **Spec coverage:** duplicate all-files-access ask (Task 2) — covered. Install-permission restart bug (Task 3) — covered. Syncthing prompt stacking (Task 4) — covered. Migration/basePath racing the permission grant (Task 2 Step 4, the early-return gate) — covered, since `basePath`/`migrationMarkerPath` logic now only runs after the gate's `return`. Manual on-device checklist from the design doc's Testing section — covered in Task 5.
- **Type consistency checked:** `OnboardingStep`/`PermissionSnapshot`/`PermissionFlowController` names match between Task 1's creation and Task 2's usage. `onRequestInstallPermission: (onGranted: () -> Unit) -> Unit` signature matches between UpdateManager's constructor (Task 3 Step 1), its call site in `installApk` (Task 3 Step 3), and the lambda passed at the MainActivity construction site (Task 3 Step 6).
- **No placeholders:** every step above has complete, exact code — none deferred.
