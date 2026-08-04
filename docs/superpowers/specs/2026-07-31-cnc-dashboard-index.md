# CNC Dashboard from Cache Index + Progress Store

## Data Sources

| Widget | Source | Reads cache_static? |
|--------|--------|---------------------|
| Total jobs | `getCachedJobInfos().size` | No |
| Total/done/bad/skipped sheets | `getProgressFromIndex().cnc` aggregated | No |
| Recent in-progress materials | `getProgressFromIndex().cnc.materials` filtered for partial progress | No |
| Incomplete remakes | `getProgressFromIndex().cnc.materials` where `isRemake && done < total` | No |
| Bad parts list (on tap) | `ProgressStore` per-sheet status | No |
| Skipped list (on tap) | `ProgressStore` per-sheet status | No |
| Recent jobs (tablet-local) | Tablet-local `SharedPreferences` or in-memory | No |

## How it works

1. Dashboard reads `engine.getCachedJobInfos()` for job list (instant)
2. For each job, calls `engine.getProgressFromIndex().cnc` for summary data
3. Aggregates totals across all jobs
4. Builds `DashboardUiModel` directly — bypasses `AppStateStore.derive()`
5. Bad parts/skipped lists load from `ProgressStore` on tap (same as current)

## Removes dependency on

- `AppStateStore.derive()` (which needs full snapshot.jobs with materials)
- `scanState.snapshot.jobs` (empty in new architecture)
- `cache_static.json` — never touched by dashboard

## Schema unchanged

- `DashboardUiModel` fields stay the same
- `DashboardWidgetFactories.buildCncDashboardWidgets()` — no changes needed
- Only changes: how `DashboardUiModel` is populated (from cache_index instead of from derive())
