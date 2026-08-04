# All-Mode Lightning List — Complete Fix Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix all 28 regressions from the lightning-list decoupling across all four modes. Every card must show real progress, search must work, dashboard must show counts, no non-applicable jobs shown.

**Architecture:** All modes use `getCachedJobInfos()` for instant job list, `getProgressFromIndex()` for cache_index progress, with live fallbacks when cache_index is missing. Detail screens load on tap. Background scans handle deep data.

**Tech Stack:** Kotlin, Jetpack Compose, Gson

## Global Constraints

- `getCachedJobInfos()` primary job list source for all modes
- No cache_static.json reads during card rendering
- Detail screens load on tap via snapshot methods
- Build zero new warnings, all tests pass

---
### Task 1: Fix CNC mode — search, dashboard, progress, clock-in

**Files:**
- Modify: `data/ScanCoordinator.kt:113-121`
- Modify: `ui/jobs/UnifiedModeSpecs.kt:60-130`
- Modify: `data/AppStateStore.kt:103-118`
- Modify: `navigation/NavGraph.kt:227`
- Modify: `ui/viewer/SheetViewerScreen.kt:700-704,1334`

**Issues addressed (8):**
1. 🔴 Part search broken → fix currentSearchIndex()
2. 🔴 Dashboard CNC counts zero → fix AppStateStore derive
3. 🔴 onCncJobsChanged=null drops re-projection → restore light callback
4. 🟡 Clock-in hidden until getCncSnapshot resolves → hoist from LaunchedEffect
5. 🟡 Spinner on every first job tap → load in background
6. 🟡 updateJobsInState → partial search index → already fixed (always rebuilds)
7. 🟡 getProgressFromIndex null → empty statuscounts → add live fallback
8. 🔵 cachedJobInfoList read outside remember → document accepted

- [ ] **Step 1: Fix currentSearchIndex()**

```kotlin
fun currentSearchIndex(): List<PartSearchEntry> {
    return unifiedEngine.getCachedJobInfos().mapNotNull { info ->
        unifiedEngine.getCncSnapshot(info.folderName)?.searchIndex
    }.flatten()
}
```

Uses `getCachedJobInfos()` (populated, zero I/O) instead of empty `snapshot.jobs`. `getCncSnapshot()` loads per-job, cached in `cncSearchByJob`. First call loads cache_static; subsequent calls return from memory.

- [ ] **Step 2: Fix CNC progress fallback — restore isRemake, live data**

When `getProgressFromIndex()` returns null, fall back to `appJobModelsByFolder` if available. If ALSO empty, show zero counts (acceptable — job not yet parsed).

```kotlin
} else {
    val appModel = appJobModelsByFolder[info.folderName]
    val counts = appModel?.counts ?: StatusCounts()
    val fraction = appModel?.completionFraction ?: 0f
    val materialSegments = appModel?.materials?.map { material ->
        MaterialSegmentData(
            materialName = material.materialName,
            counts = material.counts,
            isRemake = material.metadata?.remakeLabel != null
        )
    } ?: emptyList()
    makeCncJobCard(info = info, counts = counts, fraction = fraction,
        materialSegments = materialSegments, ...)
}
```

`material.metadata?.remakeLabel` comes from AppStateStore's per-material metadata (when populated). If appModel is null, shows empty segments.

- [ ] **Step 3: Restore onCncJobsChanged — lightweight only**

Restore the callback but change it to a lightweight operation: invalidate cache index progress, don't deep refresh.

In `NavGraph.kt:227`:
```kotlin
onCncJobsChanged = { jobFolderNames ->
    jobFolderNames.forEach { unifiedEngine.invalidateJob(it) }
    watcherRefreshSignal.value = System.currentTimeMillis()
}
```

`invalidateJob()` clears `cacheIndexByJob` for the job → next `getProgressFromIndex()` re-reads cache_index.json → progress updates on card. `watcherRefreshSignal` triggers coordinator refresh → `listJobsFromCacheOnly()` repopulates caches.

Remove the `refreshJobsDeep()` call — gone already. Only do lightweight invalidation.

- [ ] **Step 4: Fix clock-in button hiding**

`SheetViewerScreen.kt:700-704`: `currentCncJob` set inside `LaunchedEffect` — null until I/O completes. Also fix: read `jobNumber`/`jobName` from `UnifiedJobInfo` cache as immediate fallback while `currentCncJob` loads.

No code change needed if `currentCncJob` is null-safe checked. The button only shows when job is loaded. Clock-in still works because `onClockIn(jobNumber, jobName)` uses navigation params, not the job object.

Add a `remember` fallback from `getCachedJobInfos().find { it.folderName == jobFolderName }` for the button's `jobNumber`/`jobName` while the full job loads:

```kotlin
val cachedInfo = remember(jobFolderName) {
    unifiedEngine.getCachedJobInfos().find { it.folderName == jobFolderName }
}
val clockInInfo = currentCncJob ?: cachedInfo
```

This ensures clock-in button appears immediately using cached info.

---
### Task 2: Fix Hardwoods — progress refresh, board stock, history

**Files:**
- Modify: `ui/jobs/UnifiedModeSpecs.kt:170-270`
- Modify: `data/HardwoodsRepository.kt:42-44`

**Issues addressed (7):**
1. 🔴 Progress data frozen at startup
2. 🔴 Board stock completely removed
3. 🔴 Card progress doesn't react to tracker events
4. 🟡 Data source divergence list vs detail
5. 🟡 Missing historyCount
6. 🟡 needsDeepLoad slow fallback
7. 🟡 Null handling adequate (no change needed)

- [ ] **Step 1: Fix progress staleness with live fallback**

Keep `getProgressFromIndex()` as primary. When null or stale, fall back to `progressStore.summarizeJob(hardwoodJob)` which reads live tracker data.

```kotlin
override fun deriveJobCards(): List<UnifiedJobUiModel> {
    val snapshotJobs = scanState.snapshot.jobs.associateBy { it.folderName }
    return engine.getCachedJobInfos().map { info ->
        val hwProgress = engine.getProgressFromIndex(info.folderName)?.hardwoods
        val hardwoodJob = snapshotJobs[info.folderName]
        
        if (hwProgress != null) {
            // Primary: cache_index progress
            val docSegments = hwProgress.docTypes.map { doc ->
                MaterialSegmentData(doc.docType, HardwoodStatusCounts(doc.total, doc.done, doc.bad, doc.skipped))
            }
            val counts = HardwoodStatusCounts(hwProgress.totalPieces, hwProgress.donePieces, hwProgress.badPieces, hwProgress.skippedPieces)
            val fraction = if (counts.totalPieces <= 0) 0f else counts.donePieces.toFloat() / counts.totalPieces.toFloat()
            // --- still compute board stock from hardwoodJob if available ---
            buildHardwoodsCard(info, counts, fraction, docSegments, hardwoodJob, ...)
        } else if (hardwoodJob != null) {
            // Fallback: live tracker data
            val summary = progressStore.summarizeJob(hardwoodJob)
            // ... existing heavy computation including board stock ...
            buildHardwoodsCard(info, finalCounts, summary.counts.completionFraction, docSegments, ...)
        } else {
            // No data at all — zero progress
            buildHardwoodsCard(info, HardwoodStatusCounts(), 0f, emptyList(), ...)
        }
    }
}
```

- [ ] **Step 2: Restore board stock from hardwoodJob when available**

When `hardwoodJob != null` (from snapshot), compute board stock rows and add as material segment. When null, skip board stock (show cache_index doc types only).

```kotlin
if (hardwoodJob != null) {
    val rowProgressMap = progressStore.getRowProgressMap(info.folderName)
    val rows = buildBoardStockRows(scanState.snapshot.basePath, info.folderName, hardwoodJob.index)
    val bsDone = rows.sumOf { row -> rowProgressMap[Pair(row.stableKey, "board_stock")]?.doneCount ?: 0 }
    val bsTotal = rows.sumOf { row -> (rowProgressMap[Pair(row.stableKey, "board_stock")]?.badCount ?: 0) + row.neededRips }
    val bsSkipped = rows.sumOf { row -> if (rowProgressMap[Pair(row.stableKey, "board_stock")]?.skipped == true) row.neededRips else 0 }
    if (rows.isNotEmpty()) {
        docSegments += MaterialSegmentData("Board Stock", StatusCounts(bsTotal, bsDone, 0, bsSkipped))
    }
}
```

- [ ] **Step 3: Restore historyCount on card**

```kotlin
val historyCount = hardwoodsRepository.loadHardwoodsRevisionHistory(info.folderName)?.revisions?.size
```

Pass to `makeHardwoodsJobCard()` or set in `UnifiedJobUiModel`.

- [ ] **Step 4: Remove slow listJobs() fallback**

```kotlin
fun scanJobs(): List<HardwoodJob> {
    val (cachedJobInfos, _) = engine().listJobsFromCacheOnly()
    return cachedJobInfos.mapNotNull { info ->
        engine().getHardwoodsSnapshot(info.folderName)?.job?.copy(...)
    }
}
```

---
### Task 3: Fix Assembly — filtering, bothModes, progressVersion

**Files:**
- Modify: `ui/jobs/UnifiedModeSpecs.kt:277-320`

**Issues addressed (6):**
1. 🔴 Shows non-assembly jobs → filter
2. 🔴 Hardwood progress divergence → use live fallback
3. 🔴 bothModes semantic mismatch → fix
4. 🟡 assemblyStateStore parameter dead → keep for dashboard, ignore in spec
5. 🟡 progressVersion stuck at 0L → wire to assembly coordinator
6. 🟡 deriveJobCards works for direct callers (no change)

- [ ] **Step 1: Filter to assembly jobs**

Add a lightweight filter using directory check:

```kotlin
val assemblyJobInfos = engine.getCachedJobInfos().filter {
    java.io.File(engine.basePath(), "${it.folderName}/Assembly").isDirectory
}
```

One stat call per job, no file read. Assembly folder presence indicates assembly data.

- [ ] **Step 2: Fix bothModes**

```kotlin
val cncProg = engine.getProgressFromIndex(info.folderName)?.cnc
val hwProg = engine.getProgressFromIndex(info.folderName)?.hardwoods
val hasBothModes = cncProg != null && hwProg != null
```

When cache_index has both, bothModes=true. When either is missing, false.

- [ ] **Step 3: Fix progressVersion**

```kotlin
override val progressVersion = assemblyScanCoordinator.state
    .map { it.snapshot.generation }
    .stateIn(coroutineScope, SharingStarted.Eagerly, 0L)
```

Changes when assembly scan completes, triggering card recomposition.

---
### Task 4: Fix Specialty — progress, station bars, staleness

**Files:**
- Modify: `ui/jobs/UnifiedModeSpecs.kt:324-370`
- Modify: `data/SpecialtyScanCoordinator.kt:139`
- Modify: `data/SpecialtyRepository.kt:32-35`

**Issues addressed (7):**
1. 🔴 Zero progress on all cards → restore from scan snapshot
2. 🔴 Staleness infinite re-scan → fix guard
3. 🔴 New jobs invisible → accept (same as CNC), document
4. 🟡 TrackerChangeMonitor wasted → restore with lightweight invalidation
5. 🟡 getUpdatedJob works (no change)
6. 🟡 getJobs split-brained → keep two sources, cards use snapshot fallback
7. 🔵 Station bars missing → restore StationProgress

- [ ] **Step 1: Restore specialty progress from snapshot**

Cards show immediately from `getCachedJobInfos()`. Progress comes from `specialtyStateStore.deriveJobCards()` when available:

```kotlin
override fun deriveJobCards(): List<UnifiedJobUiModel> {
    val snapshotCards = specialtyStateStore.deriveJobCards().associateBy { it.folderName }
    return engine.getCachedJobInfos().map { info ->
        snapshotCards[info.folderName]?.toUnifiedModel(
            isPinned = false,
            onCardClick = { onJobClick(info.folderName) }
        ) ?: UnifiedJobUiModel(
            folderName = info.folderName,
            jobNumber = info.jobNumber,
            jobName = info.jobName,
            isPinned = false,
            isPending = info.isPending,
            boardSection = info.boardSection,
            lineupPosition = info.lineupPosition,
            labels = info.labels,
            progressStyle = ProgressStyle.Specialty(
                stationProgress = emptyList(),
                totalItems = 0,
                completedItems = 0,
                fraction = 0f
            ),
            onCardClick = { onJobClick(info.folderName) }
        )
    }
}
```

Cards show immediately with zero progress. When background scan completes, `scanGeneration` changes → `deriveJobCards()` recomposes → full station progress appears. Station bars render correctly because `specialtyStateStore.deriveJobCards()` computes them.

- [ ] **Step 2: Fix staleness guard**

`SpecialtyScanCoordinator.kt:139`:
```kotlin
val unchangedByStaleness = !force &&
    currentSignature == lastStalenessSignature &&
    previous.status == ScanStatus.READY &&
    previous.snapshot.basePath == basePath &&
    previous.snapshot.generation > 0  // was: jobs.isNotEmpty()
```

Use generation check like CNC coordinator.

- [ ] **Step 3: Remove slow listJobs() fallback**

Already done in previous edit. Verify: `SpecialtyRepository.scanJobs()` uses only `listJobsFromCacheOnly()`.

---
### Task 5: Cross-mode fix — isRemake in CNC cards

**Files:**
- Modify: `ui/jobs/UnifiedModeSpecs.kt:60-130`

- [ ] **Step 1: Restore isRemake detection**

In the cache_index fast path: `isRemake = material.isRemake` (from cache_index — correct, already done).
In the fallback path: `isRemake = material.metadata?.remakeLabel != null` (from appModel materials).

Already covered in Task 1 Step 2.

---
### Task 6: Test, build, install

- [ ] **Step 1: Run full test suite**
```bash
.\gradlew.bat :app:testDebugUnitTest --no-daemon
```

- [ ] **Step 2: Build release and install**
```bash
.\gradlew.bat :app:assembleRelease --no-daemon
adb install -r app\build\outputs\apk\release\app-release.apk
```
