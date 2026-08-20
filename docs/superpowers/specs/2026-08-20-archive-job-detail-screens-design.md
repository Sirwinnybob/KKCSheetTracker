# Archive Job Detail Screens — Design

**Status:** Approved, ready for planning.

## Goal

Fill in the placeholder archive job-detail route (`"archive/job/{archiveJobId}/{folderName}/{contentVersion}"`, currently a stub `Box` with the text "Archive job detail view not yet available" in both `MultiBackStackNavigation` and `LegacySingleStackNavigation`) with the real read-only-by-persistence detail experience for an archived job — this is "Task 9" from the archive tablet-client plan's own disclosed scope gap.

## Product requirement (from the user, verbatim intent)

The archived job detail view does **not** need to restrict user interaction. A user can do whatever they want in the screen — mark progress, open PDFs, add markup, everything the live screen supports. The only difference is that **none of it persists**: every write silently no-ops. This is exactly the mechanism `readOnly = true` already provides on `ProgressStore`/`HardwoodsProgressStore`/`SpecialtyProgressStore`/`PdfMarkupStore` (built in the archive tablet-client plan's Task 5, `ArchiveSession.kt`).

Explicit design goal: **reuse the same 4 job-detail screens (and their sub-screens) for both live and archived jobs** rather than building/maintaining a second, restricted set of screens. "Future proofing" — any new feature added to a live detail screen automatically works for archived jobs too, with zero extra maintenance.

## Research findings that shaped this design

- There is no existing `JobDetailScreen` singular — 4 separate top-level composables, one per work mode: `JobDetailScreen` (CNC), `HardwoodsJobDetailScreen`, `AssemblyJobDetailScreen`, `SpecialtyJobDetailScreen`. Each takes live stores/coordinators as plain composable parameters — no shared interface or session abstraction exists today.
- `ScanCoordinator`/`HardwoodsScanCoordinator`/`AssemblyScanCoordinator`/`SpecialtyScanCoordinator` are all `final class` (not open, not interfaces) — cannot be subclassed into "static" stand-ins.
- Investigated whether constructing REAL instances of these coordinator classes, pointed at a static archive directory, would work correctly: **confirmed yes**. None of them write back into the scanned directory (verified: zero write calls in `ScanCoordinator.kt` or `FileBackedUnifiedMetadataEngine.kt`). None auto-scan in `init{}` — scanning is 100% externally triggered via explicit `refresh()` calls. None run their own polling loop (that's a separate class, `TrackerChangeMonitor`, which we simply won't wire up for archive sessions). Repeated `refresh()` calls against an unchanged directory are cheap no-ops via a staleness-signature short-circuit. No Syncthing/network/`KKC_BASE_PATH` assumptions exist in any of the four classes — they operate against an arbitrary `java.io.File`.
- `HardwoodsScanCoordinator`/`SpecialtyScanCoordinator` take a `HardwoodsRepository`/`SpecialtyRepository` (not a raw `File`) and derive their base path from `repository.currentBasePath()` each refresh — these repositories' constructors need to be confirmed during planning (same plain-filesystem-reader pattern as `JobRepository` is expected, but unverified).
- `AssemblyStateStore`'s constructor takes `scanCoordinator`/`hardwoodsScanCoordinator` fields that are stored but never actually referenced elsewhere in the class body (only `progressStore`/`hardwoodsProgressStore`/`liveEngine` are used) — worth confirming as dead/vestigial wiring during implementation rather than treating as a hard functional requirement.
- None of the four coordinator classes have a `stop()`/`close()`/dispose lifecycle hook — their `CoroutineScope(SupervisorJob() + Dispatchers.IO)` runs until the process dies. The live app gets away with this because it constructs exactly one long-lived set at app startup. This is a pre-existing latent leak risk class, not something this feature introduces new risk semantics for — see Lifecycle decision below.
- The 4 PDF/viewer screens (`SheetViewerScreen`, `ReferencePdfViewerScreen`, `AssemblyViewerScreen`, `HardwoodsWorkspaceScreen`) currently build their `PdfMarkupStore` by reading `SharedPreferences("kkc_tracker")` directly inline — no parameter exists to override this today. All four use the 2-arg constructor, so `readOnly` defaults to `false` even under the app's existing `isViewOnlyMode` — a real, separate pre-existing gap.
- `PrintDocumentsBottomSheet` only needs `jobFolderName`/`jobRepository` — no store dependency at all, simplest of the group.
- `workMode: WorkMode` is a single **device-level** configuration value (this tablet's assigned department — CNC/Hardwoods/Assembly/Specialty), threaded through `AppNavigation` and every nav-graph function from one top-level source — **not** a per-job property requiring per-job mode detection. The archive job-detail route can simply dispatch to whichever one of the 4 screens matches this tablet's existing `workMode`, exactly mirroring how the live `"job/{folderName}"` route already works.

## Architecture

### `ArchiveJobSession` (expands the existing `ArchiveSession.kt`)

A single object, built once per archive-job-detail screen visit, bundling everything the reused screens need — all pointed at the archive cache job's directory, all in read-only mode:

- `progressStore: ProgressStore` (readOnly = true) — already built
- `hardwoodsProgressStore: HardwoodsProgressStore` (readOnly = true) — already built
- `specialtyProgressStore: SpecialtyProgressStore` (readOnly = true) — already built
- `pdfMarkupStore: PdfMarkupStore` (readOnly = true) — already built
- `unifiedEngine: UnifiedMetadataEngine` — already built, via `UnifiedMetadataEngineRegistry.getOrCreate`
- `jobRepository: JobRepository` — new, constructed against the archive cache directory
- `hardwoodsRepository`, `specialtyRepository` — new; exact constructor shape to be confirmed during planning
- `scanCoordinator: ScanCoordinator`, `hardwoodsScanCoordinator: HardwoodsScanCoordinator`, `assemblyScanCoordinator: AssemblyScanCoordinator`, `specialtyScanCoordinator: SpecialtyScanCoordinator` — new, **real instances** (not fakes/stubs) constructed pointed at the archive directory, per the safety findings above
- `appStateStore: AppStateStore`, `assemblyStateStore: AssemblyStateStore`, `specialtyStateStore: SpecialtyStateStore` — new, composed from the above exactly like `NavGraph.kt` composes the live equivalents today

### Navigation wiring

Both `ArchiveTabHost`'s route and the inline `LegacySingleStackNavigation` copy replace their current placeholder `Box` with: build an `ArchiveJobSession` for the route's `archiveJobId`/`folderName`/`contentVersion` args, then call whichever ONE of `JobDetailScreen`/`HardwoodsJobDetailScreen`/`AssemblyJobDetailScreen`/`SpecialtyJobDetailScreen` matches this tablet's existing `workMode` value (already in scope at both call sites) — same dispatch pattern the live job route already uses at each of its own analogous `when (workMode) { ... }` blocks.

The 4 detail screens themselves are **not modified** — they receive the session's coordinator/store instances instead of the live app-scope ones.

### Viewer/print screens

- `SheetViewerScreen`, `ReferencePdfViewerScreen`, `AssemblyViewerScreen`, `HardwoodsWorkspaceScreen`: each gains a new optional parameter (e.g. `overridePdfMarkupStore: PdfMarkupStore? = null`). When `null` (every existing live call site, unchanged), behavior is identical to today — reads `SharedPreferences` directly. When non-null (archive callers only), the screen uses the provided store instead. Live call sites additionally get `readOnly = isViewOnlyMode` wired into their own (still-`SharedPreferences`-sourced) `PdfMarkupStore` construction — closing the pre-existing gap where PDF markup ignored view-only mode.
- `PrintDocumentsBottomSheet`: archive callers just pass the session's `jobRepository` — no other change needed.

### Lifecycle

A fresh `ArchiveJobSession` (and its real coordinators) is constructed per archive-job-detail screen visit, matching exactly how the live app constructs its one long-lived set at startup — just scoped per-visit instead of per-process. **Decision: accept the same latent CoroutineScope-leak risk the live coordinators already carry** rather than building new disposal machinery these classes were never designed to support. Bounded in practice by how many distinct archived jobs a user opens in one tablet session (realistically small). Not re-litigated as a blocking concern — this mirrors existing, already-shipped risk exactly, not a new risk class introduced by this feature.

## Non-goals (explicitly deferred, not part of this plan)

- Tablet-triggered "archive a live job" UI (already-disclosed gap from the tablet-client plan; `ArchiveAdminClient.triggerArchive()` exists server-side-ready but has no caller anywhere).
- HTTP snapshot fallback for the archive library list (already-disclosed gap).
- Any UI-level indication that a screen is in archive/no-persist mode (per the product requirement: interaction should feel identical to live, not visually gated) — beyond whatever the Archive tab's own list screen already shows before entry.
