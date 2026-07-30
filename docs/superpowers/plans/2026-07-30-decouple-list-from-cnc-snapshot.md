# Decouple Jobs List from CNC Snapshot

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eliminate cache_static.json reads during jobs list display by building the list from cache_index.json + board config only, deferring `getCncSnapshot()` until job tap.

**Architecture:** `rememberCncJobsSpec.deriveJobCards()` currently iterates `scanState.snapshot.jobs` (full `Job` objects loaded via `getCncSnapshot()` which reads 300 KB cache_static.json per job). Instead, call `unifiedEngine.listJobsFromCacheOnly()` directly to get lightweight `UnifiedJobInfo` from cache_index.json (~2 KB), merge board config, and add progress from `getProgressFromIndex()`. This avoids loading cache_static.json for the list screen entirely. Full `getCncSnapshot()` only fires when user taps a job and navigates to CNC workspace.

**Tech Stack:** Kotlin, Android, Gson

## Global Constraints

- `rememberCncJobsSpec` signature must stay backward-compatible (callers in NavGraph.kt already pass `engine`)
- Jobs without cache_index.json must still appear (fallback to existing `appJobModelsByFolder` path)
- `scanState.snapshot.jobs` unchanged — CNC workspace still gets full data via `updateJobsInState()`
- No schema changes to cache_index.json or cache_static.json

---
### Task 1: Switch `rememberCncJobsSpec` to `listJobsFromCacheOnly()`

**Files:**
- Modify: `KKCSheetTracker/app/src/main/java/com/kkc/sheettracker/ui/jobs/UnifiedModeSpecs.kt`
- Modify: `KKCSheetTracker/app/src/main/java/com/kkc/sheettracker/data/unified/FileBackedUnifiedMetadataEngine.kt`
- Modify: `KKCSheetTracker/app/src/main/java/com/kkc/sheettracker/data/unified/UnifiedMetadataEngine.kt`

**Interfaces:**
- Consumes: `unifiedEngine.listJobsFromCacheOnly()` returns `Pair<List<UnifiedJobInfo>, List<String>>`, `engine.getProgressFromIndex(folderName)` returns `CacheIndexProgressSummary?`, `readJobBoardConfig()` returns `Map<String, JobBoardConfig>` (private in engine)
- Produces: `rememberCncJobsSpec.deriveJobCards()` builds cards without calling `getCncSnapshot()`

- [ ] **Step 1: Add `readJobBoardConfig()` to `UnifiedMetadataEngine` interface**

Currently `readJobBoardConfig()` is a private method in `FileBackedUnifiedMetadataEngine`. The jobs list spec needs board config (labels, isPending, boardSection) to merge into cards. Add it as a public interface method so `rememberCncJobsSpec` can call it.

In `UnifiedMetadataEngine.kt`, add:
```kotlin
fun readJobBoardConfig(): Map<String, JobBoardConfig>
```

In `FileBackedUnifiedMetadataEngine.kt`, make the private method `override fun` — it already exists with the right signature.

Also add the import:
```kotlin
import com.kkc.sheettracker.data.unified.JobBoardConfig
```

Check that `JobBoardConfig` is already a public type (it is — used in `mergedJobInfo` which takes `config: JobBoardConfig?`).

- [ ] **Step 2: Modify `rememberCncJobsSpec` to use `listJobsFromCacheOnly()`**

Change the composable to call `engine.listJobsFromCacheOnly()` and build cards from `UnifiedJobInfo` + `CacheIndexProgressSummary` instead of from `scanState.snapshot.jobs`.

```kotlin
@Composable
fun rememberCncJobsSpec(
    scanCoordinator: ScanCoordinator,
    appStateStore: AppStateStore,
    progressStore: ProgressStore,
    jobRepository: JobRepository,
    hardwoodsRepository: HardwoodsRepository,
    engine: UnifiedMetadataEngine,
    coroutineScope: CoroutineScope,
    onJobClick: (String) -> Unit,
    onView3D: (String) -> Unit,
    onViewCoverSheet: (String) -> Unit,
    onHistoryClick: ((String) -> Unit)? = null
): UnifiedJobsSpec {
    val scanState by scanCoordinator.state.collectAsState()
    val appJobModelsByFolder = appStateStore.jobUiModels.collectAsState().value.associateBy { it.folderName }
    val boardConfigs = remember { engine.readJobBoardConfig() }
    // Lightweight job list from cache_index.json — no cache_static.json reads.
    val (jobInfos, _) = remember(scanState.scanGeneration) { engine.listJobsFromCacheOnly() }
    // Filter to CNC jobs only (those with a CNC folder on disk).
    val cncJobInfos = remember(jobInfos) {
        jobInfos.filter { File(engine.basePath(), "${it.folderName}/CNC").isDirectory }
    }
    
    return remember(scanState, cncJobInfos, appJobModelsByFolder) {
        object : UnifiedJobsSpec {
            override val modeName = "jobs_cnc"
            override val scanStatus = scanCoordinator.state.map { it.status }.stateIn(coroutineScope, SharingStarted.Eagerly, ScanStatus.IDLE)
            override val scanGeneration = scanCoordinator.state.map { it.snapshot.generation }.stateIn(coroutineScope, SharingStarted.Eagerly, 0L)
            override val progressVersion = progressStore.progressVersion
            
            override fun deriveJobCards(): List<UnifiedJobUiModel> {
                return cncJobInfos.map { info ->
                    val config = boardConfigs[info.folderName]
                    val merged = mergedJobInfo(info, info.folderName, config)
                    val indexProgress = engine.getProgressFromIndex(info.folderName)
                    if (indexProgress?.cnc != null) {
                        // Fast path: build card from cache_index progress — no cache_static.json read needed
                        val cncProgress = indexProgress.cnc
                        val counts = StatusCounts(
                            total = cncProgress.totalSheets,
                            complete = cncProgress.done,
                            bad = cncProgress.bad,
                            skipped = cncProgress.skipped
                        )
                        val fraction = if (counts.total <= 0) 0f else counts.complete.toFloat() / counts.total.toFloat()
                        val materialSegments = cncProgress.materials.map { material ->
                            MaterialSegmentData(
                                materialName = material.materialName,
                                counts = material.toStatusCounts(),
                                isRemake = material.isRemake
                            )
                        }
                        makeCncJobCard(merged, counts, fraction, materialSegments,
                            hasDelivery = if (indexProgress.hasDeliverySheet) true else null,
                            has3D = if (indexProgress.has3DAssets) true else null,
                            isPinned = false,
                            onCardClick = { onJobClick(info.folderName) },
                            onView3DClick = { onView3D(info.folderName) },
                            onViewCoverSheetClick = { onViewCoverSheet(info.folderName) },
                            onHistoryClick = onHistoryClick
                        )
                    } else {
                        // Fallback: use existing appJobModelsByFolder data
                        val appModel = appJobModelsByFolder[info.folderName]
                        val counts = appModel?.counts ?: StatusCounts()
                        val fraction = appModel?.completionFraction ?: if (counts.total <= 0) 0f else counts.complete.toFloat() / counts.total.toFloat()
                        val materialSegments = appModel?.materials?.map { material ->
                            MaterialSegmentData(
                                materialName = material.materialName,
                                counts = material.counts,
                                isRemake = material.metadata?.remakeLabel != null
                            )
                        } ?: emptyList()
                        makeCncJobCard(merged, counts, fraction, materialSegments,
                            hasDelivery = null,
                            has3D = null,
                            isPinned = false,
                            onCardClick = { onJobClick(info.folderName) },
                            onView3DClick = { onView3D(info.folderName) },
                            onViewCoverSheetClick = { onViewCoverSheet(info.folderName) },
                            onHistoryClick = onHistoryClick
                        )
                    }
                }
            }
            ...
        }
    }
}
```

This requires extracting the card-building into a helper to avoid duplication:

```kotlin
private fun makeCncJobCard(
    info: UnifiedJobInfo,
    counts: StatusCounts,
    fraction: Float,
    materialSegments: List<MaterialSegmentData>,
    hasDelivery: Boolean?,
    has3D: Boolean?,
    isPinned: Boolean,
    onCardClick: () -> Unit,
    onView3DClick: (() -> Unit)?,
    onViewCoverSheetClick: (() -> Unit)?,
    onHistoryClick: ((String) -> Unit)?
): UnifiedJobUiModel {
    val badges = mutableSetOf<JobBadge>()
    if (info.isPending) badges.add(JobBadge.PENDING_DELIVERY)
    if (info.hiddenFromProduction) badges.add(JobBadge.HIDDEN_IN_PRODUCTION)

    return UnifiedJobUiModel(
        folderName = info.folderName,
        jobNumber = info.jobNumber,
        jobName = info.jobName,
        isPinned = isPinned,
        isPending = info.isPending,
        boardSection = info.boardSection,
        lineupPosition = info.lineupPosition,
        badges = badges,
        labels = info.labels,
        historyCount = null,
        progressStyle = ProgressStyle.Cnc(
            counts = counts,
            fraction = fraction,
            materialSegments = materialSegments
        ),
        onCardClick = onCardClick,
        onView3DClick = onView3DClick,
        onViewCoverSheetClick = onViewCoverSheetClick,
        onHistoryClick = onHistoryClick
    )
}
```

Note: we can no longer use `JobBrowserItemUiState.toUnifiedModel()` because that takes a `Job` object, not `UnifiedJobInfo`. Instead, build `UnifiedJobUiModel` directly via `makeCnfJobCard`.

Also add imports at the top of the file:
```kotlin
import com.kkc.sheettracker.data.unified.UnifiedJobInfo
import java.io.File
```

- [ ] **Step 3: Add `basePath()` and `readJobBoardConfig()` to `UnifiedMetadataEngine` interface**

In `UnifiedMetadataEngine.kt`, add:
```kotlin
fun basePath(): String
fun readJobBoardConfig(): Map<String, JobBoardConfig>
```

In `FileBackedUnifiedMetadataEngine.kt`, add:
```kotlin
override fun basePath(): String = baseDir.absolutePath
```

And make `readJobBoardConfig()` an `override fun`. It already exists as a private method with the right return type.

Also export `JobBoardConfig` if it's not already public. Check if it's a data class or if it needs to be made accessible. If it's private, make it internal or public.

Let me check the current visibility of `JobBoardConfig`.

- [ ] **Step 4: Verify build compiles**

Run: `.\gradlew.bat :app:compileDebugKotlin --no-daemon`
Expected: BUILD SUCCESSFUL, zero warnings related to our changes

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/jobs/UnifiedModeSpecs.kt app/src/main/java/com/kkc/sheettracker/data/unified/UnifiedMetadataEngine.kt app/src/main/java/com/kkc/sheettracker/data/unified/FileBackedUnifiedMetadataEngine.kt
git commit -m "perf: decouple jobs list from cnc snapshot, use cache_index for cards"
```

---
### Task 2: Remove `getCncSnapshot()` from `scanJobsFromCacheOnly()`

**Files:**
- Modify: `KKCSheetTracker/app/src/main/java/com/kkc/sheettracker/data/ScanCoordinator.kt`

**Interfaces:**
- Consumes: `listJobsFromCacheOnly()` is already called in `scanJobsFromCacheOnly()`
- Produces: `scanState.snapshot.jobs` still populated but only via `updateJobsInState()` (deep load after tap or background refresh)

- [ ] **Step 1: Modify `scanJobsFromCacheOnly()` to skip `getCncSnapshot()`**

The initial scan no longer needs to load full `Job` objects. The jobs list builds from `listJobsFromCacheOnly()` directly. Remove the `getCncSnapshot()` call:

```kotlin
private fun scanJobsFromCacheOnly(): ScanResult {
    if (!baseDir.exists() || !baseDir.isDirectory)
        return ScanResult(emptyList(), emptyList(), emptyList(), emptyList())
    val (jobInfos, needsDeepLoad) = unifiedEngine.listJobsFromCacheOnly()
    // Jobs list builds from listJobsFromCacheOnly() directly — no need to load
    // full CNC snapshots here. Full data loads only on job tap via getCncSnapshot().
    return ScanResult(emptyList(), emptyList(), emptyList(), needsDeepLoad)
}
```

The `jobs` list in `ScanResult` is now empty. The `scanState.snapshot.jobs` will be populated when `updateJobsInState()` fires after deep loads, or when user taps a job.

However, check if anything else reads `scanState.snapshot.jobs` before a deep load completes. If the CNC workspace screen expects jobs to be there immediately, we need a different approach. Let me check...

Actually, `rememberCncJobsSpec` no longer uses `scanState.snapshot.jobs` (Task 1), but the actual CNC workspace screen (when user navigates to a job) might need the snapshot. Let me verify this.

- [ ] **Step 2: Verify CNC workspace screen still loads**

Check that the CNC workspace screen (opened when user taps a job card) calls `getCncSnapshot()` directly rather than depending on `scanState.snapshot.jobs` being pre-populated.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/data/ScanCoordinator.kt
git commit -m "perf: remove getCncSnapshot from initial scan, defer to job tap"
```

---
### Task 3: Build release APK and install to tablet

**Files:**
- No code changes

- [ ] **Step 1: Build and deploy**

Run: `.\deploy_update.ps1`
Expected: SUCCESS, version 8.0.3 published

- [ ] **Step 2: Timing verification**

After app launches and completes initial scan, verify:
- Logcat shows `listJobsFromCacheOnly()` runs (trace)
- Logcat does NOT show `getCncSnapshot()` calls during list display
- Cards show progress bars and material segments from cache_index

---
## Self-Review

| Requirement | Task coverage |
|---|---|
| List builds from cache_index.json + board config | Task 1 (`deriveJobCards` uses `listJobsFromCacheOnly` + `getProgressFromIndex`) |
| No cache_static.json read during list display | Task 2 (removed `getCncSnapshot()` from `scanJobsFromCacheOnly`) |
| CNC workspace still loads full data on tap | Task 2 step 2 (verify workspace calls `getCncSnapshot` directly) |
| Fallback when cache_index.json missing | Task 1 (existing `appJobModelsByFolder` path) |
| Backward compat interface | Task 1 (`rememberCncJobsSpec` signature unchanged) |
