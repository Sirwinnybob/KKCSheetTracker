# Lightning-Fast Jobs List from cache_index

> **Goal:** Jobs list screen renders immediately from cache_index.json (~2 KB per job). Full cache_static.json (~300 KB) loads only on tap, per-job, on demand.

## Architecture

```
App launch
  └─ scanJobsFromCacheOnly()
       └─ listJobsFromCacheOnly()    ← reads cache_index.json (2 KB/job)
       └─ NO getCncSnapshot()        ← skip full load
       └─ scanState.snapshot.jobs = empty

Jobs list screen
  └─ rememberCncJobsSpec
       └─ engine.listJobsFromCacheOnly()        ← light job info + board config
       └─ engine.getProgressFromIndex()         ← card progress bars
       └─ Card renders immediately              ← no cache_static touched

User taps a job
  └─ engine.getCncSnapshot(folderName)          ← reads cache_static.json (300 KB)
       └─ Job with full materials/sheets loaded
       └─ Cached in staticByJob for navigation within job
       └─ Released when user returns to list
```

## Changes

### 1. scanJobsFromCacheOnly() — skip heavy load

`ScanCoordinator.kt:scanJobsFromCacheOnly()` only runs `listJobsFromCacheOnly()`, returns empty jobs/search/issues. Full `getCncSnapshot()` removed from this path.

### 2. rememberCncJobsSpec — light list

Already done. Uses `engine.listJobsFromCacheOnly()` + `engine.getProgressFromIndex()` to build cards. No dependency on `scanState.snapshot.jobs`.

### 3. Downstream screens — load on tap

Every screen currently reading `scanState.snapshot.jobs` must call `engine.getCncSnapshot()` directly:

| Screen | File | What it needs |
|--------|------|---------------|
| Job detail | `JobDetailScreen.kt` | `scanState.snapshot.jobs.find` → full Job |
| CNC sheet viewer | `SheetViewerScreen.kt` | `scanState.snapshot.jobs.find` → full Job |
| App state derivation | `AppStateStore.kt` | `derive(snapshot.jobs)` → jobUiModels |
| Assembly cards | `AssemblyStateStore.kt` | CNC progress overlay |

Pattern: each screen replaces `scanState.snapshot.jobs.find { it.folderName == x }` with `engine.getCncSnapshot(x)`, which loads from cache or reads cache_static.json if not yet loaded.

### 4. What stays

- `updateJobsInState()` — still loads full `getCncSnapshot()` per job when deep loads complete or user taps
- `staticByJob` cache — prevents re-reading cache_static.json for same job during navigation
- `cacheIndexByJob` — populated during list scan, used for card progress
- All stale detection, fallback, invalidation logic — untouched

## Constraints

- `scanState.snapshot.jobs` is empty until `updateJobsInState()` runs
- Screens that read `scanState.snapshot.jobs` without calling `getCncSnapshot()` first will see nothing
- No schema changes to cache_index.json or cache_static.json
- `UnifiedMetadataEngine` interface unchanged (already has `getCncSnapshot`, `listJobsFromCacheOnly`, `getProgressFromIndex`)

## Fallback

Jobs without cache_index.json: `listJobsFromCacheOnly()` returns them in `needsDeepLoad`. Background deep load fires `updateJobsInState()` which populates `scanState.snapshot.jobs` with full data. These jobs appear in list with empty progress initially.
