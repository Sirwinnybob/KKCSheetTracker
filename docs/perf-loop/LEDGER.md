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
| D1 | Scan coordinators + change monitor | `data/ScanCoordinator.kt`, `HardwoodsScanCoordinator.kt`, `AssemblyScanCoordinator.kt`, `SpecialtyScanCoordinator.kt`, `TrackerChangeMonitor.kt`, `StaticCachePoller.kt` | in-progress |
| D2 | Progress stores + index caching | `data/ProgressStore.kt`, `HardwoodsProgressStore.kt`, `SpecialtyProgressStore.kt`, `SheetRipProgressStore.kt` | pending |
| D3 | Unified metadata engine | `data/unified/FileBackedUnifiedMetadataEngine.kt` (+ `data/unified/`) | pending |
| D4 | Repositories | `data/JobRepository.kt`, `HardwoodsRepository.kt`, `SpecialtyRepository.kt`, `SupplyRepository.kt`, `DeliveryScheduleRepository.kt`, `TimecardRepository.kt` | pending |
| D5 | Sync + update | `sync/` (Syncthing*), `update/UpdateManager.kt` | pending |

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
| _(none yet)_ | | | | |
