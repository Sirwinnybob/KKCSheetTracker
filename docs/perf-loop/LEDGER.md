# Perf-Loop Ledger

Persistent state across cold-context passes. **The agent reads this first and updates it last.**
Status values: `pending` · `in-progress` · `done`.

Branch for all loop work: `perf/loop-optimization`.

---

## Subsystem queue (work top-down)

Data/sync layer is first by owner decision — it feeds everything; slow dashboards grow from here.
UI order below is a starting guess; early data passes may re-rank it (note any change in the Pass Log).

### Phase 1 — Data / sync foundation (do first)

| # | Subsystem | Key files | Status |
|---|-----------|-----------|--------|
| D1 | Scan coordinators + change monitor | `data/ScanCoordinator.kt`, `HardwoodsScanCoordinator.kt`, `AssemblyScanCoordinator.kt`, `SpecialtyScanCoordinator.kt`, `TrackerChangeMonitor.kt`, `StaticCachePoller.kt` | done — findings-only (see note) |
| D2 | Progress stores + index caching | `data/ProgressStore.kt`, `HardwoodsProgressStore.kt`, `SpecialtyProgressStore.kt`, `SheetRipProgressStore.kt` | done |
| D3 | Unified metadata engine | `data/unified/FileBackedUnifiedMetadataEngine.kt` (+ `data/unified/`) | done |
| D4 | Repositories | `data/JobRepository.kt`, `HardwoodsRepository.kt`, `SpecialtyRepository.kt`, `SupplyRepository.kt`, `DeliveryScheduleRepository.kt`, `TimecardRepository.kt` | done |
| D5 | Sync + update | `sync/` (Syncthing*), `update/UpdateManager.kt` | in-progress |

### Phase 2 — UI (audit-ranked; refine as you learn)

| # | Subsystem | Key files | Status |
|---|-----------|-----------|--------|
| U1 | Dashboards | `ui/dashboard/` (`UnifiedModeDashboardScreen.kt`, `DashboardWidgetFactories.kt`), `ui/hardwoods/HardwoodsDashboardScreen.kt`, `ui/assembly/AssemblyDashboardScreen.kt`, `ui/supply/SupplyDashboardScreen.kt` | pending |
| U2 | Viewers + markup | `ui/viewer/SheetViewerScreen.kt`, `UnifiedReferenceViewer.kt`, `ReferencePdfViewerScreen.kt`, `ui/components/ReferencePdfPane.kt`, `ui/markup/` | pending |
| U3 | Hardwoods workspace | `ui/hardwoods/HardwoodsWorkspaceScreen.kt`, `ClassicCutListTable.kt`, `HardwoodsJobsScreen.kt`, row helpers | pending |
| U4 | Assembly + 3D viewer | `ui/assembly/`, `viewer3d/` | pending |
| U5 | Specialty | `ui/specialty/` | pending |
| U6 | Supply | `ui/supply/` | pending |
| U7 | Job detail / browser / search | `ui/detail/`, `ui/browser/`, `ui/search/` | pending |
| U8 | Navigation + scaffold | `navigation/NavGraph.kt`, `ui/components/AppScaffold.kt` | pending |
| U9 | Shared components + calculator | `ui/components/` (tables, status, `CalculatorOverlay.kt`) | pending |
| U10 | Settings + timecard/hours/clock | `ui/settings/`, `ui/timecard/`, `ui/hours/`, `clock/` | pending |

---

## Pass log

Newest at top. One row per completed pass.

| Date | Subsystem | Commit | What changed | Verify |
|------|-----------|--------|--------------|--------|
| 2026-06-27 | D4 Repositories | _(this commit)_ | Applied 1 low-risk N+1 fix: `SupplyRepository.getItems()` was calling `resolveStatus(id)` per item, each doing a full `statusDir.listFiles()` → O(items) directory listings (N+1 on the networked supply drive). Now lists the status dir once and reuses it across all items via a new `resolveStatusFrom(itemId, statusFiles)` helper; single-item `resolveStatus`/`getItem` unchanged. `DeliveryScheduleRepository` (single read+parse) and `TimecardRepository` (network on IO, shared OkHttp singleton, timeclock rules off-limits) are clean. `JobRepository`/`HardwoodsRepository`/`SpecialtyRepository` are thin engine delegators; their `getCncSnapshot(...).job` discard (`JobRepository:70`) and `getUpdatedJob` single-job `listJobsFromCacheOnly` scans are the same parked engine N+1 (FINDINGS.md updated to list these call sites). | `assembleDebug` + `testDebugUnitTest` BUILD SUCCESSFUL (11s) |
| 2026-06-27 | D3 Unified metadata engine | a7641ec | Applied 1 low-risk win: `resolveCabinetParts` (assembly cabinet-tap path) now resolves the cabinet index + CNC job + hardwood job from a single cached `loadStaticJobData` snapshot instead of three separate `getCabinetSheetIndex`/`getCncSnapshot`/`getHardwoodsSnapshot` calls — removes 2 redundant `cache_static.json` `lastModified()` stats and, crucially, the full `buildCncSearchIndex` allocation that `getCncSnapshot` did only for the result to be discarded. Behavior identical (same cached objects). Engine is heavily cache-coupled + format-coupled (StaticJobData is the on-disk `cache_static.json` Gson schema), so the bigger wins are parked: (a) D1 N+1 — confirmed `getJobInfo` exists but returns RAW unmerged jobInfo, needs a board-merging variant; (b) deep-parse path calls full `listJobs()` per job for lineupPosition (O(K·N)); (c) `getCncSnapshot` rebuilds search index per call, D4 `JobRepository:70` discards it. All in FINDINGS.md. | `assembleDebug` + `testDebugUnitTest` BUILD SUCCESSFUL (13s) |
| 2026-06-27 | D2 Progress stores + index caching | 8ab9c74 | Applied 2 low-risk N+1 fixes. (1) `HardwoodsProgressStore.summarizeJob` now fetches `getRowProgressMap` once per job instead of once per document — was O(docs) full map copies per job summary, run per-job in dashboard `jobs.map { summarizeJob }` loops. (2) `SpecialtyProgressStore.loadResolvedItems` no longer loads the items twice on a cache miss — `loadMergedCompletionByItem` now receives the already-loaded list instead of re-running the full multi-file specialty parse. `SheetRipProgressStore` clean (atomic writes, small file). Parked the big one: CNC `ProgressStore` write path re-parses+re-serializes the whole tracker JSON per appended action (O(A·K) per sheet completion); needs the Hardwoods-style cache+async-persist pattern → FINDINGS.md (medium, write cadence observed by sync). | `assembleDebug` + `testDebugUnitTest` BUILD SUCCESSFUL (24s) |
| 2026-06-27 | D1 Scan coordinators + change monitor | 490ea0c | Findings-only pass. Profiled all 6 files + the engine methods they call. Subsystem is already tightly optimized (two-phase cache scan, coalescing, warmup, per-job invalidation, dedup). No low-risk code wins found; the real issues are above the line and parked: (1) N+1 — per-job `updateJobInState` re-scans every job via `listJobsFromCacheOnly()`; needs an additive engine accessor → D3. (2) per-job search-index rebuild + per-folder state emissions in the deep-load loop → couple with (1). (3) dead warmup branch in `TrackerChangeMonitor.flushPendingNow` (ambiguous intent). (4) `flushJob === this` self-clear never fires (benign). All 4 written to FINDINGS.md. | `assembleDebug` + `testDebugUnitTest` BUILD SUCCESSFUL (33s, green baseline; no code changed) |

**Note on D1:** no code changes applied. Every actionable improvement was either engine-coupled (belongs to subsystem D3 — adding a single-job `getJobInfo` accessor) or had ambiguous intent (the warmup branch). Per the loop's "all medium/high-risk → mark done with a note and park proposals" rule, D1 is complete. The N+1 finding is the priority item to pick up when D3 runs.
