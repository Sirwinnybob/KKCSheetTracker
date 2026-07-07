# Objective

## Original User Request

Create a master handoff document so other agents can work on the full-app code review remediation in parallel. Explain the work system. Build `$loop` into it. Subagents can work on several findings at the same time.

## Goal

Remediate all findings from `C:\Users\chadc\docs\kkc-code-review-2026-07-07.md`: 35 numbered findings plus the ViewerServer path-traversal note. Every item must be verified against the current repo before fixing, fixed with the smallest safe change, covered by focused tests where practical, and recorded with evidence.

## Important Context, Constraints, and User Preferences

- Project root: `C:\Scripts\KKCSheetTracker`.
- Android app for KKC Custom Cabinets.
- AGENTS.md requirements apply, especially timeclock, frosted-glass, hours formatting, and punch business rules.
- Do not treat the review document as proof by itself. Reopen cited files and verify each defect still exists before editing.
- If a finding is already fixed, mark it `skipped-already-fixed` with current file/line evidence.
- Execution agents must commit their own project-file changes.
- Avoid broad refactors. Prefer focused fixes that preserve current behavior except for the reviewed defect.
- Do not use destructive git commands. Do not revert unrelated user or agent changes.

## Critical Data, Examples, and References

- Source review: `C:\Users\chadc\docs\kkc-code-review-2026-07-07.md`.
- Global verification gates:
  - Targeted test command for each lane or item.
  - `.\gradlew.bat testDebugUnitTest`
  - `.\gradlew.bat assembleDebug`
- Canonical `$loop` behavior:
  - Parent loop sends a fresh agent to this handoff file.
  - Loop agent must inspect current worktree, verify every explicit requirement, fix gaps, commit changes, and append `::potter(ready)` only when current evidence proves completion.
  - A later fresh-context loop verifier appends `::potter(exit)` only if no project files besides handoff/git-ignored files need changes.

# Work System

## Status Format

Each checklist item has:

- `Status`: `unclaimed`, `claimed by <agent>`, `fixed`, or `skipped-already-fixed`.
- `Verify`: exact current-state evidence to inspect before fixing.
- `Fix`: intended focused change.
- `Tests`: targeted command or test class to add/update/run.
- `Done evidence`: fill in file/line evidence and command result when complete.

Agents claim work by changing only the relevant `Status` line first. Keep claims narrow. If a claimed item touches the same file as another claimed item, coordinate by lane or serialize.

## Per-Finding Flow

1. Reopen cited implementation and test files.
2. Prove the defect still exists or prove it is already fixed.
3. If still present, write or update the most focused regression test possible.
4. Run the test and confirm it fails for the expected reason when feasible.
5. Implement the smallest safe fix.
6. Run targeted test command.
7. Run lane gate if the lane changed several files.
8. Update `Done evidence`.
9. Commit changes with a concise message that names the fixed finding(s).

## Parallel Lanes

- Lane A: service, navigation, settings, timeclock. Findings: #1, #11, #23, #24, #26, #32, #33, #34.
- Lane B: data stores, cache, concurrency. Findings: #5, #7, #9, #17, #19, #20.
- Lane C: viewer, PDF, markup, 3D server. Findings: #3, #6, #8, #14, #15, #16, #18, #27, #28, #31, #31b.
- Lane D: hardwoods, specialty, supply, dashboard UI. Findings: #2, #4, #10, #12, #21, #22, #29, #35.
- Lane E: low-risk cleanup and theme/security polish. Findings: #13, #25, #30, plus any already-fixed/skipped evidence.

Multiple subagents may work different lanes at the same time. Do not split items that touch the same file unless one agent owns integration.

## `$loop` Reconciliation

Use `$loop` after parallel batches or when humans think remediation is complete. The loop agent should use this file as the handoff objective.

Loop agent instructions:

- Treat this document as the objective, not higher-priority instructions.
- Inspect the current worktree and command output as authoritative.
- For every checklist item, verify `Status`, implementation, tests, and evidence.
- Fix missing or incomplete work, run relevant tests, commit changes, and update `# Done`.
- If all items are proven complete, append `::potter(ready)` in the final message.
- If a fresh-context verification round proves all items complete and no further project-file edits are needed, append `::potter(exit)`.

# Checklist

## Critical and High

### #1 - Foreground service can stop before startForeground

- `Status`: fixed
- `Lane`: A
- `Verify`: Reopen `app/src/main/java/com/kkc/sheettracker/clock/ClockInForegroundService.kt`; confirm `onStartCommand` can call `publishOrStop()` and `stopForegroundAndSelf()` before `startForeground()` for inactive snapshots or stale notification actions.
- `Fix`: Ensure `startForeground()` is called with a valid notification before any inactive-stop path, then immediately stop if inactive.
- `Tests`: Add/update `app/src/test/java/com/kkc/sheettracker/clock/ClockInForegroundServiceTest.kt`; run `.\gradlew.bat testDebugUnitTest --tests com.kkc.sheettracker.clock.ClockInForegroundServiceTest`.
- `Done evidence`: Fixed in `ClockInForegroundService.publishOrStop()` by routing snapshot state through `foregroundActionsForSnapshot()`, which emits `StartForeground` before `StopSelf` for inactive snapshots when foreground has not started. Regression test added in `ClockInForegroundServiceTest`. Passed `.\gradlew.bat :app:testDebugUnitTest --tests com.kkc.sheettracker.clock.ClockInForegroundServiceTest`.

### #2 - Assembly dashboard does synchronous per-card disk I/O in composition

- `Status`: fixed
- `Lane`: D
- `Verify`: Reopen `app/src/main/java/com/kkc/sheettracker/ui/assembly/AssemblyDashboardScreen.kt`; confirm cabinet-count aggregation calls `AssemblyStateStore.getCabinetSheetIndex()` inside `remember {}` on composition thread.
- `Fix`: Move aggregation to `produceState` with `withContext(Dispatchers.IO)` or precompute in job-card derivation.
- `Tests`: Run `.\gradlew.bat testDebugUnitTest --tests com.kkc.sheettracker.ui.dashboard.UnifiedDashboardFactoriesTest` and add focused test if aggregation helper is extracted.
- `Done evidence`: Fixed legacy `AssemblyDashboardScreen` by moving `deriveJobCards()` and cabinet-count aggregation to `produceState + Dispatchers.IO`, with aggregation covered by `AssemblyDashboardScreenTest`. Also verified current navigation uses `UnifiedModeDashboardSpec.Assembly`, not the legacy screen, and moved active unified Hardwoods dashboard `summarizeJob()` work to `produceState + Dispatchers.IO` after verifying it was a similar file-backed dashboard pattern. Static scan shows dashboard `getCabinetSheetIndex()` only in the assembly dashboard IO block. Passed `.\gradlew.bat :app:testDebugUnitTest --tests com.kkc.sheettracker.ui.assembly.AssemblyDashboardScreenTest`, `.\gradlew.bat :app:testDebugUnitTest --tests com.kkc.sheettracker.ui.dashboard.UnifiedDashboardFactoriesTest`, and `.\gradlew.bat :app:assembleDebug`.

### #3 - PdfRenderEngine.close races with in-flight renders

- `Status`: fixed
- `Lane`: C
- `Verify`: Reopen `app/src/main/java/com/kkc/sheettracker/ui/components/ReferencePdfPane.kt`; confirm render methods use `mutex.withLock` but `close()` mutates renderer/fd without same mutex.
- `Fix`: Make close coroutine-safe by acquiring the engine mutex before closing/nulling renderer and fd; call it from dispose through an IO/non-cancelled path that cannot interleave with render.
- `Tests`: Add/update `ReferencePdfPane` JVM tests if feasible; run `.\gradlew.bat testDebugUnitTest --tests com.kkc.sheettracker.ui.components.ReferencePdfPaneZoomPanTest`.
- `Done evidence`: Fixed via parallel subagent (sonnet). All render methods used `mutex.withLock` but `close()` mutated renderer/fd unlocked and was called synchronously from `onDispose`. `close()` is now `suspend` under the same mutex; disposal runs on a process-lifetime IO scope wrapped in `NonCancellable` so it cannot interleave with a render. Verified only other caller (JobBoardGrid) already calls it in an IO context. No JVM test seam (PdfRenderer/ParcelFileDescriptor SDK stubs); verified by full `testDebugUnitTest` + `assembleDebug`. Commit 353b4af.

### #4 - mergeActiveReorder can overrun when active order and filtered jobs desync

- `Status`: fixed
- `Lane`: D
- `Verify`: Reopen `app/src/main/java/com/kkc/sheettracker/ui/components/JobDragReorder.kt`; confirm `reorderedActiveFolderNames[i++]` has no fallback when active item count differs from original.
- `Fix`: Make merge defensive with fallback to original item folder name, and/or reset active order when filtered active membership changes.
- `Tests`: Add/update component logic test; run `.\gradlew.bat testDebugUnitTest --tests com.kkc.sheettracker.ui.components.*`.
- `Done evidence`: Fixed via parallel subagent (sonnet). `reorderedActiveFolderNames[i++]` had no bounds fallback when the active-item count desynced from the filtered list (IndexOutOfBounds). Added an upfront size-mismatch reset to original order plus a `getOrElse(i){ folderNameOf(item) }` per-item fallback. Added `JobDragReorderTest` (normal / fewer / more / empty-active cases). Passed `testDebugUnitTest`. Commit 353b4af.

### #5 - TrackerChangeMonitor drops throttled invalidations permanently

- `Status`: fixed
- `Lane`: B
- `Verify`: Reopen `app/src/main/java/com/kkc/sheettracker/data/TrackerChangeMonitor.kt`; confirm `signaturesByPath` advances before `queueInvalidations` throttle acceptance.
- `Fix`: Advance signatures only after invalidation is accepted, or track observed vs successfully flushed signatures so poll retries throttled changes.
- `Done evidence`: Confirmed `pollSignaturesLocked` and the `FileObserver.onEvent` handler set `signaturesByPath[path] = next` at detection time, while `queueInvalidations` could drop that invalidation via the `MIN_INVALIDATION_GAP_MS` (750ms) throttle — the advanced signature then made every later poll see `previous == next`, so the change was lost permanently. Fix: `Invalidation` now carries the observed `path` + `signature`; the detection sites no longer advance the signature, and `queueInvalidations` commits the signature only for *accepted* invalidations. Throttled changes keep the stale signature and are re-detected/retried on the next poll. Added `throttledTrackerChangeIsRetriedOnLaterPollInsteadOfLostPermanently`. Passed `TrackerChangeMonitorSpecialtyTest`.

### #6 - ProgressStore calls block main thread from SheetViewerScreen

- `Status`: fixed
- `Lane`: C
- `Verify`: Reopen `app/src/main/java/com/kkc/sheettracker/ui/viewer/SheetViewerScreen.kt`; confirm skip/complete/bad-part/viewed handlers call `ProgressStore` synchronous file I/O from Compose handlers or default `LaunchedEffect`.
- `Fix`: Wrap store work in `scope.launch(Dispatchers.IO)` or equivalent, and marshal UI state/snackbar updates back to main.
- `Tests`: Run `.\gradlew.bat testDebugUnitTest --tests com.kkc.sheettracker.data.ProgressStoreTest --tests com.kkc.sheettracker.ui.viewer.SheetViewerScreenTest`.
- `Done evidence`: Fixed via parallel subagent (sonnet). skip/complete/bad-part/viewed handlers called ProgressStore `appendAction` (synchronous readText/writeText) directly from Compose callbacks and a lifecycle ON_STOP observer. Each now runs in `scope.launch { withContext(Dispatchers.IO) { … } }` with UI-state/snackbar updates marshaled back to Main; `persistViewTouch` became `suspend`. No JVM UI-test seam (no Robolectric/Compose-test infra in this module); verified by full `testDebugUnitTest` + `assembleDebug`. Commit 353b4af.
  - **Fresh-context follow-up (2026-07-07):** the parallel-subagent pass above only fixed the *save* path. The *load* path was still calling `pdfMarkupStore.getMergedActiveStrokes(...)` and `pdfMarkupStore.loadTabletPageMarkup(...)` (both real disk I/O — list+read+JSON-parse tracker files) directly on the Main dispatcher inside a bare `LaunchedEffect` in both `SheetViewerScreen.kt` (~line 396-402) and `UnifiedReferenceViewer.kt` (~line 505-518). Fixed by wrapping both calls together in `withContext(Dispatchers.IO)` in both files. Verified via full `.\gradlew.bat :app:testDebugUnitTest` and `.\gradlew.bat :app:assembleDebug`, both BUILD SUCCESSFUL.

### #7 - HardwoodsProgressStore JobCache maps are unsynchronized

- `Status`: fixed
- `Lane`: B
- `Verify`: Reopen `app/src/main/java/com/kkc/sheettracker/data/HardwoodsProgressStore.kt`; confirm cache read paths can iterate mutable maps while write paths mutate them without shared lock.
- `Fix`: Guard reads and writes for each `JobCache` with the same synchronization primitive, or use safe concurrent collections consistently.
- `Tests`: Add/update `HardwoodsProgressStoreTest` concurrency regression; run `.\gradlew.bat testDebugUnitTest --tests com.kkc.sheettracker.data.HardwoodsProgressStoreTest`.
- `Done evidence`: Confirmed `JobCache` uses plain `mutableMapOf`/`MutableList`; `appendAction`->`applyActionToCache` mutated `rowProgressMap`/`skippedCabinetMap`/`totalsRip10Map`/`localActions` synchronously on the caller thread while `getRowProgressMap`/`getSkippedCabinetMap`/`getTotalsRip10DoneMap` iterated the same maps unlocked. Guarded every access with the per-job `JobCache` instance monitor (`synchronized(cache)`) on both the write block and the three reader snapshots — same primitive for reads and writes. Added `concurrentRowWritesAndReadsDoNotCorruptCache` (6 threads x 300 iters). Passed `HardwoodsProgressStoreTest`.

### #8 - savePageMarkup blocks UI thread for pen strokes

- `Status`: fixed
- `Lane`: C
- `Verify`: Reopen `app/src/main/java/com/kkc/sheettracker/data/PdfMarkupStore.kt`, `SheetViewerScreen.kt`, and `UnifiedReferenceViewer.kt`; confirm markup saves/loads are synchronous at UI call sites.
- `Fix`: Move markup save/load calls to `Dispatchers.IO`; keep public API stable unless converting to suspend is cleaner and all tests/callers are updated.
- `Tests`: Run `.\gradlew.bat testDebugUnitTest --tests com.kkc.sheettracker.data.PdfMarkupStoreTest --tests com.kkc.sheettracker.ui.markup.PdfMarkupSupportTest`.
- `Done evidence`: Fixed via parallel subagent (sonnet). `PdfMarkupStore.savePageMarkup` does synchronous readText/writeText under a lock; call sites in SheetViewerScreen (`persistCurrentPageMarkup`, undo load) and UnifiedReferenceViewer (`persistMarkupState` + two inlined duplicate calls) were synchronous on the UI thread. Moved all save/load to `scope.launch(Dispatchers.IO)`; deduped the two inline calls through `persistMarkupState`; public API unchanged. No JVM seam; verified by full `testDebugUnitTest` + `assembleDebug`. Commit 353b4af.

### #9 - getCncSnapshot can mix data/signature generations

- `Status`: fixed
- `Lane`: B
- `Verify`: Reopen `app/src/main/java/com/kkc/sheettracker/data/unified/FileBackedUnifiedMetadataEngine.kt`; confirm `getCncSnapshot` loads static data then rereads `staticByJob` for signature.
- `Fix`: Return or capture a single cached entry containing both data and signature, so both come from one generation.
- `Tests`: Add/update `UnifiedMetadataEngineTest`; run `.\gradlew.bat testDebugUnitTest --tests com.kkc.sheettracker.data.unified.UnifiedMetadataEngineTest`.
- `Done evidence`: Confirmed `getCncSnapshot` called `loadStaticJobData(...)` for the data then separately re-read `staticByJob[jobFolderName]?.signature`, so a concurrent reload could pair this generation's `cncJob` with another generation's signature/search index. Fixed to read the cached entry once (`val entry = staticByJob[jobFolderName]`) and take both `entry.data` and `entry.signature` from it (falling back to the just-loaded data with no index caching if the job was invalidated concurrently). Added `getCncSnapshotPairsJobAndSearchIndexFromSameGeneration` (2 parts -> search index size 2). Passed `UnifiedMetadataEngineTest`.
  - **Fresh-context follow-up (2026-07-07):** `getCncSnapshotPairsJobAndSearchIndexFromSameGeneration` is single-threaded (mutate-then-refresh-then-read) and would likely pass even against the pre-fix two-read implementation, since `refreshJobDeep` populates the cache before `getCncSnapshot` runs. Added `getCncSnapshotStaysConsistentUnderConcurrentRefresh`: a writer thread flips the CNC part count and calls `refreshJobDeep` while a reader thread concurrently calls `getCncSnapshot` and asserts `job.materials` part count matches `searchIndex.size` on every read, 1000 reads over 200 writer iterations. Verified via full `testDebugUnitTest`, BUILD SUCCESSFUL.

### #10 - StylusDrawingCanvas uses raw pixels as dp

- `Status`: fixed
- `Lane`: D
- `Verify`: Reopen `app/src/main/java/com/kkc/sheettracker/ui/hardwoods/ClassicCutListTable.kt`; confirm `Modifier.size()` receives raw pixel dimensions as dp.
- `Fix`: Convert table pixel width/height to dp using `LocalDensity.current`; remove useless touch-slop divide/multiply if present.
- `Tests`: Add/update `ClassicCutListInputTest` if helper extract is needed; run `.\gradlew.bat testDebugUnitTest --tests com.kkc.sheettracker.ui.hardwoods.ClassicCutListInputTest`.
- `Done evidence`: Fixed via parallel subagent (sonnet). `Modifier.size(width=(tableSize.width/touchSlop*touchSlop).dp, …)` — the touchSlop divide/multiply cancelled to a no-op, leaving raw pixels appended with `.dp`. Now `with(density){ tableSize.width.toDp() }` / `.toDp()` using the in-scope density; removed the dead `LocalViewConfiguration` import. No JVM seam (Compose layout); verified by compile + suite. Commit 353b4af.

### #11 - Legacy nav stack skips employee login gate on clock-in

- `Status`: fixed
- `Lane`: A
- `Verify`: Reopen `app/src/main/java/com/kkc/sheettracker/navigation/NavGraph.kt`; confirm `LegacySingleStackNavigation` persists clock-in without the blank-employee pending login gate used by multi-stack.
- `Fix`: Hoist shared clock-in login/pending flow or route legacy `onClockIn` through same gate; preserve employee name appended to persisted job name.
- `Tests`: Add/update navigation logic test if seam exists; run `.\gradlew.bat testDebugUnitTest --tests com.kkc.sheettracker.navigation.*`.
- `Done evidence`: Fixed via parallel subagent (sonnet). `LegacySingleStackNavigation` persisted clock-in directly with no blank-employee pending-login gate and no employee-name suffix (Multi gates via `pendingClockIn`). Hoisted shared `resolveClockInGate(...)` (sealed Ready/NeedsLogin) and `formattedClockInJobName(...)`; both nav hosts now route through them, and Legacy gained the `pendingClockIn` state + `HoursLoginDialog` block mirroring Multi. Added `ClockInGateTest`. Passed `navigation.*` + `assembleDebug`. Commit 353b4af.

### #12 - Compact specialty checkbox overwrites all multi-station CUSTOM keys

- `Status`: fixed
- `Lane`: D
- `Verify`: Reopen `app/src/main/java/com/kkc/sheettracker/ui/specialty/CompactSpecialtySection.kt`; confirm compact checkbox writes all completion keys for multi-station CUSTOM items.
- `Fix`: Toggle only the key matching current `SpecialtySurfaceMode`; if no single matching key exists, disable or hide compact checkbox for multi-key items.
- `Tests`: Update `CompactSpecialtySectionLogicTest`; run `.\gradlew.bat testDebugUnitTest --tests com.kkc.sheettracker.ui.specialty.CompactSpecialtySectionLogicTest`.
- `Done evidence`: Fixed via parallel subagent (sonnet). The compact checkbox called `setItemCompletion`, which writes ALL station keys for a multi-station CUSTOM item. Added `compactCompletionKeyForMode(item, mode)` resolving a single key (shared ITEM for non-split; the mode-relevant station key for CUSTOM, else null); the checkbox is disabled when null and writes only that key via `setItemCompletionKey`. Updated `CompactSpecialtySectionLogicTest` (+5 cases). Passed. Commit 353b4af.

### #13 - Theme JSON cannot set distinct status bg/border shades

- `Status`: fixed
- `Lane`: E
- `Verify`: Reopen `app/src/main/java/com/kkc/sheettracker/ui/theme/KKCThemeRepository.kt`; confirm derived status fields all read same base JSON key.
- `Fix`: Read dedicated keys such as `completeBg`, `completeBorder`, `badBg`, `skipBg`, `skipBorder`, and `inProgressBorder`, with existing base-key fallback.
- `Tests`: Update `KKCThemeRepositoryTest`; run `.\gradlew.bat testDebugUnitTest --tests com.kkc.sheettracker.ui.theme.KKCThemeRepositoryTest`.
- `Done evidence`: Confirmed `parseThemeFile` derived `completeBg`/`completeBorder` from `color(statusObj, "complete")`, `badBg` from "bad", `skipBg`/`skipBorder` from "skip", `inProgressBorder` from "inProgress" — so distinct shades were impossible. Fixed both light and dark status blocks to read dedicated keys (`completeBg`, `completeBorder`, `badBg`, `skipBg`, `skipBorder`, `inProgressBorder`) with fallback chain: dedicated key -> base key -> built-in default. Added `statusColorsSupportDedicatedBgAndBorderShades` and `statusBgAndBorderFallBackToBaseKeyWhenNoDedicatedKeyGiven`. Verified via `KKCThemeRepositoryTest`.

### #14 - SheetViewerScreen scans tracker directory every second on main thread

- `Status`: fixed
- `Lane`: C
- `Verify`: Reopen `app/src/main/java/com/kkc/sheettracker/ui/viewer/SheetViewerScreen.kt`; confirm periodic `trackerContentVersion` call runs in `LaunchedEffect` without IO dispatcher.
- `Fix`: Wrap tracker version call in `withContext(Dispatchers.IO)`.
- `Tests`: Run `.\gradlew.bat testDebugUnitTest --tests com.kkc.sheettracker.ui.viewer.SheetViewerScreenTest`.
- `Done evidence`: Fixed via parallel subagent (sonnet). `while(isActive){ markupContentVersion = pdfMarkupStore.trackerContentVersion(job); delay(1000) }` folded lastModified/length over the tracker dir on Main every second. Wrapped the call in `withContext(Dispatchers.IO)`. No JVM seam; verified by full `testDebugUnitTest` + `assembleDebug`. Commit 353b4af.

### #15 - UnifiedReferenceViewer has same main-thread tracker scan

- `Status`: fixed
- `Lane`: C
- `Verify`: Reopen `app/src/main/java/com/kkc/sheettracker/ui/viewer/UnifiedReferenceViewer.kt`; confirm periodic `trackerContentVersion` call runs without IO dispatcher.
- `Fix`: Wrap tracker version call in `withContext(Dispatchers.IO)`.
- `Tests`: Run `.\gradlew.bat testDebugUnitTest --tests com.kkc.sheettracker.ui.viewer.UnifiedReferenceViewerTest`.
- `Done evidence`: Fixed via parallel subagent (sonnet). Identical every-second main-thread `trackerContentVersion` poll in UnifiedReferenceViewer. Wrapped in `withContext(Dispatchers.IO)` (added `rememberCoroutineScope`/`launch` imports, also used by #8's markup fix). No JVM seam; verified by full `testDebugUnitTest` + `assembleDebug`. Commit 353b4af.

### #16 - ReferencePdfViewerScreen does file-backed lookup in remember

- `Status`: fixed
- `Lane`: C
- `Verify`: Reopen `app/src/main/java/com/kkc/sheettracker/ui/viewer/ReferencePdfViewerScreen.kt`; confirm sheet index and reference filename fallback calls execute inside `remember`.
- `Fix`: Move file-backed lookups to `produceState` with `withContext(Dispatchers.IO)`, and drive dependent state from result.
- `Tests`: Run `.\gradlew.bat testDebugUnitTest --tests com.kkc.sheettracker.ui.viewer.*`.
- `Done evidence`: Fixed via parallel subagent (sonnet). `sheetIndex`, the assembly-virtual fallback, and `defaultPdfFilename` each called `jobRepository` engine() I/O inside `remember{}` on the composition thread. Converted all three to `produceState` hopping to `Dispatchers.IO`, with downstream `remember{}` keyed on the produced value. No JVM seam; verified by full `testDebugUnitTest` + `assembleDebug`. Commit 353b4af.

## Medium

### #17 - Calculator formatNumber emits binary double garbage

- `Status`: fixed
- `Lane`: B
- `Verify`: Reopen `app/src/main/java/com/kkc/sheettracker/ui/components/CalculatorEngine.kt`; confirm formatting uses `BigDecimal(value)`.
- `Fix`: Use `BigDecimal.valueOf(value)` or `BigDecimal(value.toString())`.
- `Tests`: Update `CalculatorEngineTest`; run `.\gradlew.bat testDebugUnitTest --tests com.kkc.sheettracker.ui.components.CalculatorEngineTest`.
- `Done evidence`: Confirmed `formatNumber()` line 337 used `java.math.BigDecimal(value)` in the scientific-notation (`contains('E')`) branch, which expands the exact binary double into garbage digits. Fixed to `java.math.BigDecimal.valueOf(value)`. Added regression `smallResult_inScientificRange_formatsWithoutBinaryGarbage` (1 ÷ 10000000 → "0.0000001"). Verified via `CalculatorEngineTest`.

### #18 - CoverPageOverlay gets PDF catalog on main thread

- `Status`: fixed
- `Lane`: C
- `Verify`: Reopen `app/src/main/java/com/kkc/sheettracker/ui/components/CoverPageOverlay.kt`; confirm `jobRepository.getJobPdfCatalog()` executes before existing IO block.
- `Fix`: Move catalog lookup inside `withContext(Dispatchers.IO)`.
- `Tests`: Run `.\gradlew.bat testDebugUnitTest --tests com.kkc.sheettracker.ui.components.*`.
- `Done evidence`: Fixed via parallel subagent (sonnet). `jobRepository.getJobPdfCatalog(...)` ran on the LaunchedEffect's Main dispatcher before the existing IO block. Moved the catalog lookup inside `withContext(Dispatchers.IO)`. No JVM seam; verified by full `testDebugUnitTest` + `assembleDebug`. Commit 353b4af.

### #19 - Blank material mapping nulls auto-complete matching

- `Status`: fixed
- `Lane`: B
- `Verify`: Reopen `app/src/main/java/com/kkc/sheettracker/data/MaterialMappings.kt`; confirm `canonical()` can return blank mapped value.
- `Fix`: Use mapped value only if normalized mapped value is non-blank; otherwise return normalized input.
- `Tests`: Update `MaterialMappingsTest`; run `.\gradlew.bat testDebugUnitTest --tests com.kkc.sheettracker.data.MaterialMappingsTest`.
- `Done evidence`: Confirmed `canonical()` returned `normalize(sanitized ?: name)`; a mapping whose value is a blank string made `sanitized` a non-null blank, so it returned `""`. Fixed to `normalize(sanitized).ifEmpty { normalized }`. Added regression `blankMappedValueFallsBackToNormalizedInput`. Verified via `MaterialMappingsTest`.

### #20 - JobBoardRequestStore loses concurrent read-modify-write edits

- `Status`: fixed
- `Lane`: B
- `Verify`: Reopen `app/src/main/java/com/kkc/sheettracker/data/JobBoardRequestStore.kt`; confirm read/merge/atomic-write sequence lacks mutex.
- `Fix`: Add per-store or per-file `Mutex` around read/merge/write, following existing store patterns.
- `Tests`: Add/update store concurrency test; run `.\gradlew.bat testDebugUnitTest --tests com.kkc.sheettracker.data.*JobBoard*`.
- `Done evidence`: Confirmed `queueEdit` read/merge/atomic-write had no lock, so two concurrent calls (including from separate screen-owned store instances over the same file) could drop an edit. Callers are synchronous, so used a `synchronized` block over a per-file monitor keyed by the request file's absolute path (companion `fileLocks` map) rather than a suspend `Mutex`, guarding cross-instance writes too. Added `JobBoardRequestStoreTest.concurrentEditsToDistinctFoldersAreAllRetained` (50 edits / 8 threads / 2 instances). Passed `com.kkc.sheettracker.data.JobBoardRequestStoreTest`.

### #21 - Hardwoods cabinet-number search is exact and case-sensitive

- `Status`: fixed
- `Lane`: D
- `Verify`: Reopen `app/src/main/java/com/kkc/sheettracker/ui/hardwoods/HardwoodsSearchScreen.kt`; confirm cabinet number search uses exact/case-sensitive match while other fields use case-insensitive substring.
- `Fix`: Use `entry.cabinetNumbers.any { it.contains(query, ignoreCase = true) }`.
- `Tests`: Update `HardwoodsSearchScreenTest`; run `.\gradlew.bat testDebugUnitTest --tests com.kkc.sheettracker.ui.hardwoods.HardwoodsSearchScreenTest`.
- `Done evidence`: Confirmed line 77 used `cabinetNumbers.any { it == query }` (exact, case-sensitive) while all other fields used `contains(query, ignoreCase = true)`. Fixed to `it.contains(query, ignoreCase = true)`. Existing exact-match test still holds (CAB-210 !contains CAB-10); added `matchesCabinetNumberCaseInsensitiveSubstring`. Verified via `HardwoodsSearchScreenTest`.

### #22 - Classic width normalizer diverges from grouping normalizer

- `Status`: fixed
- `Lane`: D
- `Verify`: Reopen `ClassicCutListTable.kt` and `HardwoodsWorkspaceScreen.kt`; confirm private normalize function differs from grouping lookup/builder.
- `Fix`: Extract/use one shared width normalizer for both map construction and lookup.
- `Tests`: Update `HardwoodsRowHelpersTest` or `ClassicCutListInputTest`; run `.\gradlew.bat testDebugUnitTest --tests com.kkc.sheettracker.ui.hardwoods.HardwoodsRowHelpersTest`.
- `Done evidence`: Fixed via parallel subagent (sonnet). ClassicCutListTable had a private `normalizeWidth` handling only space-separated mixed fractions, while HardwoodsWorkspaceScreen's `normalizeWidthForGrouping` (via `parseDimensionForSort`) also handled plain (`1/2`) and dash (`3-1/2`) forms — so band lookups missed and fell back to transparent. Made `normalizeWidthForGrouping` `internal` and used it for both the lookup and the map build; deleted the divergent local copy + its unused regex. Added `HardwoodsRowHelpersTest` cases for the previously-divergent formats. Passed. Commit 353b4af.

### #23 - CNC-to-hardwoods sync listener only wired in multi-stack nav

- `Status`: fixed
- `Lane`: A
- `Verify`: Reopen `NavGraph.kt`; confirm `progressStore.onSheetStatusChangedListener` registration exists only in multi-stack path and `syncCncToHardwoods` has no legacy fallback.
- `Fix`: Move listener registration into shared `AppNavigation` or shared helper used by both nav hosts.
- `Tests`: Add/update navigation or sync test if practical; run `.\gradlew.bat testDebugUnitTest --tests com.kkc.sheettracker.navigation.*`.
- `Done evidence`: Reconciled two competing PR branches (`fix/issue-23-sync-cnc-to-hardwoods-...` and `jules-...-86fa4ab4`) that both hoisted the fix to the same lines of `NavGraph.kt` in slightly different, conflicting ways. Manually hoisted the `hardwoodsRepository` instantiation, `coroutineScope`, and `progressStore.onSheetStatusChangedListener` `DisposableEffect` from `MultiBackStackNavigation` (previously lines ~438-458) up into the shared `AppNavigation` function (now before `val flags = remember(appStateFlags) { ... }`), using the already-shared `sharedHardwoodsProgressStore`. This registers the CNC->hardwoods sync listener once for both `MultiBackStackNavigation` and `LegacySingleStackNavigation` paths. Confirmed `coroutineScope` and the removed `hardwoodsRepository`-based listener block were not referenced elsewhere in `MultiBackStackNavigation` before deleting. Passed `.\gradlew.bat :app:testDebugUnitTest --tests com.kkc.sheettracker.navigation.*` (HomeTabRoutingTest, SpecialtyRouteTest, WorkModeTest all 0 failures) and `.\gradlew.bat :app:assembleDebug`.
  - **Fresh-context follow-up (2026-07-07):** `CncToHardwoodsSyncTest.kt` (the regression test cited for this finding) only exercises the underlying `syncCncToHardwoods` function via a hand-copied listener registration — it never reads or touches `NavGraph.kt`, so it would not catch a regression that un-hoists the `DisposableEffect` back into `MultiBackStackNavigation` only. Added `NavGraphCncSyncWiringTest.kt`, a source-structure regression test that reads the actual `NavGraph.kt` file and asserts the `progressStore.onSheetStatusChangedListener` assignment lives inside the shared `AppNavigation` function body (i.e. before either `MultiBackStackNavigation` or `LegacySingleStackNavigation` is declared) and that `AppNavigation` actually calls both nav-host functions. Passed `testDebugUnitTest --tests com.kkc.sheettracker.navigation.NavGraphCncSyncWiringTest`.

### #24 - Settings employee edit state lacks employeeName key

- `Status`: fixed
- `Lane`: A
- `Verify`: Reopen `app/src/main/java/com/kkc/sheettracker/ui/settings/SettingsScreen.kt`; confirm `editEmployeeName` uses unkeyed `remember`.
- `Fix`: Use `remember(employeeName) { mutableStateOf(employeeName) }`.
- `Tests`: Add/update settings logic test if present; otherwise run `.\gradlew.bat testDebugUnitTest --tests com.kkc.sheettracker.ui.theme.*` plus full compile.
- `Done evidence`: Confirmed line 78 used unkeyed `remember { mutableStateOf(employeeName) }` so the field never resync'd when the `employeeName` prop changed (e.g. after auto-login/directory refresh). Fixed to `remember(employeeName) { mutableStateOf(employeeName) }`. No settings unit-test module exists and the logic is inline in a composable; gate is `assembleDebug` per the finding's fallback. Verified via `.\gradlew.bat :app:assembleDebug`.

### #25 - Theme header containment uses raw startsWith

- `Status`: fixed
- `Lane`: E
- `Verify`: Reopen `KKCThemeRepository.kt`; confirm header background containment compares raw path prefix without separator boundary or `Path.startsWith`.
- `Fix`: Use canonical/normalized `Path.startsWith`, or separator-aware `file.path == root.path || file.path.startsWith(root.path + File.separator)`.
- `Tests`: Update `KKCThemeRepositoryTest`; run `.\gradlew.bat testDebugUnitTest --tests com.kkc.sheettracker.ui.theme.KKCThemeRepositoryTest`.
- `Done evidence`: Confirmed `resolveHeaderBackground` used `!file.path.startsWith(root.path)` (canonical files but raw prefix), so a sibling like `.metadata/themes-evil/x.svg` passed containment for root `.metadata/themes`. Fixed to separator-aware `file.path != root.path && !file.path.startsWith(root.path + File.separator)`. Added `themeHeaderSvgSiblingPrefixDirectoryIsRejected`. Verified via `KKCThemeRepositoryTest`.

### #26 - Old timeclock background media files are orphaned

- `Status`: fixed
- `Lane`: A
- `Verify`: Reopen `app/src/main/java/com/kkc/sheettracker/ui/timecard/BgPickerSheet.kt` and `TimecardBgStore`; confirm replace/clear overwrites config without deleting previous media file.
- `Fix`: After successful save/clear, delete previous `currentConfig.mediaPath` if it points inside timecard background directory and is no longer selected.
- `Tests`: Add/update store/helper test if deletion logic is extracted; run `.\gradlew.bat testDebugUnitTest`.
- `Done evidence`: Fixed via parallel subagent (sonnet). `TimecardBgStore.save()` wrote config to DataStore but never deleted the previous copied media file, so every replace/clear (all funnel through `save()`) orphaned the old file in `filesDir/timeclock_bg/`. `save()` now reads the prior mediaPath and calls new `deleteOrphanedMedia(mediaDir, previous, new)`, which deletes only when the path differs and is a direct child of the bg dir (guards a still-selected file and anything outside the dir). Added pure-function `TimecardBgStoreTest`. Passed. Commit 353b4af.

### #27 - Part graphic ZIP decode runs synchronously in dialog remember

- `Status`: fixed
- `Lane`: C
- `Verify`: Reopen `SheetViewerScreen.kt`; confirm `loadPartGraphicBitmap()` with `ZipFile`/`BitmapFactory` runs inside dialog `remember`.
- `Fix`: Load through `produceState` keyed on selected graphic/archive/pdf and run decode in `withContext(Dispatchers.IO)`.
- `Tests`: Run `.\gradlew.bat testDebugUnitTest --tests com.kkc.sheettracker.ui.viewer.SheetViewerScreenTest`.
- `Done evidence`: Fixed via parallel subagent (sonnet). `remember(...) { loadPartGraphicBitmap(pdf, archive, graphicPath) }` ran `ZipFile` + `BitmapFactory.decodeStream` during dialog composition. Converted to `produceState` keyed on graphicPath/archive/pdf, decoding in `withContext(Dispatchers.IO)`. Integration fix (main thread): consume the delegated `produceState` value via a local val before the null-check `Image(...)` to satisfy smart-cast. No JVM seam; verified by full `testDebugUnitTest` + `assembleDebug`. Commit 353b4af.

### #28 - Render cache evicts page bitmaps without recycling

- `Status`: fixed
- `Lane`: C
- `Verify`: Reopen `SheetViewerScreen.kt`; confirm `cacheRenderedPage` removes evicted page from cache without recycling `pageBitmap`.
- `Fix`: Recycle evicted cached page bitmap unless it is currently displayed or otherwise still referenced; do not recycle `diagramBitmap`.
- `Tests`: Add helper test if cache logic can be extracted; run `.\gradlew.bat testDebugUnitTest --tests com.kkc.sheettracker.ui.viewer.SheetViewerScreenTest`.
- `Done evidence`: Fixed via parallel subagent (sonnet). `cacheRenderedPage` eviction did `renderCache.remove(stalePage)` without recycling the bitmap. Extracted `shouldRecycleEvictedPageBitmap(evicted, currentlyDisplayed)` (non-null, not already recycled, and not identity-equal to the currently displayed `pageBitmap`) and recycle only when it returns true; `diagramBitmap` is never touched. Added 5 `SheetViewerScreenTest` cases (mockito Bitmap mocks). Passed. Commit 353b4af.

## Low

### #29 - Dashboard thumbnail produceState slots are unkeyed

- `Status`: fixed
- `Lane`: D
- `Verify`: Reopen `app/src/main/java/com/kkc/sheettracker/ui/dashboard/UnifiedModeDashboardScreen.kt`; confirm repeated recent/remake items use `forEach` with unkeyed `produceState`.
- `Fix`: Wrap each repeated item in `key(item.jobFolderName, item.pdfFilename)`.
- `Tests`: Run `.\gradlew.bat testDebugUnitTest --tests com.kkc.sheettracker.ui.dashboard.UnifiedDashboardFactoriesTest`.
- `Done evidence`: Fixed via parallel subagent (haiku). recent/remake `forEach` items used unkeyed `produceState`, so thumbnail load state could mismatch slots on list change. Wrapped each item in `key(item.jobFolderName, item.pdfFilename){ … }`. Integration fix (main thread): added the missing `androidx.compose.runtime.key` import. Verified by compile + `UnifiedDashboardFactoriesTest`. Commit 353b4af.

### #30 - HardwoodsJobBrowserScreen is dead code with fabricated zero progress

- `Status`: fixed
- `Lane`: E
- `Verify`: Search for call sites of `HardwoodsJobBrowserScreen`; confirm none exist outside its own declaration and nav uses `HardwoodsJobsScreen`.
- `Fix`: Delete unreachable file unless current repo has gained a real caller; if caller exists, wire real progress instead.
- `Tests`: Run `.\gradlew.bat testDebugUnitTest --tests com.kkc.sheettracker.ui.hardwoods.*` and `.\gradlew.bat assembleDebug`.
- `Done evidence`: Fixed (deleted) via parallel subagent (haiku). Grep across `app/src` found zero references to `HardwoodsJobBrowserScreen` outside its own declaration; navigation uses `HardwoodsJobsScreen`. Deleted the file. Passed `hardwoods.*` + `assembleDebug`. Commit 353b4af.

### #31 - ViewerServer binds wildcard instead of loopback

- `Status`: fixed
- `Lane`: C
- `Verify`: Reopen `app/src/main/java/com/kkc/sheettracker/viewer3d/ViewerServer.kt`; confirm server extends `NanoHTTPD(0)` or otherwise binds all interfaces while WebView uses `127.0.0.1`.
- `Fix`: Construct server with loopback hostname, e.g. `NanoHTTPD("127.0.0.1", 0)`.
- `Tests`: Add/update ViewerServer test if JVM-feasible; otherwise run `.\gradlew.bat assembleDebug` and document constructor evidence.
- `Done evidence`: Fixed via parallel subagent (sonnet). Server extended `NanoHTTPD(0)` (all interfaces) while the WebView (Model3DPane) connects to `127.0.0.1`. Now `NanoHTTPD("127.0.0.1", 0)`. Added `ViewerServerTest.bindsToLoopbackHostname_notWildcard`. Passed. Commit 353b4af.

### #31b - ViewerServer path traversal / arbitrary file read note

- `Status`: fixed
- `Lane`: C
- `Verify`: Reopen `ViewerServer.kt`; confirm decoded `folderName`, room, or GLB relative path can escape intended `baseDir` or room directory through canonicalization gaps.
- `Fix`: Canonicalize resolved job/3D/room/file paths and require they remain under canonical `baseDir` and intended room directory using path-aware containment; reject escapes with 400/403.
- `Tests`: Add ViewerServer path-containment unit tests if possible; otherwise document manual evidence and run `.\gradlew.bat assembleDebug`.
- `Done evidence`: Fixed via parallel subagent (sonnet) — was a real exploitable gap, not already fixed. `findRoomDir`/`scanRooms` resolved request-controlled `room`/`folderName` into `File` objects with no containment check, so `room="../../../outside"` redirected the very root that `serveGlbFile` later trusts → arbitrary file read outside `baseDir`. Added `@VisibleForTesting isPathContainedIn(candidate, root)` (canonicalize both + separator-aware `startsWith`) and applied it to threeDDir/room/GLB resolution; escapes now return 400/403/404. Added `ViewerServerTest` containment-helper cases (incl. sibling-prefix false-positive) plus room- and folderName-traversal escape tests. Passed. Commit 353b4af.

### #32 - tabletIdDirty comparison omits trim

- `Status`: fixed
- `Lane`: A
- `Verify`: Reopen `SettingsScreen.kt`; confirm tablet ID dirty flag compares raw text while save trims.
- `Fix`: Compare `it.trim() != tabletId.trim()`.
- `Tests`: Add/update settings helper test if available; run `.\gradlew.bat assembleDebug`.
- `Done evidence`: Confirmed dirty flag was `it != tabletId` while save used `editTabletId.trim()`, so trailing/leading whitespace flagged a spurious diff. Fixed to `it.trim() != tabletId.trim()`. Verified via `.\gradlew.bat :app:assembleDebug`.

### #33 - basePathDirty comparison omits trim

- `Status`: fixed
- `Lane`: A
- `Verify`: Reopen `SettingsScreen.kt`; confirm base path dirty flag compares raw text while save trims.
- `Fix`: Compare `it.trim() != basePath.trim()`.
- `Tests`: Add/update settings helper test if available; run `.\gradlew.bat assembleDebug`.
- `Done evidence`: Confirmed dirty flag was `it != basePath` while save used `editBasePath.trim()`. Fixed to `it.trim() != basePath.trim()`. Verified via `.\gradlew.bat :app:assembleDebug`.

### #34 - employeeNameDirty comparison omits trim

- `Status`: fixed
- `Lane`: A
- `Verify`: Reopen `SettingsScreen.kt`; confirm employee name dirty checks compare raw text while save trims, including dropdown-select path.
- `Fix`: Compare `edit/input.trim() != employeeName.trim()` in text and dropdown paths.
- `Tests`: Add/update settings helper test if available; run `.\gradlew.bat assembleDebug`.
- `Done evidence`: Confirmed both the text-field path (`it != employeeName`) and the dropdown-select path (`name != employeeName`) compared raw text while save used `editEmployeeName.trim()`. Fixed both to `.trim() != employeeName.trim()`. Verified via `.\gradlew.bat :app:assembleDebug`.

### #35 - Supply status reload race can transiently stale UI

- `Status`: fixed
- `Lane`: D
- `Verify`: Reopen `app/src/main/java/com/kkc/sheettracker/ui/supply/SupplyDashboardScreen.kt`; confirm each status change launches write then full reload and assigns result without monotonic guard.
- `Fix`: Prefer monotonic request id so only newest reload updates state, or patch known item status in memory after successful write.
- `Tests`: Add/update supply dashboard logic test if helper extracted; run `.\gradlew.bat testDebugUnitTest --tests com.kkc.sheettracker.ui.supply.*`.
- `Done evidence`: Fixed via parallel subagent (sonnet). Each status change did `setStatus` then unconditionally reassigned `items = getItems()`, so an older reload could stomp a newer one. Extracted `performSupplyStatusChange(...)` guarded by a monotonic `AtomicLong itemsReloadRequestId` — only the newest request applies its reload result (Toast on setStatus failure and current-items fallback preserved). Added `SupplyStatusReloadRaceTest` (real concurrency). Passed. Commit 353b4af.

# Lane Gates

- Lane A targeted gate: `.\gradlew.bat testDebugUnitTest --tests com.kkc.sheettracker.clock.* --tests com.kkc.sheettracker.navigation.*`
- Lane B targeted gate: `.\gradlew.bat testDebugUnitTest --tests com.kkc.sheettracker.data.* --tests com.kkc.sheettracker.data.unified.* --tests com.kkc.sheettracker.ui.components.CalculatorEngineTest`
- Lane C targeted gate: `.\gradlew.bat testDebugUnitTest --tests com.kkc.sheettracker.ui.viewer.* --tests com.kkc.sheettracker.ui.components.* --tests com.kkc.sheettracker.data.PdfMarkupStoreTest`
- Lane D targeted gate: `.\gradlew.bat testDebugUnitTest --tests com.kkc.sheettracker.ui.hardwoods.* --tests com.kkc.sheettracker.ui.specialty.* --tests com.kkc.sheettracker.ui.supply.* --tests com.kkc.sheettracker.ui.dashboard.*`
- Lane E targeted gate: `.\gradlew.bat testDebugUnitTest --tests com.kkc.sheettracker.ui.theme.*`
- Final gate: `.\gradlew.bat testDebugUnitTest`
- Final build: `.\gradlew.bat assembleDebug`

# Done

Use this section for durable progress notes. Each entry should include:

- Date/time.
- Agent/lane.
- Findings completed or skipped.
- Files changed.
- Test commands and results.
- Commit hash.
- Any follow-up needed.

## 2026-07-07 - Critical #1 foreground-service crash

- Agent/lane: Codex, Lane A.
- Findings completed: #1.
- Files changed: `app/src/main/java/com/kkc/sheettracker/clock/ClockInForegroundService.kt`, `app/src/test/java/com/kkc/sheettracker/clock/ClockInForegroundServiceTest.kt`, `docs/superpowers/plans/2026-07-07-code-review-remediation-loop.md`.
- Test command: `.\gradlew.bat :app:testDebugUnitTest --tests com.kkc.sheettracker.clock.ClockInForegroundServiceTest`.
- Result: passed.
- Commit hash: this entry is included in the commit that fixes #1.
- Follow-up: run wider lane/final gates before closing the full remediation objective.

## 2026-07-07 - High #2 dashboard main-thread metadata I/O

- Agent/lane: Codex, Lane D.
- Findings completed: #2.
- Files changed: `app/src/main/java/com/kkc/sheettracker/ui/assembly/AssemblyDashboardScreen.kt`, `app/src/test/java/com/kkc/sheettracker/ui/assembly/AssemblyDashboardScreenTest.kt`, `app/src/main/java/com/kkc/sheettracker/ui/dashboard/UnifiedModeDashboardScreen.kt`, `docs/superpowers/plans/2026-07-07-code-review-remediation-loop.md`.
- Test commands: `.\gradlew.bat :app:testDebugUnitTest --tests com.kkc.sheettracker.ui.assembly.AssemblyDashboardScreenTest`; `.\gradlew.bat :app:testDebugUnitTest --tests com.kkc.sheettracker.ui.dashboard.UnifiedDashboardFactoriesTest`; `.\gradlew.bat :app:assembleDebug`.
- Result: passed.
- Verification note: exact dashboard `getCabinetSheetIndex()` pattern is assembly-only after the fix; active unified Hardwoods dashboard had a related `summarizeJob()` file-backed composition pattern and was moved to IO too.
- Commit hash: this entry is included in the commit that fixes #2.

## 2026-07-07 - Medium #23 CNC-to-hardwoods sync listener only wired in multi-stack nav

- Agent/lane: Claude, Lane A.
- Findings completed: #23.
- Files changed: `app/src/main/java/com/kkc/sheettracker/navigation/NavGraph.kt`, `docs/superpowers/plans/2026-07-07-code-review-remediation-loop.md`.
- Test commands: `.\gradlew.bat :app:testDebugUnitTest --tests com.kkc.sheettracker.navigation.*`; `.\gradlew.bat :app:assembleDebug`.
- Result: passed.
- Verification note: Two open PR branches (`fix/issue-23-sync-cnc-to-hardwoods-...` and `jules-...-86fa4ab4`) attempted the identical fix on the same lines of `NavGraph.kt` with different local variable names, so neither was merged directly. Re-implemented the hoist by hand instead, and left both branches unmerged/un-deleted on the remote for the user to close out.
- Commit hash: this entry is included in the commit that fixes #23.
- Follow-up: none for #23. The `jules-...` branch also carried unrelated `@file:Suppress("ProduceStateDoesNotAssignValue")` lint annotations and a `ClockInForegroundService.kt` `@SuppressLint("MissingPermission")` annotation that were intentionally not pulled in, since the working tree already has a real runtime permission check for posting notifications that supersedes the suppress-only approach.

## 2026-07-07 - Serial batch (Claude main thread): #5, #7, #9, #13, #17, #19, #20, #21, #24, #25, #32, #33, #34

- Agent/lane: Claude (Opus), main thread, Lanes A/B/D/E.
- Findings completed: #13, #17, #19, #21, #24, #25, #32, #33, #34 (commit 678966f); #7, #9, #20 (commit 3597cb3); #5 (commit a606c90).
- Each: reopened cited file, proved defect against current code, smallest safe fix, focused regression test, targeted `testDebugUnitTest` + `assembleDebug`.
- New/updated tests: CalculatorEngineTest, MaterialMappingsTest, HardwoodsSearchScreenTest, KKCThemeRepositoryTest, UnifiedMetadataEngineTest, JobBoardRequestStoreTest (new), HardwoodsProgressStoreTest, TrackerChangeMonitorSpecialtyTest.
- Result: all passed.

## 2026-07-07 - Parallel subagent batch: #3, #4, #6, #8, #10, #11, #12, #14, #15, #16, #18, #22, #26, #27, #28, #29, #30, #31, #31b, #35

- Agent/lane: Claude dispatched 6 parallel general-purpose subagents (5 sonnet, 1 haiku by complexity) over disjoint file sets; integration/verify/commit by Claude main thread.
- Lane split (disjoint files, no cross-lane edits):
  - A (sonnet): SheetViewerScreen, UnifiedReferenceViewer, PdfMarkupStore → #6, #8, #14, #15, #27, #28.
  - B (sonnet): ReferencePdfPane, ReferencePdfViewerScreen, CoverPageOverlay → #3, #16, #18.
  - C (sonnet): ViewerServer → #31, #31b.
  - D (sonnet): JobDragReorder, ClassicCutListTable, HardwoodsWorkspaceScreen, CompactSpecialtySection → #4, #10, #12, #22.
  - E (haiku): UnifiedModeDashboardScreen, HardwoodsJobBrowserScreen(delete) → #29, #30.
  - F (sonnet): NavGraph, BgPickerSheet, TimecardBgStore, SupplyDashboardScreen → #11, #26, #35.
- Subagents were forbidden from running gradle/git or editing this doc (shared working tree); they edited only their assigned files and reported evidence. Main thread ran the single central build.
- Integration fixes by main thread: added `androidx.compose.runtime.key` import (#29) and consumed the delegated `produceState` value via a local val for smart-cast (#27) — both were compile errors surfaced only by the central build.
- New/updated tests: JobDragReorderTest (new), HardwoodsRowHelpersTest, CompactSpecialtySectionLogicTest, SheetViewerScreenTest (bitmap-recycle), ViewerServerTest (new), ClockInGateTest (new), TimecardBgStoreTest (new), SupplyStatusReloadRaceTest (new).
- Verify: `.\gradlew.bat :app:testDebugUnitTest` (full suite) and `.\gradlew.bat :app:assembleDebug` both BUILD SUCCESSFUL. Commit 353b4af (code); doc update in a follow-up commit.
- Note: `DoorCutSheetFilterTest.kt` gained a `testMarkSheetCompleteSyncsToHardwoodsViaListener` test (an off-scope leftover, related to already-committed #23) — kept because it compiles and passes and the plan says not to revert other agents' changes.
- Status: all 36 checklist items (including #31b) now `fixed`. Remaining work before `::potter(exit)`: a fresh-context verification pass.

## 2026-07-07 - Fresh-context verification round 1: 3 real gaps found and fixed

- Agent/lane: Claude (fresh-context loop verifier), main thread.
- Method: dispatched two independent read-only subagents to re-verify all 36 findings against current code (not the plan doc's claims) — one covering #1-18, one covering #19-35+#31b. 33/36 verified clean on first pass. Ran full `.\gradlew.bat :app:testDebugUnitTest` and `.\gradlew.bat :app:assembleDebug` up front — both BUILD SUCCESSFUL before any fixes, confirming no regressions from the prior parallel batch.
- Gaps found and fixed this round:
  - **#8**: the *save* path was fixed in the prior batch, but the *load* path (`getMergedActiveStrokes`/`loadTabletPageMarkup`) still ran synchronous disk I/O on Main inside `LaunchedEffect` in both `SheetViewerScreen.kt` and `UnifiedReferenceViewer.kt`. Wrapped both in `withContext(Dispatchers.IO)`.
  - **#9**: implementation was correct but the existing test was single-threaded and wouldn't have caught the original cross-generation race. Added `getCncSnapshotStaysConsistentUnderConcurrentRefresh` (real writer/reader thread stress test) to `UnifiedMetadataEngineTest.kt`.
  - **#23**: the regression test never touched `NavGraph.kt`, so it couldn't catch a regression un-hoisting the listener back into `MultiBackStackNavigation` only. Added `NavGraphCncSyncWiringTest.kt`, a source-structure test asserting the listener lives in the shared `AppNavigation` body and both nav hosts are called from it.
- Files changed: `app/src/main/java/com/kkc/sheettracker/ui/viewer/SheetViewerScreen.kt`, `app/src/main/java/com/kkc/sheettracker/ui/viewer/UnifiedReferenceViewer.kt`, `app/src/test/java/com/kkc/sheettracker/data/unified/UnifiedMetadataEngineTest.kt`, `app/src/test/java/com/kkc/sheettracker/navigation/NavGraphCncSyncWiringTest.kt` (new), `docs/superpowers/plans/2026-07-07-code-review-remediation-loop.md`.
- Test commands: targeted run (`UnifiedMetadataEngineTest`, `NavGraphCncSyncWiringTest`, `CncToHardwoodsSyncTest`) passed; full `.\gradlew.bat :app:testDebugUnitTest` passed; full `.\gradlew.bat :app:assembleDebug` passed. All BUILD SUCCESSFUL.
- Result: all 36 items now have implementation and test evidence that survive independent fresh-context scrutiny.
- Follow-up: per `$loop` reconciliation rules, since project files changed this round, `::potter(exit)` cannot be appended yet — one more fresh-context verification round with zero required changes is needed to confirm closure.
