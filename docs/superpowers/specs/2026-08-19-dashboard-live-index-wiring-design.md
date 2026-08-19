# Dashboard-Family Live Index Wiring Design

**Status:** Approved design — ready for implementation planning on 2026-08-19.

## Context

`2026-08-18-live-cache-index-tablet-client-design.md` wired `LiveAwareUnifiedMetadataEngine`
into the Jobs tab only (`JobsTabHost`, `LegacySingleStackNavigation`, both in
`app/src/main/java/com/kkc/sheettracker/navigation/NavGraph.kt`). Its own Known
Limitations section documented, as a candidate follow-up rather than a silent gap,
that the Dashboard-family screens still read the raw
`UnifiedMetadataEngineRegistry` singleton directly — so while the socket is
connected, the Jobs tab can show fresher data than the Dashboard tab for the same
job at the same moment, a user-visible inconsistency that did not exist before that
feature (every screen previously read the same lagged file-based source).

This design closes that gap by threading the same shared live-aware engine
instance into the Dashboard-family read paths, mirroring the wiring pattern
already used for `JobsTabHost`.

### Scope correction from the original triage

The original Known Limitations note named `app/src/main/java/com/kkc/sheettracker/ui/assembly/AssemblyDashboardScreen.kt`
as one of the two offending files. Tracing actual call sites during this design's
research found that file is dead code: `AssemblyDashboardScreen(...)` is invoked
nowhere in `app/src/main/java`, only from its own test,
`app/src/test/java/com/kkc/sheettracker/ui/assembly/AssemblyDashboardScreenTest.kt`.
The live Assembly dashboard path the app actually renders is
`AssemblyDashboardContent` inside
`app/src/main/java/com/kkc/sheettracker/ui/dashboard/UnifiedModeDashboardScreen.kt`
(reached via `DashboardTabHost` in `NavGraph.kt`), which hits the same root cause
through a different call path: `assemblyStateStore.deriveJobCards()` →
`AssemblyStateStore.engine()` in `app/src/main/java/com/kkc/sheettracker/data/AssemblyStateStore.kt`.

Full trace of every Dashboard-reachable engine call site:

- **`HardwoodsDashboardContent`** (`UnifiedModeDashboardScreen.kt`, ~line 750) —
  local `remember { UnifiedMetadataEngineRegistry.getOrCreate(...) }`, calls
  `getCachedJobInfos()` / `getProgressFromIndex()`. In scope.
- **`AssemblyStateStore.engine()`** (`AssemblyStateStore.kt`, ~line 30) — resolves
  its own registry instance per call, used by `deriveJobCards()` (the Assembly
  dashboard's data source) and every other method on the class (cabinet jump,
  cabinet context, search index, cabinet parts). In scope — fixing this one
  method fixes all of them, including the two dead call sites in
  `AssemblyDashboardScreen.kt`.
- **CNC dashboard** (`CncDashboardContent`) — reads from `appStateStore.dashboardUiModel`,
  a separately-derived state flow that does not call the engine on this path. Out
  of scope; unaffected by either this design or the original one.
- **Specialty dashboard / `SpecialtyStateStore`** — its only registry call is
  `SpecialtyScanCoordinator.refreshJobOnOpen()`'s `engine.refreshJobDeep(folderName)`,
  a full per-job hydration call. That's job-detail territory, which both this
  design and the original tablet-client design explicitly exclude (job
  opening is unaffected by the live index; it hydrates full data through the
  existing per-job load path regardless). Out of scope.

## Decision

Thread the single `liveIndexEngine` (`MultiBackStackNavigation`) /
`unifiedEngine` (`LegacySingleStackNavigation`) instance that `NavGraph.kt`
already builds once per `(basePath, isDebugBuild)` down into the two bypass
points identified above, replacing their independent registry lookups. No new
components — this is parameter threading along the existing composition tree,
the same pattern already used to get `unifiedEngine` into `JobsTabHost` and
`rememberCncJobsSpec`.

### `AssemblyStateStore`

- Constructor gains a `liveEngine: UnifiedMetadataEngine` parameter.
- `engine()` is deleted; every internal call site (`getJobs()`,
  `deriveJobCards()`, `getCabinetSheetIndex()`, `resolveCabinetJump()` via
  `getCabinetContext()`, `deriveCabinetParts()`, `deriveSearchIndex()`) reads
  `liveEngine` directly instead.
- Both `NavGraph.kt` construction sites (`MultiBackStackNavigation`,
  `LegacySingleStackNavigation`) pass their existing `liveIndexEngine` /
  `unifiedEngine` value into `AssemblyStateStore(...)`.
- Recomposition safety: `AssemblyStateStore` is `remember`-keyed (in both nav
  variants) on `assemblyScanCoordinator`, which is itself
  `remember(basePath) { ... }`-keyed. `liveIndexEngine` is `remember`-keyed on
  `liveIndexRegistryEngine`, itself `remember(basePath, isDebugBuild)`-keyed.
  Both therefore rebuild together whenever `basePath` changes — no separate
  synchronization needed, no new lifecycle to manage. This was verified by
  reading the actual `remember` key lists in `NavGraph.kt`, not assumed.

### `HardwoodsDashboardContent` / `UnifiedModeDashboardSpec.Hardwoods`

- `UnifiedModeDashboardSpec.Hardwoods` gains a `liveEngine: UnifiedMetadataEngine`
  field.
- `HardwoodsDashboardContent` takes `liveEngine` as a parameter instead of its
  local `remember { UnifiedMetadataEngineRegistry.getOrCreate(...) }` block, and
  uses it for `getCachedJobInfos()` / `getProgressFromIndex()`.
- Both `DashboardTabHost` (`MultiBackStackNavigation`) and the inline `"dashboard"`
  composable (`LegacySingleStackNavigation`) pass their existing live engine
  value when building `UnifiedModeDashboardSpec.Hardwoods(...)`.

### `UnifiedModeDashboardSpec.Assembly`

- No new field needed — `AssemblyDashboardContent` already receives
  `assemblyStateStore`, and `AssemblyStateStore` now carries its own
  `liveEngine` internally per the change above. The spec itself is unchanged;
  only its constructor call sites in `NavGraph.kt` need `AssemblyStateStore`
  built with `liveEngine` passed in (already covered by the `AssemblyStateStore`
  change, since it's constructed once and shared).

### `CncDashboardContent`, `UnifiedModeDashboardSpec.Specialty`

Untouched — confirmed out of scope above.

### Dead code removal

Delete `app/src/main/java/com/kkc/sheettracker/ui/assembly/AssemblyDashboardScreen.kt`
and `app/src/test/java/com/kkc/sheettracker/ui/assembly/AssemblyDashboardScreenTest.kt`.
Confirmed unreachable from any nav path; the user chose removal over leaving it
in place during design review.

### Design doc update

Update `2026-08-18-live-cache-index-tablet-client-design.md`'s Known
Limitations section to record this gap as resolved by this design, correcting
the file reference from `AssemblyDashboardScreen.kt` to
`AssemblyStateStore.kt` / `UnifiedModeDashboardScreen.kt` per the trace above.

## Testing

- `AssemblyStateStoreTest` (new or extended): construct with a fake
  `UnifiedMetadataEngine` standing in for `liveEngine`; assert `deriveJobCards()`
  and the cabinet/search methods read through it, not a registry-resolved
  instance. Directly exercises that `engine()` is gone.
- `UnifiedModeDashboardScreenTest` (or equivalent Hardwoods dashboard test):
  assert `HardwoodsDashboardContent` renders from an injected engine rather than
  a registry lookup — e.g. by injecting a fake `UnifiedMetadataEngine` whose
  `getCachedJobInfos()` differs from whatever the real registry would return for
  the same base path, and asserting the fake's data appears.
- Manual/deployment verification: with the live socket connected on a shop
  tablet, change a job's CNC or hardwood progress via the worker, and confirm
  the Dashboard tab (Hardwoods and Assembly modes) updates within one socket
  push — not waiting on `StaticCachePoller`'s cadence — matching what the Jobs
  tab already shows.

## Non-goals

- CNC dashboard and Specialty dashboard — confirmed not to touch the engine on
  their Dashboard-reachable paths; no change needed.
- Job-detail/full data hydration (`refreshJobDeep`, per-job snapshot loads) —
  untouched, same non-goal as the original design.
- Any change to `LiveIndexClient`, `LiveAwareUnifiedMetadataEngine`'s own
  behavior, or the socket protocol — this design only changes which callers
  reach the existing decorator.
