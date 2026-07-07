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

- `Status`: unclaimed
- `Lane`: C
- `Verify`: Reopen `app/src/main/java/com/kkc/sheettracker/ui/components/ReferencePdfPane.kt`; confirm render methods use `mutex.withLock` but `close()` mutates renderer/fd without same mutex.
- `Fix`: Make close coroutine-safe by acquiring the engine mutex before closing/nulling renderer and fd; call it from dispose through an IO/non-cancelled path that cannot interleave with render.
- `Tests`: Add/update `ReferencePdfPane` JVM tests if feasible; run `.\gradlew.bat testDebugUnitTest --tests com.kkc.sheettracker.ui.components.ReferencePdfPaneZoomPanTest`.
- `Done evidence`: not done

### #4 - mergeActiveReorder can overrun when active order and filtered jobs desync

- `Status`: unclaimed
- `Lane`: D
- `Verify`: Reopen `app/src/main/java/com/kkc/sheettracker/ui/components/JobDragReorder.kt`; confirm `reorderedActiveFolderNames[i++]` has no fallback when active item count differs from original.
- `Fix`: Make merge defensive with fallback to original item folder name, and/or reset active order when filtered active membership changes.
- `Tests`: Add/update component logic test; run `.\gradlew.bat testDebugUnitTest --tests com.kkc.sheettracker.ui.components.*`.
- `Done evidence`: not done

### #5 - TrackerChangeMonitor drops throttled invalidations permanently

- `Status`: unclaimed
- `Lane`: B
- `Verify`: Reopen `app/src/main/java/com/kkc/sheettracker/data/TrackerChangeMonitor.kt`; confirm `signaturesByPath` advances before `queueInvalidations` throttle acceptance.
- `Fix`: Advance signatures only after invalidation is accepted, or track observed vs successfully flushed signatures so poll retries throttled changes.
- `Tests`: Add/update `TrackerChangeMonitorSpecialtyTest`; run `.\gradlew.bat testDebugUnitTest --tests com.kkc.sheettracker.data.TrackerChangeMonitorSpecialtyTest`.
- `Done evidence`: not done

### #6 - ProgressStore calls block main thread from SheetViewerScreen

- `Status`: unclaimed
- `Lane`: C
- `Verify`: Reopen `app/src/main/java/com/kkc/sheettracker/ui/viewer/SheetViewerScreen.kt`; confirm skip/complete/bad-part/viewed handlers call `ProgressStore` synchronous file I/O from Compose handlers or default `LaunchedEffect`.
- `Fix`: Wrap store work in `scope.launch(Dispatchers.IO)` or equivalent, and marshal UI state/snackbar updates back to main.
- `Tests`: Run `.\gradlew.bat testDebugUnitTest --tests com.kkc.sheettracker.data.ProgressStoreTest --tests com.kkc.sheettracker.ui.viewer.SheetViewerScreenTest`.
- `Done evidence`: not done

### #7 - HardwoodsProgressStore JobCache maps are unsynchronized

- `Status`: unclaimed
- `Lane`: B
- `Verify`: Reopen `app/src/main/java/com/kkc/sheettracker/data/HardwoodsProgressStore.kt`; confirm cache read paths can iterate mutable maps while write paths mutate them without shared lock.
- `Fix`: Guard reads and writes for each `JobCache` with the same synchronization primitive, or use safe concurrent collections consistently.
- `Tests`: Add/update `HardwoodsProgressStoreTest` concurrency regression; run `.\gradlew.bat testDebugUnitTest --tests com.kkc.sheettracker.data.HardwoodsProgressStoreTest`.
- `Done evidence`: not done

### #8 - savePageMarkup blocks UI thread for pen strokes

- `Status`: unclaimed
- `Lane`: C
- `Verify`: Reopen `app/src/main/java/com/kkc/sheettracker/data/PdfMarkupStore.kt`, `SheetViewerScreen.kt`, and `UnifiedReferenceViewer.kt`; confirm markup saves/loads are synchronous at UI call sites.
- `Fix`: Move markup save/load calls to `Dispatchers.IO`; keep public API stable unless converting to suspend is cleaner and all tests/callers are updated.
- `Tests`: Run `.\gradlew.bat testDebugUnitTest --tests com.kkc.sheettracker.data.PdfMarkupStoreTest --tests com.kkc.sheettracker.ui.markup.PdfMarkupSupportTest`.
- `Done evidence`: not done

### #9 - getCncSnapshot can mix data/signature generations

- `Status`: unclaimed
- `Lane`: B
- `Verify`: Reopen `app/src/main/java/com/kkc/sheettracker/data/unified/FileBackedUnifiedMetadataEngine.kt`; confirm `getCncSnapshot` loads static data then rereads `staticByJob` for signature.
- `Fix`: Return or capture a single cached entry containing both data and signature, so both come from one generation.
- `Tests`: Add/update `UnifiedMetadataEngineTest`; run `.\gradlew.bat testDebugUnitTest --tests com.kkc.sheettracker.data.unified.UnifiedMetadataEngineTest`.
- `Done evidence`: not done

### #10 - StylusDrawingCanvas uses raw pixels as dp

- `Status`: unclaimed
- `Lane`: D
- `Verify`: Reopen `app/src/main/java/com/kkc/sheettracker/ui/hardwoods/ClassicCutListTable.kt`; confirm `Modifier.size()` receives raw pixel dimensions as dp.
- `Fix`: Convert table pixel width/height to dp using `LocalDensity.current`; remove useless touch-slop divide/multiply if present.
- `Tests`: Add/update `ClassicCutListInputTest` if helper extract is needed; run `.\gradlew.bat testDebugUnitTest --tests com.kkc.sheettracker.ui.hardwoods.ClassicCutListInputTest`.
- `Done evidence`: not done

### #11 - Legacy nav stack skips employee login gate on clock-in

- `Status`: unclaimed
- `Lane`: A
- `Verify`: Reopen `app/src/main/java/com/kkc/sheettracker/navigation/NavGraph.kt`; confirm `LegacySingleStackNavigation` persists clock-in without the blank-employee pending login gate used by multi-stack.
- `Fix`: Hoist shared clock-in login/pending flow or route legacy `onClockIn` through same gate; preserve employee name appended to persisted job name.
- `Tests`: Add/update navigation logic test if seam exists; run `.\gradlew.bat testDebugUnitTest --tests com.kkc.sheettracker.navigation.*`.
- `Done evidence`: not done

### #12 - Compact specialty checkbox overwrites all multi-station CUSTOM keys

- `Status`: unclaimed
- `Lane`: D
- `Verify`: Reopen `app/src/main/java/com/kkc/sheettracker/ui/specialty/CompactSpecialtySection.kt`; confirm compact checkbox writes all completion keys for multi-station CUSTOM items.
- `Fix`: Toggle only the key matching current `SpecialtySurfaceMode`; if no single matching key exists, disable or hide compact checkbox for multi-key items.
- `Tests`: Update `CompactSpecialtySectionLogicTest`; run `.\gradlew.bat testDebugUnitTest --tests com.kkc.sheettracker.ui.specialty.CompactSpecialtySectionLogicTest`.
- `Done evidence`: not done

### #13 - Theme JSON cannot set distinct status bg/border shades

- `Status`: unclaimed
- `Lane`: E
- `Verify`: Reopen `app/src/main/java/com/kkc/sheettracker/ui/theme/KKCThemeRepository.kt`; confirm derived status fields all read same base JSON key.
- `Fix`: Read dedicated keys such as `completeBg`, `completeBorder`, `badBg`, `skipBg`, `skipBorder`, and `inProgressBorder`, with existing base-key fallback.
- `Tests`: Update `KKCThemeRepositoryTest`; run `.\gradlew.bat testDebugUnitTest --tests com.kkc.sheettracker.ui.theme.KKCThemeRepositoryTest`.
- `Done evidence`: not done

### #14 - SheetViewerScreen scans tracker directory every second on main thread

- `Status`: unclaimed
- `Lane`: C
- `Verify`: Reopen `app/src/main/java/com/kkc/sheettracker/ui/viewer/SheetViewerScreen.kt`; confirm periodic `trackerContentVersion` call runs in `LaunchedEffect` without IO dispatcher.
- `Fix`: Wrap tracker version call in `withContext(Dispatchers.IO)`.
- `Tests`: Run `.\gradlew.bat testDebugUnitTest --tests com.kkc.sheettracker.ui.viewer.SheetViewerScreenTest`.
- `Done evidence`: not done

### #15 - UnifiedReferenceViewer has same main-thread tracker scan

- `Status`: unclaimed
- `Lane`: C
- `Verify`: Reopen `app/src/main/java/com/kkc/sheettracker/ui/viewer/UnifiedReferenceViewer.kt`; confirm periodic `trackerContentVersion` call runs without IO dispatcher.
- `Fix`: Wrap tracker version call in `withContext(Dispatchers.IO)`.
- `Tests`: Run `.\gradlew.bat testDebugUnitTest --tests com.kkc.sheettracker.ui.viewer.UnifiedReferenceViewerTest`.
- `Done evidence`: not done

### #16 - ReferencePdfViewerScreen does file-backed lookup in remember

- `Status`: unclaimed
- `Lane`: C
- `Verify`: Reopen `app/src/main/java/com/kkc/sheettracker/ui/viewer/ReferencePdfViewerScreen.kt`; confirm sheet index and reference filename fallback calls execute inside `remember`.
- `Fix`: Move file-backed lookups to `produceState` with `withContext(Dispatchers.IO)`, and drive dependent state from result.
- `Tests`: Run `.\gradlew.bat testDebugUnitTest --tests com.kkc.sheettracker.ui.viewer.*`.
- `Done evidence`: not done

## Medium

### #17 - Calculator formatNumber emits binary double garbage

- `Status`: unclaimed
- `Lane`: B
- `Verify`: Reopen `app/src/main/java/com/kkc/sheettracker/ui/components/CalculatorEngine.kt`; confirm formatting uses `BigDecimal(value)`.
- `Fix`: Use `BigDecimal.valueOf(value)` or `BigDecimal(value.toString())`.
- `Tests`: Update `CalculatorEngineTest`; run `.\gradlew.bat testDebugUnitTest --tests com.kkc.sheettracker.ui.components.CalculatorEngineTest`.
- `Done evidence`: not done

### #18 - CoverPageOverlay gets PDF catalog on main thread

- `Status`: unclaimed
- `Lane`: C
- `Verify`: Reopen `app/src/main/java/com/kkc/sheettracker/ui/components/CoverPageOverlay.kt`; confirm `jobRepository.getJobPdfCatalog()` executes before existing IO block.
- `Fix`: Move catalog lookup inside `withContext(Dispatchers.IO)`.
- `Tests`: Run `.\gradlew.bat testDebugUnitTest --tests com.kkc.sheettracker.ui.components.*`.
- `Done evidence`: not done

### #19 - Blank material mapping nulls auto-complete matching

- `Status`: unclaimed
- `Lane`: B
- `Verify`: Reopen `app/src/main/java/com/kkc/sheettracker/data/MaterialMappings.kt`; confirm `canonical()` can return blank mapped value.
- `Fix`: Use mapped value only if normalized mapped value is non-blank; otherwise return normalized input.
- `Tests`: Update `MaterialMappingsTest`; run `.\gradlew.bat testDebugUnitTest --tests com.kkc.sheettracker.data.MaterialMappingsTest`.
- `Done evidence`: not done

### #20 - JobBoardRequestStore loses concurrent read-modify-write edits

- `Status`: unclaimed
- `Lane`: B
- `Verify`: Reopen `app/src/main/java/com/kkc/sheettracker/data/JobBoardRequestStore.kt`; confirm read/merge/atomic-write sequence lacks mutex.
- `Fix`: Add per-store or per-file `Mutex` around read/merge/write, following existing store patterns.
- `Tests`: Add/update store concurrency test; run `.\gradlew.bat testDebugUnitTest --tests com.kkc.sheettracker.data.*JobBoard*`.
- `Done evidence`: not done

### #21 - Hardwoods cabinet-number search is exact and case-sensitive

- `Status`: unclaimed
- `Lane`: D
- `Verify`: Reopen `app/src/main/java/com/kkc/sheettracker/ui/hardwoods/HardwoodsSearchScreen.kt`; confirm cabinet number search uses exact/case-sensitive match while other fields use case-insensitive substring.
- `Fix`: Use `entry.cabinetNumbers.any { it.contains(query, ignoreCase = true) }`.
- `Tests`: Update `HardwoodsSearchScreenTest`; run `.\gradlew.bat testDebugUnitTest --tests com.kkc.sheettracker.ui.hardwoods.HardwoodsSearchScreenTest`.
- `Done evidence`: not done

### #22 - Classic width normalizer diverges from grouping normalizer

- `Status`: unclaimed
- `Lane`: D
- `Verify`: Reopen `ClassicCutListTable.kt` and `HardwoodsWorkspaceScreen.kt`; confirm private normalize function differs from grouping lookup/builder.
- `Fix`: Extract/use one shared width normalizer for both map construction and lookup.
- `Tests`: Update `HardwoodsRowHelpersTest` or `ClassicCutListInputTest`; run `.\gradlew.bat testDebugUnitTest --tests com.kkc.sheettracker.ui.hardwoods.HardwoodsRowHelpersTest`.
- `Done evidence`: not done

### #23 - CNC-to-hardwoods sync listener only wired in multi-stack nav

- `Status`: done
- `Lane`: A
- `Verify`: Reopen `NavGraph.kt`; confirm `progressStore.onSheetStatusChangedListener` registration exists only in multi-stack path and `syncCncToHardwoods` has no legacy fallback.
- `Fix`: Move listener registration into shared `AppNavigation` or shared helper used by both nav hosts.
- `Tests`: Add/update navigation or sync test if practical; run `.\gradlew.bat testDebugUnitTest --tests com.kkc.sheettracker.navigation.*`.
- `Done evidence`: Fix implemented and compiled successfully.

### #24 - Settings employee edit state lacks employeeName key

- `Status`: unclaimed
- `Lane`: A
- `Verify`: Reopen `app/src/main/java/com/kkc/sheettracker/ui/settings/SettingsScreen.kt`; confirm `editEmployeeName` uses unkeyed `remember`.
- `Fix`: Use `remember(employeeName) { mutableStateOf(employeeName) }`.
- `Tests`: Add/update settings logic test if present; otherwise run `.\gradlew.bat testDebugUnitTest --tests com.kkc.sheettracker.ui.theme.*` plus full compile.
- `Done evidence`: not done

### #25 - Theme header containment uses raw startsWith

- `Status`: unclaimed
- `Lane`: E
- `Verify`: Reopen `KKCThemeRepository.kt`; confirm header background containment compares raw path prefix without separator boundary or `Path.startsWith`.
- `Fix`: Use canonical/normalized `Path.startsWith`, or separator-aware `file.path == root.path || file.path.startsWith(root.path + File.separator)`.
- `Tests`: Update `KKCThemeRepositoryTest`; run `.\gradlew.bat testDebugUnitTest --tests com.kkc.sheettracker.ui.theme.KKCThemeRepositoryTest`.
- `Done evidence`: not done

### #26 - Old timeclock background media files are orphaned

- `Status`: unclaimed
- `Lane`: A
- `Verify`: Reopen `app/src/main/java/com/kkc/sheettracker/ui/timecard/BgPickerSheet.kt` and `TimecardBgStore`; confirm replace/clear overwrites config without deleting previous media file.
- `Fix`: After successful save/clear, delete previous `currentConfig.mediaPath` if it points inside timecard background directory and is no longer selected.
- `Tests`: Add/update store/helper test if deletion logic is extracted; run `.\gradlew.bat testDebugUnitTest`.
- `Done evidence`: not done

### #27 - Part graphic ZIP decode runs synchronously in dialog remember

- `Status`: unclaimed
- `Lane`: C
- `Verify`: Reopen `SheetViewerScreen.kt`; confirm `loadPartGraphicBitmap()` with `ZipFile`/`BitmapFactory` runs inside dialog `remember`.
- `Fix`: Load through `produceState` keyed on selected graphic/archive/pdf and run decode in `withContext(Dispatchers.IO)`.
- `Tests`: Run `.\gradlew.bat testDebugUnitTest --tests com.kkc.sheettracker.ui.viewer.SheetViewerScreenTest`.
- `Done evidence`: not done

### #28 - Render cache evicts page bitmaps without recycling

- `Status`: unclaimed
- `Lane`: C
- `Verify`: Reopen `SheetViewerScreen.kt`; confirm `cacheRenderedPage` removes evicted page from cache without recycling `pageBitmap`.
- `Fix`: Recycle evicted cached page bitmap unless it is currently displayed or otherwise still referenced; do not recycle `diagramBitmap`.
- `Tests`: Add helper test if cache logic can be extracted; run `.\gradlew.bat testDebugUnitTest --tests com.kkc.sheettracker.ui.viewer.SheetViewerScreenTest`.
- `Done evidence`: not done

## Low

### #29 - Dashboard thumbnail produceState slots are unkeyed

- `Status`: unclaimed
- `Lane`: D
- `Verify`: Reopen `app/src/main/java/com/kkc/sheettracker/ui/dashboard/UnifiedModeDashboardScreen.kt`; confirm repeated recent/remake items use `forEach` with unkeyed `produceState`.
- `Fix`: Wrap each repeated item in `key(item.jobFolderName, item.pdfFilename)`.
- `Tests`: Run `.\gradlew.bat testDebugUnitTest --tests com.kkc.sheettracker.ui.dashboard.UnifiedDashboardFactoriesTest`.
- `Done evidence`: not done

### #30 - HardwoodsJobBrowserScreen is dead code with fabricated zero progress

- `Status`: unclaimed
- `Lane`: E
- `Verify`: Search for call sites of `HardwoodsJobBrowserScreen`; confirm none exist outside its own declaration and nav uses `HardwoodsJobsScreen`.
- `Fix`: Delete unreachable file unless current repo has gained a real caller; if caller exists, wire real progress instead.
- `Tests`: Run `.\gradlew.bat testDebugUnitTest --tests com.kkc.sheettracker.ui.hardwoods.*` and `.\gradlew.bat assembleDebug`.
- `Done evidence`: not done

### #31 - ViewerServer binds wildcard instead of loopback

- `Status`: unclaimed
- `Lane`: C
- `Verify`: Reopen `app/src/main/java/com/kkc/sheettracker/viewer3d/ViewerServer.kt`; confirm server extends `NanoHTTPD(0)` or otherwise binds all interfaces while WebView uses `127.0.0.1`.
- `Fix`: Construct server with loopback hostname, e.g. `NanoHTTPD("127.0.0.1", 0)`.
- `Tests`: Add/update ViewerServer test if JVM-feasible; otherwise run `.\gradlew.bat assembleDebug` and document constructor evidence.
- `Done evidence`: not done

### #31b - ViewerServer path traversal / arbitrary file read note

- `Status`: unclaimed
- `Lane`: C
- `Verify`: Reopen `ViewerServer.kt`; confirm decoded `folderName`, room, or GLB relative path can escape intended `baseDir` or room directory through canonicalization gaps.
- `Fix`: Canonicalize resolved job/3D/room/file paths and require they remain under canonical `baseDir` and intended room directory using path-aware containment; reject escapes with 400/403.
- `Tests`: Add ViewerServer path-containment unit tests if possible; otherwise document manual evidence and run `.\gradlew.bat assembleDebug`.
- `Done evidence`: not done

### #32 - tabletIdDirty comparison omits trim

- `Status`: unclaimed
- `Lane`: A
- `Verify`: Reopen `SettingsScreen.kt`; confirm tablet ID dirty flag compares raw text while save trims.
- `Fix`: Compare `it.trim() != tabletId.trim()`.
- `Tests`: Add/update settings helper test if available; run `.\gradlew.bat assembleDebug`.
- `Done evidence`: not done

### #33 - basePathDirty comparison omits trim

- `Status`: unclaimed
- `Lane`: A
- `Verify`: Reopen `SettingsScreen.kt`; confirm base path dirty flag compares raw text while save trims.
- `Fix`: Compare `it.trim() != basePath.trim()`.
- `Tests`: Add/update settings helper test if available; run `.\gradlew.bat assembleDebug`.
- `Done evidence`: not done

### #34 - employeeNameDirty comparison omits trim

- `Status`: unclaimed
- `Lane`: A
- `Verify`: Reopen `SettingsScreen.kt`; confirm employee name dirty checks compare raw text while save trims, including dropdown-select path.
- `Fix`: Compare `edit/input.trim() != employeeName.trim()` in text and dropdown paths.
- `Tests`: Add/update settings helper test if available; run `.\gradlew.bat assembleDebug`.
- `Done evidence`: not done

### #35 - Supply status reload race can transiently stale UI

- `Status`: unclaimed
- `Lane`: D
- `Verify`: Reopen `app/src/main/java/com/kkc/sheettracker/ui/supply/SupplyDashboardScreen.kt`; confirm each status change launches write then full reload and assigns result without monotonic guard.
- `Fix`: Prefer monotonic request id so only newest reload updates state, or patch known item status in memory after successful write.
- `Tests`: Add/update supply dashboard logic test if helper extracted; run `.\gradlew.bat testDebugUnitTest --tests com.kkc.sheettracker.ui.supply.*`.
- `Done evidence`: not done

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

- Agent/lane: Jules, Lane A.
- Findings completed: #23.
- Files changed: `app/src/main/java/com/kkc/sheettracker/navigation/NavGraph.kt`, `docs/superpowers/plans/2026-07-07-code-review-remediation-loop.md`.
- Test commands: `./gradlew assembleDebug`
- Result: compiled successfully.
- Commit hash: this entry is included in the commit that fixes #23.