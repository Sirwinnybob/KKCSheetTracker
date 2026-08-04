# Final Fixes — All Adversarial Findings

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close every open finding from the 4-mode adversarial reviews after lightning-list / cache-index strip. Every finding listed below is either **FIX**, **BY-DESIGN**, or **ACCEPT** with evidence.

**Architecture:** List screens stay index-first (`getCachedJobInfos` + `getProgressFromIndex`). Live tracker data wins when available. Specialty reads small admin/tracker JSON directly — never waits on empty snapshot. No background full-job scans restored.

**Tech Stack:** Kotlin, Jetpack Compose, StateFlow, Gson

**Repo root (tablet):** `C:\Scripts\KKCSheetTracker`

---

## Global Constraints

- Do **not** restore background `scanJobs()` full loads for CNC / HW / ASM / Specialty
- `scanJobsFromCacheOnly` returning empty jobs is intentional
- All unit tests pass (`.\gradlew.bat :app:testDebugUnitTest --no-daemon`)
- Zero new compile warnings
- Deployment-gate / cache_index schema unchanged on RJW side

---

## Finding Inventory (complete)

Every agent finding. Disposition is binding — do not "fix" BY-DESIGN / ACCEPT items.

### CNC

| ID | Finding | Disposition | Why |
|----|---------|-------------|-----|
| C1 | `currentSearchIndex()` calls `getCncSnapshot()` for every job | **FIX** | First part-search loads every `cache_static.json`. Acceptable if lazy+cached, but must not block UI and must use cache. Improve: build/return from `cncSearchByJob` cache only; warm on demand or after first open — do **not** re-introduce full scan into refresh. |
| C2 | Card progress frozen on cache_index | **FIX** | `deriveJobCards` always takes index branch when `cnc != null`; never live ProgressStore after tracker write. |
| C3 | `onCncJobsChanged` invalidate + re-read same stale `cache_index.json` | **FIX** | Same root as C2. Invalidate alone cannot refresh progress; need live tracker path. |
| C4 | `needsDeepLoad` discarded after cache-only scan | **BY-DESIGN** | Jobs appear via `cache_index` jobInfo. Full CNC data loads on tap via `getCncSnapshot()`. RJW writes index for deployed jobs. |
| C5 | `cachedJobInfoList` not cleared in `invalidateJob` | **FIX** | Stale list until next `listJobsFromCacheOnly()`. Clear list (or null it) on per-job invalidate + full clear. |
| C6 | Dashboard recent materials include `done == 0` | **ACCEPT (already fixed)** | Code already filters `mat.done > 0 && mat.done < mat.totalSheets`. Verify only. |
| C7 | Thumbnail path wrong (full PDF fallback) | **ACCEPT (already fixed)** | Path `.metadata/.thumbs/{stem}_p001.png` resolved relative to PDF parent (`CNC/`). Verify on device after install. |

### Hardwoods

| ID | Finding | Disposition | Why |
|----|---------|-------------|-----|
| H1 | `scanJobsFromCacheOnly` returns empty `jobs` / discards jobInfos | **BY-DESIGN** | List uses `getCachedJobInfos()`. Snapshot intentionally empty. |
| H2 | Board stock segment only when `hardwoodJob != null` | **FIX** | Snapshot empty ⇒ board stock never on list cards. `loadBoardStock(folderName)` needs no snapshot job — ungated call. |
| H3 | Snapshot live-fallback path never reached when index present | **FIX** | Same class as C2/C3: prefer live `summarizeJob` when snapshot job exists; else index; else zero. After strip, snapshot usually empty — so primary live path for list = ProgressStore against lightweight job OR keep index + separate board-stock. Prefer: index for piece counts + always attempt board stock via `loadBoardStock`. For live piece counts after tracker write: when index exists, still overlay live row progress if ProgressStore cache warm — see Task 2. |
| H4 | Card progress doesn't react to tracker (if only index used) | **FIX** | Covered by H3 + existing `progressVersion = progressStore.progressVersion` (already wired). Ensure derive recomputes with live data when store invalidates. |

### Assembly

| ID | Finding | Disposition | Why |
|----|---------|-------------|-----|
| A1 | `progressVersion` = assembly scan generation only | **FIX** | Tracker writes (CNC/HW) don't bump assembly generation → cards stale until refresh. Combine CNC + HW progress versions. |
| A2 | Duplication vs `AssemblyStateStore` | **ACCEPT** | Spec is list source of truth; store still used by dashboard/detail. No merge this pass. |
| A3 | Shows non-assembly jobs / bothModes wrong | **ACCEPT (already fixed)** | `bothModes = cncProg != null && hwProg != null` from index. Filtering by Assembly dir was planned earlier — verify whether still needed; if all jobs show in assembly list, add filter task. |

### Specialty

| ID | Finding | Disposition | Why |
|----|---------|-------------|-----|
| S1 | Cards always zero progress | **FIX** | Snapshot empty; `specialtyStateStore.deriveJobCards()` empty. Read `specialtyProgressStore.loadResolvedItems()` in list derive. |
| S2 | `progressVersion = MutableStateFlow(0L)` dead | **FIX** | Wire to `specialtyProgressStore.progressVersion` (or state store that forwards it). |
| S3 | Staleness guard uses `generation > 0` not `jobs.isNotEmpty()` | **ACCEPT** | Correct for empty-snapshot architecture. Matches CNC coordinator. |
| S4 | Background specialty scan stripped | **BY-DESIGN** | List must not wait on snapshot; progress from small JSONs only. |

### Cross-cutting / dashboard

| ID | Finding | Disposition | Why |
|----|---------|-------------|-----|
| X1 | Assembly dashboard / specialty dashboard still snapshot-oriented | **FIX if broken** | After S1/S2, specialty dashboard using `specialtyStateStore.getJobs()` may still be empty — audit and point at index + progress store. |
| X2 | CNC/HW dashboard counts from index only (stale after shop floor work) | **FIX** | Same live-overlay pattern as list cards, or document "dashboard refreshes when RJW rewrites index" — **prefer live overlay** for consistency with floor tablets. |

---

## Disposition summary

| Action | IDs |
|--------|-----|
| **FIX now** | C1, C2, C3, C5, H2, H3, H4, A1, S1, S2, X1, X2 |
| **BY-DESIGN** | C4, H1, S4 |
| **ACCEPT / verify** | C6, C7, A2, A3, S3 |

---

### Task 1: CNC live progress + invalidate list cache (C2, C3, C5)

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/jobs/UnifiedModeSpecs.kt` (`rememberCncJobsSpec`)
- Modify: `app/src/main/java/com/kkc/sheettracker/data/unified/FileBackedUnifiedMetadataEngine.kt` (`invalidateJob`, `clearAll` if present)
- Modify: `app/src/main/java/com/kkc/sheettracker/data/unified/UnifiedMetadataEngine.kt` only if interface needs doc

**Root cause:** Index branch always wins. Tracker invalidation drops memory cache then reloads same RJW-written JSON. Live counts live in `ProgressStore` once a job has been opened / indexed.

- [ ] **Step 1: Prefer live ProgressStore material counts when job index is warm**

In `rememberCncJobsSpec.deriveJobCards()`:

```kotlin
override fun deriveJobCards(): List<UnifiedJobUiModel> {
    return jobInfos.map { info ->
        val indexProgress = engine.getProgressFromIndex(info.folderName)
        val liveCounts = progressStore.getJobStatusCountsOrNull(info.folderName)
        // If ProgressStore has a warm job index (opened or previously projected), use it.
        // Else fall back to cache_index. Else zeros via appModel / empty.
        when {
            liveCounts != null && liveCounts.total > 0 -> {
                val materials = progressStore.getMaterialSegmentsOrEmpty(info.folderName)
                // isRemake: keep from index materials by name when possible
                makeCncJobCard(...)
            }
            indexProgress?.cnc != null -> {
                // existing index path
            }
            else -> {
                // existing appModel fallback
            }
        }
    }
}
```

If `getJobStatusCountsOrNull` does not exist, add thin helpers on `ProgressStore` that return null when job cache missing (do **not** parse PDFs on list path).

Alternative if ProgressStore API is awkward: keep using `appJobModelsByFolder` only when `counts.total > 0`, **else** index — but AppStateStore derive currently builds from empty snapshot materials, so this alone is insufficient. **Must** use ProgressStore directly for live path.

- [ ] **Step 2: Confirm `onCncJobsChanged` still invalidates + bumps refresh**

`NavGraph.kt` already:
```kotlin
onCncJobsChanged = { jobFolderNames ->
    jobFolderNames.forEach { scanCoordinator.unifiedEngine.invalidateJob(it) }
    watcherRefreshSignal.value = System.currentTimeMillis()
}
```
Keep. After Step 1, refresh recomposes cards; live branch uses ProgressStore (already invalidated by `TrackerChangeMonitor` via `progressStore.invalidateJobIndexes`).

- [ ] **Step 3: Clear `cachedJobInfoList` on invalidate**

```kotlin
override fun invalidateJob(jobFolderName: String) {
    staticByJob.remove(jobFolderName)
    trackerByJob.remove(jobFolderName)
    cncSearchByJob.remove(jobFolderName)
    cacheIndexByJob.remove(jobFolderName)
    // Force next listJobsFromCacheOnly / getCachedJobInfos consumers to refresh
    cachedJobInfoList = cachedJobInfoList.filterNot { it.folderName == jobFolderName }
}
```

Also clear fully in any `clearCaches()` path.

- [ ] **Step 4: Compile + focused tests**

```bash
.\gradlew.bat :app:compileDebugKotlin --no-daemon
.\gradlew.bat :app:testDebugUnitTest --no-daemon
```

Expected: BUILD SUCCESSFUL

---

### Task 2: CNC part search lazy path (C1)

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/data/ScanCoordinator.kt` (`currentSearchIndex`)
- Possibly: `JobRepository.kt` callers

**Root cause:** Search maps every cached job through `getCncSnapshot` → loads all static caches on first search.

- [ ] **Step 1: Prefer already-cached search indexes; load missing lazily**

```kotlin
fun currentSearchIndex(): List<PartSearchEntry> {
    return unifiedEngine.getCachedJobInfos().flatMap { info ->
        unifiedEngine.getCachedCncSearchIndex(info.folderName)
            ?: unifiedEngine.getCncSnapshot(info.folderName)?.searchIndex.orEmpty()
    }
}
```

If no `getCachedCncSearchIndex`, add engine method that returns `cncSearchByJob[folder]?.index` without I/O.

- [ ] **Step 2: Optional — warm search index only for jobs user has opened**

Document: cold search may still load static for jobs never opened. Acceptable vs full background scan. Do **not** restore refresh-time full index build.

- [ ] **Step 3: Verify**

```bash
.\gradlew.bat :app:compileDebugKotlin --no-daemon
```

Manual: open CNC → part search still finds parts for jobs previously opened; first global search may hitch once (document).

---

### Task 3: Hardwoods board stock + live overlay (H2, H3, H4)

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/jobs/UnifiedModeSpecs.kt` (`rememberHardwoodsJobsSpec`)

- [ ] **Step 1: Ungate board stock from snapshot job**

In index branch, replace:
```kotlin
if (hardwoodJob != null) {
    val rowProgressMap = ...
    val bsRows = hardwoodsRepository.loadBoardStock(info.folderName)
    ...
}
```
with:
```kotlin
// Board stock is small JSON; does not need hardwood snapshot job
val rowProgressMap = progressStore.getRowProgressMap(info.folderName)
val bsRows = hardwoodsRepository.loadBoardStock(info.folderName)
if (bsRows.isNotEmpty()) {
    docSegments += MaterialSegmentData("Board Stock", StatusCounts(...))
}
```

- [ ] **Step 2: Live piece-count overlay when ProgressStore warm**

When `hardwoodJob != null` (rare after strip) keep existing `summarizeJob` path.

When only index present: keep index docTypes for pieces, but board stock always from Step 1 using live `rowProgressMap` (reacts to `progressVersion`).

If ProgressStore exposes job-level summary without full HardwoodJob, prefer that over stale index `donePieces` when cache warm — mirror CNC Task 1 pattern.

- [ ] **Step 3: Verify**

```bash
.\gradlew.bat :app:compileDebugKotlin --no-daemon
.\gradlew.bat :app:testDebugUnitTest --tests "*Hardwoods*" --no-daemon
```

---

### Task 4: Assembly progressVersion (A1) + assembly job filter check (A3)

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/jobs/UnifiedModeSpecs.kt` (`rememberAssemblyJobsSpec`)
- Modify: `app/src/main/java/com/kkc/sheettracker/navigation/NavGraph.kt` (call sites)

- [ ] **Step 1: Inject progress stores**

```kotlin
fun rememberAssemblyJobsSpec(
    ...
    progressStore: ProgressStore,
    hardwoodsProgressStore: HardwoodsProgressStore,
    ...
)
```

- [ ] **Step 2: Combine versions**

```kotlin
override val progressVersion = combine(
    progressStore.progressVersion,
    hardwoodsProgressStore.progressVersion
) { cnc, hw -> cnc + hw }
    .stateIn(coroutineScope, SharingStarted.Eagerly, 0L)
```

Imports: `kotlinx.coroutines.flow.combine`

- [ ] **Step 3: Update both NavGraph call sites** with real store instances.

- [ ] **Step 4: Verify A3 — assembly list filter**

If assembly list shows every CNC job with empty HW, filter:

```kotlin
val assemblyJobInfos = jobInfos.filter { info ->
    File(engine.basePath(), "${info.folderName}/Assembly").isDirectory
}
```

Only add if product still wants Assembly-folder-only list. Confirm with existing UX (assembly often shows all production jobs with CNC+HW bars). **Default: no filter** unless current product requires it — note decision in PR.

- [ ] **Step 5: Verify**

```bash
.\gradlew.bat :app:compileDebugKotlin --no-daemon
```

---

### Task 5: Specialty cards + progressVersion (S1, S2, X1)

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/jobs/UnifiedModeSpecs.kt` (`rememberSpecialtyJobsSpec`)
- Modify: `app/src/main/java/com/kkc/sheettracker/navigation/NavGraph.kt`
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/dashboard/UnifiedModeDashboardScreen.kt` (specialty section if empty)

- [ ] **Step 1: Inject `SpecialtyProgressStore`**

```kotlin
fun rememberSpecialtyJobsSpec(
    ...
    specialtyProgressStore: SpecialtyProgressStore,
    ...
)
```

- [ ] **Step 2: Derive cards from `loadResolvedItems`**

```kotlin
override val progressVersion = specialtyProgressStore.progressVersion

override fun deriveJobCards(): List<UnifiedJobUiModel> {
    return jobInfos.map { info ->
        val resolved = runCatching {
            specialtyProgressStore.loadResolvedItems(info.folderName)
        }.getOrElse { emptyList() }
        val totalItems = resolved.size
        val completedItems = resolved.count { it.isComplete }
        val fraction = if (totalItems <= 0) 0f else completedItems.toFloat() / totalItems.toFloat()
        val stationProgress = resolved
            .groupBy { it.item.station }
            .map { (station, items) ->
                StationProgress(
                    station = station, // use model type already on SpecialtyResolvedItem
                    totalItems = items.size,
                    completedItems = items.count { it.isComplete }
                )
            }
        UnifiedJobUiModel(
            folderName = info.folderName,
            jobNumber = info.jobNumber,
            jobName = info.jobName,
            isPinned = false,
            isPending = info.isPending,
            boardSection = info.boardSection,
            lineupPosition = info.lineupPosition,
            labels = info.labels,
            progressStyle = ProgressStyle.Specialty(
                stationProgress = stationProgress,
                totalItems = totalItems,
                completedItems = completedItems,
                fraction = fraction
            ),
            onCardClick = { onJobClick(info.folderName) },
            onView3DClick = onView3D?.let { cb -> { cb(info.folderName) } },
            onViewCoverSheetClick = onViewCoverSheet?.let { cb -> { cb(info.folderName) } }
        )
    }
}
```

Match real `StationProgress` / station field types in codebase (read `SpecialtyResolvedItem` before coding).

- [ ] **Step 3: NavGraph — pass `specialtyProgressStore` at all specialty spec call sites**

- [ ] **Step 4: Specialty dashboard (X1)**

If `UnifiedModeDashboardScreen` specialty branch still uses `specialtyStateStore.getJobs()` on empty snapshot, switch to `engine.getCachedJobInfos()` + same resolved-item counts.

- [ ] **Step 5: Verify**

```bash
.\gradlew.bat :app:compileDebugKotlin --no-daemon
.\gradlew.bat :app:testDebugUnitTest --tests "*Specialty*" --no-daemon
```

---

### Task 6: Dashboard live overlay (X2) + verify C6/C7

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/dashboard/UnifiedModeDashboardScreen.kt`

- [ ] **Step 1: CNC dashboard**

Where totals/recent materials built from `getProgressFromIndex` only, overlay ProgressStore warm counts when available (same helpers as Task 1). Keep index as cold-start source.

- [ ] **Step 2: Hardwoods dashboard**

Same pattern for piece totals.

- [ ] **Step 3: Verify C6 filter still present**

Assert `mat.done > 0 && mat.done < mat.totalSheets` remains.

- [ ] **Step 4: Verify C7 thumb path**

`thumbnailPath = ".metadata/.thumbs/${stem}_p001.png"` resolved via `File(pdfFile.parentFile, relative)` → `job/CNC/.metadata/.thumbs/...`. No code change unless device proves wrong stem naming.

- [ ] **Step 5: Compile**

```bash
.\gradlew.bat :app:compileDebugKotlin --no-daemon
```

---

### Task 7: Full test, release, install

- [ ] **Step 1: Full unit tests**

```bash
.\gradlew.bat :app:testDebugUnitTest --no-daemon
```

Expected: all pass (baseline was 570).

- [ ] **Step 2: Release APK**

```bash
.\gradlew.bat :app:assembleRelease --no-daemon
```

- [ ] **Step 3: Install on tablet**

```bash
adb install -r app\build\outputs\apk\release\app-release.apk
```

- [ ] **Step 4: Manual smoke (on device)**

| Check | Pass criteria |
|-------|----------------|
| CNC list | Cards show non-zero progress from index on cold start |
| CNC mark sheet done | Card counts update without waiting for RJW |
| CNC part search | Finds parts; no multi-minute freeze |
| CNC dashboard thumbs | Pre-rendered PNG shows; not full-PDF spin forever |
| CNC recent materials | No materials with 0 done |
| HW list | Progress + Board Stock segment when board stock JSON exists |
| HW mark row done | Card/board stock updates |
| Assembly list | CNC/HW bars update when either mode progresses |
| Specialty list | Non-zero station progress from admin JSON |
| Specialty complete item | Card fraction updates |

---

## Explicit non-goals (do not undo)

| Item | Reason |
|------|--------|
| Restore CNC/HW/ASM/Specialty background full job lists into snapshot | Defeats lightning list |
| Put specialty progress into `cache_index.json` | Tablet-owned; small JSON already on disk |
| Change RJW `cache_index` / `deployment_gate` schema | Android contract stable |
| Merge AssemblyStateStore into jobs spec | A2 accepted |

---

## Execution order

1. Task 5 (Specialty zero cards — highest user-visible break)
2. Task 1 (CNC live progress)
3. Task 4 (Assembly version bump)
4. Task 3 (Hardwoods board stock)
5. Task 2 (Search polish)
6. Task 6 (Dashboard parity)
7. Task 7 (ship)

---

## Relevant files

| File | Role |
|------|------|
| `ui/jobs/UnifiedModeSpecs.kt` | All four mode card derives |
| `data/ScanCoordinator.kt` | CNC search index |
| `data/unified/FileBackedUnifiedMetadataEngine.kt` | invalidate / caches |
| `data/ProgressStore.kt` | CNC live counts |
| `data/HardwoodsProgressStore.kt` | HW live counts |
| `data/SpecialtyProgressStore.kt` | Specialty resolved items + version |
| `data/HardwoodsRepository.kt` | `loadBoardStock` |
| `navigation/NavGraph.kt` | Spec wiring / onCncJobsChanged |
| `ui/dashboard/UnifiedModeDashboardScreen.kt` | CNC/HW/Specialty dashboards |
| `data/TrackerChangeMonitor.kt` | Invalidation fan-out |
