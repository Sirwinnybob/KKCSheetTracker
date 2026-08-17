# Release Logging Policy Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ensure KKCSheetTracker release builds emit no app-owned debug/info logs while retaining actionable warnings, errors, and crash reports.

**Architecture:** Route routine application diagnostics through one logging facade whose pure policy gates debug/info on `BuildConfig.DEBUG`. Keep warning/error behavior and `CrashReporter` persistence unchanged; do not rely on R8 stripping.

**Tech Stack:** Kotlin, Android `Log`, generated `BuildConfig`, JUnit 4, Gradle.

## Global Constraints

- App-owned verbose/debug/info logs must be disabled in release builds.
- Warning/error logs and on-disk crash reporting remain available in release.
- Do not enable minification, change signing, add dependencies, or suppress framework/third-party logs.
- High-frequency continuous-viewer traces are deleted by the viewer-fix plan, not migrated.
- Preserve unrelated working-tree changes and stage only task-owned files.

---

### Task 1: Central logging facade and emission policy

**Files:**
- Create: `app/src/main/java/com/kkc/sheettracker/logging/AppLog.kt`
- Create: `app/src/test/java/com/kkc/sheettracker/logging/AppLogTest.kt`

**Interfaces:**
- Consumes: `BuildConfig.DEBUG` and Android `Log`.
- Produces: `AppLog.d`, `AppLog.i`, `AppLog.w`, `AppLog.e`, `AppLogPriority`, and `shouldEmitAppLog(AppLogPriority, Boolean): Boolean`.

- [ ] **Step 1: Write the failing policy tests**

```kotlin
package com.kkc.sheettracker.logging

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLogTest {
    @Test
    fun release_disablesDebugAndInfo() {
        assertFalse(shouldEmitAppLog(AppLogPriority.DEBUG, isDebugBuild = false))
        assertFalse(shouldEmitAppLog(AppLogPriority.INFO, isDebugBuild = false))
    }

    @Test
    fun release_retainsWarningsAndErrors() {
        assertTrue(shouldEmitAppLog(AppLogPriority.WARN, isDebugBuild = false))
        assertTrue(shouldEmitAppLog(AppLogPriority.ERROR, isDebugBuild = false))
    }

    @Test
    fun debugBuild_emitsEverySupportedPriority() {
        AppLogPriority.entries.forEach { priority ->
            assertTrue(shouldEmitAppLog(priority, isDebugBuild = true))
        }
    }
}
```

- [ ] **Step 2: Run the focused test and verify RED**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.logging.AppLogTest"
```

Expected: compilation fails only because the logging facade and policy do not exist.

- [ ] **Step 3: Implement the minimal facade**

Create `AppLog.kt`:

```kotlin
package com.kkc.sheettracker.logging

import android.util.Log
import com.kkc.sheettracker.BuildConfig

internal enum class AppLogPriority { DEBUG, INFO, WARN, ERROR }

internal fun shouldEmitAppLog(priority: AppLogPriority, isDebugBuild: Boolean): Boolean =
    isDebugBuild || priority == AppLogPriority.WARN || priority == AppLogPriority.ERROR

object AppLog {
    fun d(tag: String, message: String, throwable: Throwable? = null): Int =
        emit(AppLogPriority.DEBUG, tag, message, throwable)

    fun i(tag: String, message: String, throwable: Throwable? = null): Int =
        emit(AppLogPriority.INFO, tag, message, throwable)

    fun w(tag: String, message: String, throwable: Throwable? = null): Int =
        emit(AppLogPriority.WARN, tag, message, throwable)

    fun e(tag: String, message: String, throwable: Throwable? = null): Int =
        emit(AppLogPriority.ERROR, tag, message, throwable)

    private fun emit(
        priority: AppLogPriority,
        tag: String,
        message: String,
        throwable: Throwable?
    ): Int {
        if (!shouldEmitAppLog(priority, BuildConfig.DEBUG)) return 0
        return when (priority) {
            AppLogPriority.DEBUG -> if (throwable == null) Log.d(tag, message) else Log.d(tag, message, throwable)
            AppLogPriority.INFO -> if (throwable == null) Log.i(tag, message) else Log.i(tag, message, throwable)
            AppLogPriority.WARN -> if (throwable == null) Log.w(tag, message) else Log.w(tag, message, throwable)
            AppLogPriority.ERROR -> if (throwable == null) Log.e(tag, message) else Log.e(tag, message, throwable)
        }
    }
}
```

- [ ] **Step 4: Run the focused test and verify GREEN**

Run the Step 2 command. Expected: PASS.

- [ ] **Step 5: Commit the logging boundary**

```powershell
git add -- app/src/main/java/com/kkc/sheettracker/logging/AppLog.kt app/src/test/java/com/kkc/sheettracker/logging/AppLogTest.kt
git commit -m "feat(logging): gate routine release logs"
```

### Task 2: Migrate all remaining debug/info call sites

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/data/AppStateStore.kt`
- Modify: `app/src/main/java/com/kkc/sheettracker/data/PdfMarkupStore.kt`
- Modify: `app/src/main/java/com/kkc/sheettracker/data/ProgressStore.kt`
- Modify: `app/src/main/java/com/kkc/sheettracker/data/StaticCachePoller.kt`
- Modify: `app/src/main/java/com/kkc/sheettracker/data/TimecardDiscovery.kt`
- Modify: `app/src/main/java/com/kkc/sheettracker/navigation/NavigationCoordinator.kt`
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/assembly/AssemblyViewerScreen.kt`
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/components/ReferencePdfPane.kt`
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/viewer/SheetViewerScreen.kt`
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/viewer/UnifiedReferenceViewer.kt`
- Modify: `app/src/main/java/com/kkc/sheettracker/update/DeviceOwnerUpdateFallback.kt`
- Modify: `app/src/main/java/com/kkc/sheettracker/update/UpdateManager.kt`
- Modify: `app/src/main/java/com/kkc/sheettracker/viewer3d/ViewerServer.kt`

**Interfaces:**
- Consumes: `AppLog.d` and `AppLog.i` from Task 1.
- Produces: no direct app-owned `Log.d` or `Log.i` call outside `AppLog.kt`.

- [ ] **Step 1: Capture the pre-migration failure**

```powershell
rg -n -g '!AppLog.kt' "\b(?:android\.util\.)?Log\.(d|i)\(" app/src/main/java
```

Expected: output lists the remaining direct debug/info calls in the exact files above. `ContinuousReferencePdfPane.kt` must already have no matches after the viewer-fix plan.

- [ ] **Step 2: Mechanically route debug/info through `AppLog`**

In every listed file:

```kotlin
import com.kkc.sheettracker.logging.AppLog
```

Replace `Log.d(...)` / `android.util.Log.d(...)` with `AppLog.d(...)`, and `Log.i(...)` with `AppLog.i(...)`. Keep `Log.w`, `Log.e`, and `CrashReporter` unchanged. Remove `android.util.Log` imports only in files where no warning/error use remains.

- [ ] **Step 3: Verify no release-unsafe direct calls remain**

Run the Step 1 command again. Expected: no output.

Also verify that warnings/errors were not accidentally removed:

```powershell
rg -n "\b(?:android\.util\.)?Log\.(w|e)\(" app/src/main/java/com/kkc/sheettracker/crash app/src/main/java/com/kkc/sheettracker/data app/src/main/java/com/kkc/sheettracker/update
```

Expected: actionable warning/error sites, including `CrashReporter`, remain.

- [ ] **Step 4: Compile and run policy tests**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.logging.AppLogTest"
.\gradlew.bat :app:compileDebugKotlin
```

Expected: both commands exit 0.

- [ ] **Step 5: Commit the call-site migration**

```powershell
git add -- app/src/main/java/com/kkc/sheettracker/data/AppStateStore.kt app/src/main/java/com/kkc/sheettracker/data/PdfMarkupStore.kt app/src/main/java/com/kkc/sheettracker/data/ProgressStore.kt app/src/main/java/com/kkc/sheettracker/data/StaticCachePoller.kt app/src/main/java/com/kkc/sheettracker/data/TimecardDiscovery.kt app/src/main/java/com/kkc/sheettracker/navigation/NavigationCoordinator.kt app/src/main/java/com/kkc/sheettracker/ui/assembly/AssemblyViewerScreen.kt app/src/main/java/com/kkc/sheettracker/ui/components/ReferencePdfPane.kt app/src/main/java/com/kkc/sheettracker/ui/viewer/SheetViewerScreen.kt app/src/main/java/com/kkc/sheettracker/ui/viewer/UnifiedReferenceViewer.kt app/src/main/java/com/kkc/sheettracker/update/DeviceOwnerUpdateFallback.kt app/src/main/java/com/kkc/sheettracker/update/UpdateManager.kt app/src/main/java/com/kkc/sheettracker/viewer3d/ViewerServer.kt
git commit -m "refactor(logging): route routine diagnostics"
```

### Task 3: Release-policy verification

**Files:**
- No source changes expected; failures return to Task 1 or Task 2 with a focused test.

**Interfaces:**
- Consumes: the logging facade and migrated call sites.
- Produces: debug/release build evidence and source-policy evidence.

- [ ] **Step 1: Run the full debug unit suite**

```powershell
.\gradlew.bat :app:testDebugUnitTest
```

Expected: exit 0.

- [ ] **Step 2: Build both variants sequentially**

```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:assembleRelease
```

Expected: both exit 0. Do not change signing or minification if release assembly exposes an environment-specific signing limitation; report that limitation separately.

- [ ] **Step 3: Run the final source-policy audit**

```powershell
rg -n -g '!AppLog.kt' "\b(?:android\.util\.)?Log\.(d|i)\(" app/src/main/java
rg -n "PdfFlingDebug|PdfRenderTrace" app/src/main/java
```

Expected: both commands produce no output.

- [ ] **Step 4: Confirm crash evidence remains**

```powershell
rg -n "recordFatalCrash|Log\.(w|e)" app/src/main/java/com/kkc/sheettracker/crash/CrashReporter.kt
```

Expected: the fatal crash persistence path and warning/error fallbacks remain present.
