# Lightning List Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Jobs list screen renders immediately from cache_index.json. cache_static.json loads only on tap, per-job.

**Architecture:** `scanJobsFromCacheOnly()` skips `getCncSnapshot()` — snapshot.jobs starts empty. `rememberCncJobsSpec` builds cards from cache_index. Six downstream screens switch from `scanState.snapshot.jobs.find` to direct `engine.getCncSnapshot(folderName)?.job`. Remaining full-list consumers (dashboard, assembly, staleness guards) tolerate empty list.

**Tech Stack:** Kotlin, Android, Compose, Gson

## Global Constraints

- No schema changes to cache_index.json or cache_static.json
- `UnifiedMetadataEngine` interface unchanged (already has needed methods)
- Build must compile with zero new warnings
- `rememberCncJobsSpec` signature unchanged (callers already pass `engine`)

---
### Task 1: Skip `getCncSnapshot()` in initial scan

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/data/ScanCoordinator.kt:197-217`
- Modify: `app/src/main/java/com/kkc/sheettracker/data/ScanCoordinator.kt:139-142` (staleness guard)

**Interfaces:**
- Consumes: `listJobsFromCacheOnly()` already returns job infos
- Produces: `ScanResult.jobs` empty list, `needsDeepLoad` still populated

- [ ] **Step 1: Modify `scanJobsFromCacheOnly()`**

Replace the full `getCncSnapshot()` loop with a lightweight scan that only builds the needsDeepLoad list:

```kotlin
private fun scanJobsFromCacheOnly(): ScanResult {
    if (!baseDir.exists() || !baseDir.isDirectory)
        return ScanResult(emptyList(), emptyList(), emptyList(), emptyList())
    val (jobInfos, needsDeepLoad) = unifiedEngine.listJobsFromCacheOnly()
    // List screen reads cache_index.json directly via rememberCncJobsSpec.
    // Full cache_static.json loads on per-job tap via getCncSnapshot().
    // Background deep load populates snapshot.jobs via updateJobsInState().
    return ScanResult(emptyList(), emptyList(), emptyList(), needsDeepLoad)
}
```

Remove the `jobs`, `search`, `issues` mutable lists and the `forEach` loop.

- [ ] **Step 2: Fix staleness guard**

`runRefresh()` line 139 checks `previous.snapshot.jobs.isNotEmpty()` to decide on staleness fast path. With empty list, this is always false. Change to check `previous.snapshot.generation > 0`:

```kotlin
val unchanged = currentSignature == lastStalenessSignature &&
    previous.snapshot.generation > 0
```

This still skips the staleness check when nothing changed, but doesn't depend on jobs list being populated.

- [ ] **Step 3: Verify build compiles**

Run: `.\gradlew.bat :app:compileDebugKotlin --no-daemon`

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "perf: skip getCncSnapshot in initial scan, defer to per-job tap"
```

---
### Task 2: Switch single-job lookup screens to `getCncSnapshot()`

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/detail/JobDetailScreen.kt:124`
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/viewer/SheetViewerScreen.kt:699,1020-1022,1331`

**Interfaces:**
- Consumes: `engine.getCncSnapshot(folderName)` returns `UnifiedCncSnapshot?`
- Produces: Same behavior as before, but loads on demand instead of from snapshot

Each screen currently does: `scanState.snapshot.jobs.find { it.folderName == targetFolderName }` to get a single `Job` object. Replace with: `engine.getCncSnapshot(targetFolderName)?.job`.

- [ ] **Step 1: Fix JobDetailScreen.kt**

Line 124 has:
```kotlin
val job = scanState.snapshot.jobs.find { it.folderName == jobFolderName }
```

Replace with:
```kotlin
val job = unifiedEngine.getCncSnapshot(jobFolderName)?.job
```

Note: `unifiedEngine` is already available in `JobDetailScreen` composable params.

- [ ] **Step 2: Fix SheetViewerScreen.kt (3 locations)**

Line 699:
```kotlin
val job = scanState.snapshot.jobs.find { it.folderName == jobFolderName }
```
Replace with:
```kotlin
val job = unifiedEngine.getCncSnapshot(jobFolderName)?.job
```

Line 1020-1022:
```kotlin
val currentJobNumber = scanState.snapshot.jobs
    .firstOrNull { it.folderName == jobFolderName }?.jobNumber ?: ""
```
Replace with:
```kotlin
val currentJobNumber = unifiedEngine.getCncSnapshot(jobFolderName)?.job?.jobNumber ?: ""
```

Line 1331:
```kotlin
val job = scanState.snapshot.jobs.find { it.folderName == jobFolderName }
```
Replace with:
```kotlin
val job = unifiedEngine.getCncSnapshot(jobFolderName)?.job
```

- [ ] **Step 3: Verify build compiles**

Run: `.\gradlew.bat :app:compileDebugKotlin --no-daemon`

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "refactor: switch detail/viewer screens to direct getCncSnapshot"
```

---
### Task 3: Build release APK and install

**Files:**
- No code changes

- [ ] **Step 1: Bump version to 8.0.3**

Edit `app/build.gradle.kts`:
```kotlin
versionCode = 80003
versionName = "8.0.3"
```

- [ ] **Step 2: Build release APK**

Run: `.\gradlew.bat :app:assembleRelease --no-daemon`

- [ ] **Step 3: Install to tablet**

Run: `adb install -r app\build\outputs\apk\release\app-release.apk`

- [ ] **Step 4: Commit version bump**

```bash
git add app/build.gradle.kts
git commit -m "chore: bump to 8.0.3"
```

---
## Self-Review

| Spec requirement | Task coverage |
|---|---|
| scanJobsFromCacheOnly skips getCncSnapshot | Task 1 (returns empty jobs list) |
| List reads cache_index | Already done in UnifiedModeSpecs.kt (previous commit) |
| Screens load cache_static on tap | Task 2 (6 replacements in 2 files) |
| Staleness guard tolerant of empty list | Task 1 Step 2 (generation check) |
| Build compiles | Task 1 Step 3, Task 2 Step 3 |
