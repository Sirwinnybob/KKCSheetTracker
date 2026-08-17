# Tablet Performance Cleanup and OCR Retirement Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove dead tablet OCR state and eliminate validated unnecessary refresh, UI-thread I/O, update parsing, and bitmap retention.

**Architecture:** Keep upstream metadata authoritative, make hidden-screen and refresh work demand-driven, and bound transient caches at their owners. Each task is independently testable and avoids the deferred cross-domain watcher/tab-lifecycle redesigns.

**Tech Stack:** Kotlin, Jetpack Compose, coroutines, Android `LruCache`, JUnit 4, Gradle, ADB.

## Global Constraints

- Never run `adb uninstall`; use `adb install -r` only.
- Preserve upstream sidecar fields `ocrBoxes`, `ocrSource`, `ocrGeneratedAt`, and `ocrVersion`.
- Do not add Android OCR execution, ML Kit text recognition, OCR prewarming, or OCR cache files.
- Keep ML Kit barcode scanning for Supply unchanged.
- Do not recursively delete existing tablet cache directories during deployment.
- Preserve unrelated working-tree changes and stage only task-owned files.
- Domain-aware watcher routing, hidden-NavHost unmounting, and registry lifecycle eviction are out of scope.

---

### Task 1: Remove dead tablet-local OCR cache ownership

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/data/ProgressStore.kt`
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/viewer/SheetViewerScreen.kt`
- Test: `app/src/test/java/com/kkc/sheettracker/data/ProgressStoreTest.kt`

**Interfaces:**
- Consumes: upstream `PageMetadata.ocrBoxes` only.
- Produces: `ProgressStore` with no OCR cache API/path; `PageMetadata?.toSidecarDiagramBounds()` for upstream bounds.

- [ ] **Step 1: Establish the existing ProgressStore baseline**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.data.ProgressStoreTest"
```

Expected: PASS before removal.

- [ ] **Step 2: Remove dead local OCR types, state, APIs, and pruning**

Delete from `ProgressStore.kt`:

```text
OcrBox
OcrPageCache
ocrMemCache
ocrCacheFile
ocrCacheKey
hasOcrCache
getOcrCache
saveOcrCache
sanitizeOcrPageCache
```

In `pruneLocalStateForJob`, delete the `localStateDir/ocr/$jobFolderName` directory walk, safe-name
lookup used only by that walk, valid OCR key construction, and `ocrMemCache` eviction. Keep draft and
prepared-page pruning unchanged. Update comments so they mention only drafts/prepared pages.

- [ ] **Step 3: Rename misleading Android viewer terminology**

In `SheetViewerScreen.kt`, rename:

```kotlin
private const val OCR_TAG = "KKC_OCR"
```

to:

```kotlin
private const val SHEET_RENDER_TAG = "KKC_SHEET_RENDER"
```

Rename `toSidecarOcrMap()` to `toSidecarDiagramBounds()` while continuing to read
`PageMetadata.ocrBoxes`. Replace remaining tag references with `SHEET_RENDER_TAG`; the release-log
plan removes/gates routine debug/info calls separately.

- [ ] **Step 4: Verify the ownership boundary**

```powershell
rg -n "ocrMemCache|ocrCache(File|Key)|hasOcrCache|getOcrCache|saveOcrCache|OcrPageCache" app/src/main/java
rg -n 'File\(localStateDir, "ocr/' app/src/main/java
rg -n "ocrBoxes|ocrSource|ocrGeneratedAt|ocrVersion" app/src/main/java/com/kkc/sheettracker/data/models/Models.kt app/src/main/java/com/kkc/sheettracker/data/unified/FileBackedUnifiedMetadataEngine.kt
```

Expected: the first two commands have no output; the final command still shows upstream schema consumption.

- [ ] **Step 5: Run regression tests and commit**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.data.ProgressStoreTest" --tests "com.kkc.sheettracker.ui.viewer.SheetViewerScreenTest"
git add -- app/src/main/java/com/kkc/sheettracker/data/ProgressStore.kt app/src/main/java/com/kkc/sheettracker/ui/viewer/SheetViewerScreen.kt app/src/test/java/com/kkc/sheettracker/data/ProgressStoreTest.kt
git commit -m "refactor(ocr): remove tablet cache ownership"
```

### Task 2: Emit one watcher refresh per invalidation batch

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/navigation/NavGraph.kt`
- Modify: `app/src/test/java/com/kkc/sheettracker/navigation/NavGraphCncSyncWiringTest.kt`

**Interfaces:**
- Consumes: `TrackerChangeMonitor.onCncJobsChanged` and `onWatcherRefreshRequested`.
- Produces: targeted job invalidation immediately; one delayed/coalesced watcher epoch.

- [ ] **Step 1: Write the failing wiring assertion**

Add a source-wiring assertion proving both navigation graphs retain targeted invalidation while only
the two `onWatcherRefreshRequested` callbacks write the epoch:

```kotlin
@Test
fun cncInvalidationDoesNotEmitASecondWatcherEpoch() {
    val source = navGraphSource()
    assertEquals(
        2,
        Regex("jobFolderNames\\.forEach \\{ scanCoordinator\\.unifiedEngine\\.invalidateJob\\(it\\) \\}")
            .findAll(source).count()
    )
    assertEquals(
        2,
        Regex("watcherRefreshSignal\\.value = System\\.currentTimeMillis\\(\\)")
            .findAll(source).count()
    )
}
```

- [ ] **Step 2: Run RED, remove both immediate epoch writes, and run GREEN**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.navigation.NavGraphCncSyncWiringTest"
```

Expected before edit: FAIL because both duplicated navigation graphs write the epoch inside
`onCncJobsChanged`. Remove only those two writes; keep job invalidation and the coalesced callback.
Run the command again; expected PASS.

- [ ] **Step 3: Run monitor regressions and commit**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.data.TrackerChangeMonitor*" --tests "com.kkc.sheettracker.navigation.NavGraphCncSyncWiringTest"
git add -- app/src/main/java/com/kkc/sheettracker/navigation/NavGraph.kt app/src/test/java/com/kkc/sheettracker/navigation/NavGraphCncSyncWiringTest.kt
git commit -m "perf(scan): coalesce CNC watcher refresh"
```

### Task 3: Gate hidden Jobs work and move specialty reads to IO

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/jobs/UnifiedJobsScreen.kt`
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/jobs/UnifiedModeSpecs.kt`
- Modify: `app/src/main/java/com/kkc/sheettracker/navigation/NavGraph.kt`
- Create: `app/src/main/java/com/kkc/sheettracker/navigation/SpecialtyAvailability.kt`
- Create: `app/src/test/java/com/kkc/sheettracker/navigation/SpecialtyAvailabilityTest.kt`
- Create: `app/src/test/java/com/kkc/sheettracker/ui/jobs/UnifiedJobsScreenTest.kt`

**Interfaces:**
- Consumes: `UnifiedJobsScreen.active`, `JobRepository`, and `UnifiedJobsSpec.resolveBadges`.
- Produces: `SpecialtyAvailability`, `resolveSpecialtyAvailability(...)`,
  `loadSpecialtyAvailability(JobRepository, String): SpecialtyAvailability`, and
  `shouldRunUnifiedJobsBackgroundWork(Boolean): Boolean`.

- [ ] **Step 1: Write the failing availability test**

Define the desired immutable result:

```kotlin
internal data class SpecialtyAvailability(
    val hasDeliverySheet: Boolean = false,
    val hasAssemblySheet: Boolean = false,
    val hasPlansElevations: Boolean = false,
    val hasThreeDAssets: Boolean = false,
    val hasClosetRods: Boolean = false
)
```

Test the pure resolver without filesystem mocks:

```kotlin
@Test
fun resolverCombinesTheFiveAvailabilityChecks() {
    val result = resolveSpecialtyAvailability(
        hasDeliverySheet = { true },
        hasAssemblySheet = { false },
        hasPlansElevations = { true },
        hasThreeDAssets = { true },
        hasClosetRods = { false }
    )

    assertEquals(
        SpecialtyAvailability(
            hasDeliverySheet = true,
            hasAssemblySheet = false,
            hasPlansElevations = true,
            hasThreeDAssets = true,
            hasClosetRods = false
        ),
        result
    )
}
```

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.navigation.SpecialtyAvailabilityTest"
```

Expected: compilation fails because the result/loader do not exist.

Also create `UnifiedJobsScreenTest.kt`:

```kotlin
package com.kkc.sheettracker.ui.jobs

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnifiedJobsScreenTest {
    @Test
    fun backgroundWorkRunsOnlyForTheActiveTab() {
        assertTrue(shouldRunUnifiedJobsBackgroundWork(active = true))
        assertFalse(shouldRunUnifiedJobsBackgroundWork(active = false))
    }
}
```

Run it before production edits and expect compilation to fail because the policy helper is absent.

- [ ] **Step 2: Implement one IO-backed specialty availability load**

Implement `resolveSpecialtyAvailability` by invoking each supplied lambda once. Implement
`loadSpecialtyAvailability` as a regular blocking adapter that supplies the five existing repository
calls to that pure resolver. In both specialty route copies, use:

```kotlin
val availability by produceState(SpecialtyAvailability(), folderName) {
    value = withContext(Dispatchers.IO) {
        loadSpecialtyAvailability(jobRepository, folderName)
    }
}
```

Pass the five fields to `SpecialtyJobDetailScreen`; remove the five synchronous `remember` blocks.

- [ ] **Step 3: Gate hidden Jobs effects and badge I/O**

Change the initial refresh effect to:

```kotlin
internal fun shouldRunUnifiedJobsBackgroundWork(active: Boolean): Boolean = active

LaunchedEffect(active) {
    if (!shouldRunUnifiedJobsBackgroundWork(active)) return@LaunchedEffect
    if (lowEndMode.lazyLoadingActive) delay(500)
    spec.refresh(RefreshReason.APP_FOREGROUND, force = false)
}
```

Key badge effects by `active` and use the same policy helper to return immediately when false. In the specialty
`resolveBadges`, wrap `getJobPdfCatalog` and `hasThreeDAssets` in `withContext(Dispatchers.IO)`.

- [ ] **Step 4: Run focused tests and commit**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.navigation.SpecialtyAvailabilityTest" --tests "com.kkc.sheettracker.navigation.SpecialtyRouteTest" --tests "com.kkc.sheettracker.ui.jobs.UnifiedJobsScreenTest"
.\gradlew.bat :app:compileDebugKotlin
git add -- app/src/main/java/com/kkc/sheettracker/ui/jobs/UnifiedJobsScreen.kt app/src/main/java/com/kkc/sheettracker/ui/jobs/UnifiedModeSpecs.kt app/src/main/java/com/kkc/sheettracker/navigation/NavGraph.kt app/src/main/java/com/kkc/sheettracker/navigation/SpecialtyAvailability.kt app/src/test/java/com/kkc/sheettracker/navigation/SpecialtyAvailabilityTest.kt app/src/test/java/com/kkc/sheettracker/ui/jobs/UnifiedJobsScreenTest.kt
git commit -m "perf(ui): suspend hidden metadata work"
```

### Task 4: Single-flight update scanning and APK parse reuse

**Files:**
- Create: `app/src/main/java/com/kkc/sheettracker/update/UpdateScanPolicy.kt`
- Create: `app/src/test/java/com/kkc/sheettracker/update/UpdateScanPolicyTest.kt`
- Modify: `app/src/main/java/com/kkc/sheettracker/update/UpdateManager.kt`

**Interfaces:**
- Produces: `UpdateScanGate.tryEnter()/leave()` and `ApkArchiveFingerprint.from(File)`.

- [ ] **Step 1: Write failing policy tests**

Test that a second `tryEnter()` fails until `leave()`, and that fingerprints change with path,
length, or mtime:

```kotlin
@Test fun gateAllowsOnlyOneScan() {
    val gate = UpdateScanGate()
    assertTrue(gate.tryEnter())
    assertFalse(gate.tryEnter())
    gate.leave()
    assertTrue(gate.tryEnter())
}
```

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.update.UpdateScanPolicyTest"
```

Expected: compilation fails because policy types do not exist.

- [ ] **Step 2: Implement and integrate**

Create `UpdateScanPolicy.kt`:

```kotlin
package com.kkc.sheettracker.update

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

internal data class ApkArchiveFingerprint(
    val absolutePath: String,
    val length: Long,
    val lastModified: Long
) {
    companion object {
        fun from(file: File): ApkArchiveFingerprint = ApkArchiveFingerprint(
            absolutePath = file.absolutePath,
            length = file.length(),
            lastModified = file.lastModified()
        )
    }
}

internal class UpdateScanGate {
    private val active = AtomicBoolean(false)
    fun tryEnter(): Boolean = active.compareAndSet(false, true)
    fun leave() { active.set(false) }
}
```

Add a
`ConcurrentHashMap<ApkArchiveFingerprint, ApkInfo>` to `UpdateManager` (cache successful parses;
`ConcurrentHashMap` cannot store null); both self and external scans must resolve through one cached
`getApkInfo`. Wrap the scan thread in `try/finally { gate.leave() }`.
If `tryEnter()` is false, return without launching another thread.

- [ ] **Step 3: Verify and commit**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.update.UpdateScanPolicyTest"
.\gradlew.bat :app:compileDebugKotlin
git add -- app/src/main/java/com/kkc/sheettracker/update/UpdateScanPolicy.kt app/src/main/java/com/kkc/sheettracker/update/UpdateManager.kt app/src/test/java/com/kkc/sheettracker/update/UpdateScanPolicyTest.kt
git commit -m "perf(update): dedupe APK scans"
```

### Task 5: Bound modal Sheet bitmap retention

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/components/ReferenceModalOverlay.kt`
- Modify: `app/src/test/java/com/kkc/sheettracker/ui/components/ReferenceModalStateTest.kt`

**Interfaces:**
- Produces: a four-entry `LruCache<Int, Bitmap>` scoped to `sheetPdfFile` and refresh identity.

- [ ] **Step 1: Add and verify an LRU-size constant**

Add `internal const val REFERENCE_MODAL_SHEET_CACHE_PAGES = 4` and a test asserting the intentional
bound. Run `ReferenceModalStateTest`; expected RED until the constant exists.

- [ ] **Step 2: Replace the map and add disposal**

```kotlin
val sheetBitmapCache = remember(sheetPdfFile, refreshGeneration) {
    LruCache<Int, Bitmap>(REFERENCE_MODAL_SHEET_CACHE_PAGES)
}
DisposableEffect(sheetBitmapCache) {
    onDispose { sheetBitmapCache.evictAll() }
}
```

Use `get(pageIndex)` / `put(pageIndex, resolved)`. Do not call `Bitmap.recycle()`.

- [ ] **Step 3: Test and commit**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.ui.components.ReferenceModalStateTest"
git add -- app/src/main/java/com/kkc/sheettracker/ui/components/ReferenceModalOverlay.kt app/src/test/java/com/kkc/sheettracker/ui/components/ReferenceModalStateTest.kt
git commit -m "perf(viewer): bound modal sheet cache"
```

### Task 6: Full verification

**Files:** No source changes expected.

**Interfaces:** Consumes all prior tasks and the separate continuous-viewer/logging plans.

- [ ] **Step 1: Run source ownership audits**

```powershell
rg -n "hasOcrCache|getOcrCache|saveOcrCache|ocrMemCache|OcrPageCache" app/src/main/java
rg -n 'File\(localStateDir, "ocr/' app/src/main/java
rg -n "ocrBoxes|ocrSource|ocrGeneratedAt|ocrVersion" app/src/main/java/com/kkc/sheettracker/data/models/Models.kt app/src/main/java/com/kkc/sheettracker/data/unified/FileBackedUnifiedMetadataEngine.kt
```

Expected: first two have no output; final command confirms upstream compatibility remains.

- [ ] **Step 2: Run tests and builds sequentially**

```powershell
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:assembleRelease
```

Expected: all exit 0.

- [ ] **Step 3: Install and verify on the connected tablet**

```powershell
adb -s R52W209W6RA install -r app\build\outputs\apk\debug\app-debug.apk
```

Verify Jobs tab switching, specialty route entry, update check behavior, modal Sheet page navigation,
and continuous PDF zoom/scroll. Never uninstall the app.

## Deferred follow-up designs

- Domain/job-aware watcher routing to replace multi-coordinator `force=true` fan-out.
- Saveable unmounting of hidden tab NavHosts.
- Unified metadata registry/job eviction on base-path and job removal.
