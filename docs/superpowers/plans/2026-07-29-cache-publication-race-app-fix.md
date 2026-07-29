# Cache Publication Race App Fix Implementation Plan

> For agentic workers: REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement task by task. Steps use checkbox syntax.

**Goal:** Keep a newly released job or CNC remake visible when a watcher refresh occurs before Ready Jobs Watcher publishes matching cache_static.json.

**Architecture:** Record whether an in-memory static job comes from published cache or a deep raw parse. Cache-only refresh remains fast for normal jobs, but retains a deep generation for a gate-included job while its cache is missing or stale. A current published cache becomes authoritative.

**Tech Stack:** Kotlin, JUnit 4, coroutines and StateFlow, Gson, Android Gradle unit tests.

## Global Constraints

- Planning only. Do not modify production code until approved.
- Preserve unrelated dirty worktree files. Stage only files named in each task.
- deployment_gate.json remains authoritative. Never retain a pending, hidden, or parseReady=false job.
- Do not make every watcher refresh a raw deep scan.
- Ready Jobs Watcher remains the only cache_static.json publisher.
- The RJW fix must publish cache before parseReady=true, but Android still tolerates out-of-order Syncthing arrival.

---

## File Map

- app/src/main/java/com/kkc/sheettracker/data/unified/FileBackedUnifiedMetadataEngine.kt: records cache versus deep origin and retains deep data through stale or missing cache-only scans.
- app/src/test/java/com/kkc/sheettracker/data/UnifiedFacadeParityTest.kt: regression coverage for remake and newly released job visibility.
- app/src/main/java/com/kkc/sheettracker/data/ScanCoordinator.kt: no planned code change; existing WATCHER_CHANGE is the regression caller.

### Task 1: Reproduce both overwrites

**Files:**
- Modify: app/src/test/java/com/kkc/sheettracker/data/UnifiedFacadeParityTest.kt

**Interfaces:**
- Consumes: ScanCoordinator.refresh, ScanCoordinator.refreshJobsDeep, existing seedInitialStaticCache.
- Produces: two focused cache-publication race tests.

- [ ] **Step 1: Add stale-cache remake test**

Add scanCoordinator_cacheOnlyWatcherRefreshDoesNotDiscardDeepRemakeWhilePublishedCacheIsStale. Seed normal raw files and the one-material cache. Add 1234 - Remake Maple.pdf and its existing-test sidecar but leave cache_static.json unchanged. Call refreshJobsDeep(listOf(jobFolder)), wait for remake, then call:

```kotlin
coordinator.refresh(RefreshReason.WATCHER_CHANGE, force = true)
val remakeSurvived = waitUntil(timeoutMs = 5_000L) {
    coordinator.state.value.status == ScanStatus.READY &&
        coordinator.state.value.snapshot.jobs.singleOrNull()?.materials
            ?.any { it.pdfFilename == "1234 - Remake Maple.pdf" } == true
}
assertTrue("Watcher refresh must not replace deep remake with stale cache", remakeSurvived)
```

- [ ] **Step 2: Add missing-cache release test**

Add scanCoordinator_cacheOnlyWatcherRefreshDoesNotDiscardDeepJobWhilePublishedCacheIsMissing. Seed raw files and gate {"deployed": true, "parseReady": true}; do not call seedInitialStaticCache. Trigger WATCHER_CHANGE, wait for background deep-load, trigger a second WATCHER_CHANGE, and assert jobFolder is still the only job.

- [ ] **Step 3: Prove failure before implementation**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.data.UnifiedFacadeParityTest"
```

Expected: FAIL. Current cache-only rebuilding overwrites the deep remake with old cache and drops a deep-loaded no-cache job.

### Task 2: Preserve deep generation until cache publication

**Files:**
- Modify: app/src/main/java/com/kkc/sheettracker/data/unified/FileBackedUnifiedMetadataEngine.kt
- Test: app/src/test/java/com/kkc/sheettracker/data/UnifiedFacadeParityTest.kt

**Interfaces:**
- Consumes: DeploymentGateRules.evaluate, checkIsCacheStale, and staticByJob.
- Produces: StaticEntryOrigin, CachedStaticEntry.origin, and cache-only listing that cannot discard a valid deep result.

- [ ] **Step 1: Add immutable origin**

At CachedStaticEntry, introduce:

```kotlin
private enum class StaticEntryOrigin { PUBLISHED_CACHE, DEEP_PARSE }
private data class CachedStaticEntry(
    val signature: Long,
    val data: StaticJobData,
    val origin: StaticEntryOrigin
)
```

Mark all cache_static.json reads in getMergedJobInfo, listJobsFromCacheOnly, and loadJobFromCacheFile as PUBLISHED_CACHE. Mark the raw rebuild at the end of loadStaticJobData as DEEP_PARSE.

- [ ] **Step 2: Preserve deep entry with missing cache**

After gate validation in listJobsFromCacheOnly, preserve an existing DEEP_PARSE entry when cache file is absent. Otherwise add a parsable job folder to needsDeepLoad. Extract the repeated UnifiedJobInfo construction into private mergedJobInfo(rawInfo, folderName, config), and use it for cache and retained deep entries.

```kotlin
val existing = staticByJob[dir.name]
if (!cacheFile.isFile) {
    if (existing?.origin == StaticEntryOrigin.DEEP_PARSE) {
        loaded += mergedJobInfo(existing.data.jobInfo, dir.name, boardConfigs[dir.name])
    } else if (parseJobFolderName(dir.name) != null) {
        needsDeepLoad += dir.name
    }
    continue
}
```

- [ ] **Step 3: Preserve deep entry with stale cache**

Before cache-only logic reads and overwrites an entry, use this limited check:

```kotlin
if (existing?.origin == StaticEntryOrigin.DEEP_PARSE &&
    checkIsCacheStale(dir, cacheFile.lastModified())
) {
    loaded += mergedJobInfo(existing.data.jobInfo, dir.name, boardConfigs[dir.name])
    continue
}
```

This executes staleness work only for already deep-parsed jobs. Ordinary jobs remain cache-only. Once RJW publishes current cache, normal code replaces the deep entry with PUBLISHED_CACHE.

- [ ] **Step 4: Pass focused and nearby suites**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.data.UnifiedFacadeParityTest"
.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.data.UnifiedMetadataEngineTest" --tests "com.kkc.sheettracker.data.ScanStalenessTest" --tests "com.kkc.sheettracker.data.TrackerChangeMonitorEventsTest"
```

- [ ] **Step 5: Commit only this slice**

```powershell
git add app/src/main/java/com/kkc/sheettracker/data/unified/FileBackedUnifiedMetadataEngine.kt app/src/test/java/com/kkc/sheettracker/data/UnifiedFacadeParityTest.kt
git commit -m "fix: retain deep jobs until cache publish"
```

Do not stage HardwoodsWorkspaceScreen.kt, SpecialtyJobDetailScreen.kt, their tests, or the existing sheet-rip plan.

### Task 3: Finish source verification without deployment

**Files:**
- Verify: app/src/main/java/com/kkc/sheettracker/data/unified/FileBackedUnifiedMetadataEngine.kt
- Verify: app/src/test/java/com/kkc/sheettracker/data/UnifiedFacadeParityTest.kt

- [ ] **Step 1: Run full Android unit suite**

```powershell
.\gradlew.bat :app:testDebugUnitTest --rerun-tasks
.\gradlew.bat :app:compileDebugKotlin --rerun-tasks
git show --check --stat HEAD
git status --short
```

Expected: passing suite and compile, no whitespace errors, unrelated dirty files remain unstaged.

- [ ] **Step 2: Hand off live validation**

After RJW is deployed and a tablet is connected, release one small job and send one CNC remake while the app stays open. Verify each remains through two StaticCachePoller intervals, 40 seconds total, without force-stopping. Capture StaticCachePoller, KKC_SCAN, and TrackerChangeMonitor logcat only if the result disagrees with tests.

## Self-Review

- Coverage: Task 1 reproduces both symptoms; Task 2 protects both without weakening gates; Task 3 verifies source without authorizing an APK release.
- Placeholder scan: all tasks name concrete files, symbols, and commands.
- Type consistency: StaticEntryOrigin, CachedStaticEntry.origin, and mergedJobInfo are introduced before use.

## Execution Handoff

Plan complete and saved to docs/superpowers/plans/2026-07-29-cache-publication-race-app-fix.md.

1. Subagent-Driven recommended: fresh subagent per task and review between tasks.
2. Inline Execution: execute in this session with checkpoints.
