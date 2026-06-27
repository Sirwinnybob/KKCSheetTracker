# Perf-Loop Findings (parked backlog)

Items discovered mid-pass that were **out of scope or above the low-risk line.** Logged here so the
pass stays focused and nothing is forgotten. Do NOT act on these unless the current subsystem covers
them and they're low-risk.

Format per item:

```
### <short title>
- **Where:** file:line(s)
- **Type:** perf | bug | cleanup
- **Risk:** low (deferred only by scope) | medium | high
- **Found in pass:** <subsystem / date>
- **What & why:** one or two lines.
- **Suggested fix:** brief.
```

For large architectural changes / migrations, write a full proposal in `migrations/<name>.md` instead
and leave a one-line pointer here.

---

### N+1: per-job `updateJobInState` re-scans every job via `listJobsFromCacheOnly()`
- **Where:** `data/ScanCoordinator.kt:218-247` (`updateJobInState`), reached from the deep-load loop `ScanCoordinator.kt:163-171` and from `StaticCachePoller` fan-out. Same shape in `AssemblyScanCoordinator.kt:114-140`, and at the repository layer in `HardwoodsRepository.getUpdatedJob` (`HardwoodsRepository.kt:53-64`) and `SpecialtyRepository.getUpdatedJob` (`SpecialtyRepository.kt:40-44`) — both call `listJobsFromCacheOnly()` then `.find { it.folderName == … }` for a single job. Backed by `unified/FileBackedUnifiedMetadataEngine.kt:232-290` (`listJobsFromCacheOnly`). (D4-pass note 2026-06-27: these repo `getUpdatedJob` callers are the same N+1; fix them all together with the single-job board-merged accessor.)
- **Type:** perf
- **Risk:** medium (needs an additive engine API — belongs to subsystem D3)
- **Found in pass:** D1 / 2026-06-27
- **What & why:** `updateJobInState(folderName)` only needs one job's `UnifiedJobInfo` (for the board-config fields: `lineupPosition`, `labels`, `hiddenFromProduction`, `isPending`, `boardSection`), but it calls `listJobsFromCacheOnly()`, which loops over *every* job dir doing a `DeploymentGateRules.evaluate` file read + `cache_static.json` stat, reads `job_board.json`, and does a full sort. In the deep-load loop this runs once per folder → O(K·N); on a cold start where K≈N this is O(N²). `StaticCachePoller` triggers the same per changed job.
- **Suggested fix:** add a single-job accessor to `UnifiedMetadataEngine`, e.g. `fun getMergedJobInfo(folderName: String): UnifiedJobInfo?`, that loads the one `cache_static.json` (reuse `loadJobFromCacheFile`) and merges the board config for just that folder (`readJobBoardConfig()[folderName]`). Point both `updateJobInState` implementations at it. Cannot reuse `loadJobFromCacheFile` directly today — it skips the board-config merge, so swapping it in would drop `labels`/`isPending`/`boardSection` (a regression).
- **D3-pass investigation (2026-06-27):** the interface already has `getJobInfo(folderName): UnifiedJobInfo?` (`UnifiedMetadataEngine.kt:12`), BUT the impl (`FileBackedUnifiedMetadataEngine.kt:229-230`) returns the **raw** cached `data.jobInfo` with **no** `job_board.json` merge — so it returns empty `labels`/`isPending`/`boardSection` and cannot be swapped in as-is. The merge reads one file (`job_board.json`) for the whole board, and `lineupPosition` in the cache-only path comes from the cache file's stored value (not a computed index), so a single-job board-merging accessor IS feasible. Either add a new `getMergedJobInfo` (leave `getJobInfo` raw for existing callers) or change `getJobInfo` to merge (verify its current callers tolerate the I/O + populated board fields first). Still medium — touches engine API + ripples to D1/D4 coordinators; left for a deliberate change, not the loop.

### Deep-parse path calls full `listJobs()` to get one job's lineupPosition (compounds the deep-load N+1)
- **Where:** `unified/FileBackedUnifiedMetadataEngine.kt:628` inside `loadStaticJobData`
- **Type:** perf
- **Risk:** medium (engine-internal, but lineupPosition derivation depends on global ordering)
- **Found in pass:** D3 / 2026-06-27
- **What & why:** when a job's static cache is missing/stale, `loadStaticJobData` runs the full deep parse AND calls `listJobs()` — a complete base-dir scan (every job's deployment-gate eval + `job_board.json` read + sort) — solely to look up this one job's `lineupPosition`/`labels`/`isPending`/`boardSection`. In a deep-load batch (`refreshJobDeep` per folder in `needsDeepLoad`) that's an extra O(N) per parsed job → O(K·N), stacking on top of the coordinator-level N+1 above.
- **Suggested fix:** memoize `listJobs()` (or just the folder→ordering/board-config map it produces) for the duration of a deep-load batch, keyed by a cheap `job_board.json` + dir-listing signature; or thread the board-config map into the batch so each job doesn't re-derive the global ordering. Needs care: ordering must stay correct if files change mid-batch. Pairs naturally with the single-job-accessor fix above.

### `getCncSnapshot` rebuilds the CNC search index on every call; some callers discard it
- **Where:** `unified/FileBackedUnifiedMetadataEngine.kt:316-321` (`getCncSnapshot` → `buildCncSearchIndex` `:873`)
- **Type:** perf
- **Risk:** medium (new in-memory cache or API split — engine surface)
- **Found in pass:** D3 / 2026-06-27
- **What & why:** `getCncSnapshot` calls `buildCncSearchIndex(cncJob)` every time, allocating a `PartSearchEntry` per part across all materials/pages. Of the call sites, two only use `.job` and throw the index away: `JobRepository.kt:70` (D4) and the engine's own `resolveCabinetParts` (now fixed this pass to read `loadStaticJobData(...).cncJob` directly). `JobRepository:70` still pays the full build per call.
- **Suggested fix:** memoize the search index in-memory keyed by `(jobFolderName, staticSignature)` and invalidate it alongside `staticByJob` in `invalidateJob`/`invalidateAll`; or split the API into `getCncJob()` (no index) and `getCncSearchIndex()`. The deep-parse path can't store it in `StaticJobData` because that type is the Gson schema for the server-written `cache_static.json` (adding a field = on-disk format change). Deferred — also touches the D4 `JobRepository` caller.

### Per-job `updateJobInState` rebuilds the entire CNC search index each call
- **Where:** `data/ScanCoordinator.kt:237-238`
- **Type:** perf
- **Risk:** low (deferred only by scope — entangled with the N+1 fix above; safest to do together in D3/D2)
- **Found in pass:** D1 / 2026-06-27
- **What & why:** `updatedSearch = current.snapshot.searchIndex.filter { it.jobFolderName != folderName } + snapshot.searchIndex` re-filters and re-concatenates the whole search index for a single-job update. In the deep-load loop that's O(K·M) over the full index M, plus K separate `StateFlow` emissions (K downstream recompositions).
- **Suggested fix:** when a batch of folders is deep-loaded, coalesce into one re-projection + one state emission instead of per-folder. Note: changes "jobs appear incrementally" to "appear as a batch" — confirm that UX trade-off with the owner before applying. Best handled alongside the D3 accessor work.

### Dead warmup branch in `TrackerChangeMonitor.flushPendingNow` (possible latent bug)
- **Where:** `data/TrackerChangeMonitor.kt:326-330`
- **Type:** bug
- **Risk:** medium (correct behavior is ambiguous — needs owner intent)
- **Found in pass:** D1 / 2026-06-27
- **What & why:** the block `if (now < startupWarmupUntilMs && viewerInteraction.value) { scheduleFlushLocked(); return }` is only reached after the preceding `if (viewerInteraction.value) return` has already returned, so `viewerInteraction.value` is false here and the condition is effectively always false (dead). If the intent was "defer the flush during the startup warmup window," that deferral never happens. Fixing it to actually defer (`now < startupWarmupUntilMs`) would be a behavior change, so not applied.
- **Suggested fix:** confirm intent. If warmup-deferral is desired, drop the `&& viewerInteraction.value`. If not, delete the dead block.

### CNC `ProgressStore` write path: full JSON re-parse + re-serialize per appended action
- **Where:** `data/ProgressStore.kt:254-275` (`appendAction`), amplified by `markSheetComplete` `data/ProgressStore.kt:473-485`
- **Type:** perf
- **Risk:** medium (touches the tracker-write cadence that TrackerChangeMonitor + other tablets observe; changes the append API)
- **Found in pass:** D2 / 2026-06-27
- **What & why:** every `appendAction` does a synchronous `loadTabletProgress` (readText + full Gson parse + sanitize over ALL prior actions) followed by `saveTabletProgress` (copy the whole actions list + serialize + writeText). For a job worked all day the action log is large, so each append is O(A). `markSheetComplete` calls `appendAction` (2 + number-of-draft-bad-parts) times, i.e. O(A·K) per sheet completion, each a separate disk write + `bumpProgressVersion()`. The sibling stores (`HardwoodsProgressStore`, `SpecialtyProgressStore`) already avoid this with an in-memory `cacheByJob`/`resolvedCacheByJob` + async/atomic persist; the CNC store is the laggard.
- **Suggested fix:** mirror the Hardwoods pattern — keep an in-memory per-job action list, append in memory + update the index, and persist asynchronously (debounced/coalesced) under a per-job write mutex. Minimum viable contained step: batch the multiple `appendAction` calls inside `markSheetComplete`/`resolveBadPartsOnSheet` into a single load→append-all→save. Verify the change-monitor still sees one coalesced write and that crash-durability is acceptable (async persist). Deferred — write cadence is observed by sync.

### Update check runs APK-archive parsing + external-storage scan on the main thread
- **Where:** `update/UpdateManager.kt:121` (`checkForUpdates`) → `findUpdateDirectory`/`findReleaseUpdateDirectory`/`checkForSelfUpdatesInDirectory`/`checkForExternalUpdatesInDirectory` → `getApkInfo`/`getApkVersionCode` (`getPackageArchiveInfo`). Called on the main thread from `MainActivity.kt:115` (`onCreate`) and `MainActivity.kt:499` (`onStart`, every foreground).
- **Type:** perf
- **Risk:** medium (threading-model change; the method mutates Compose `mutableStateOf` and shows `AlertDialog`s, which must stay on main, and it lives in the outward-facing update flow)
- **Found in pass:** D5 / 2026-06-27
- **What & why:** each foreground triggers a synchronous main-thread sweep of the external-storage update folders: `listFiles()` + `File.exists()/isDirectory` stats, and `PackageManager.getPackageArchiveInfo(apk, 0)` which **parses each APK archive** (tens of ms per APK, worse on networked/SD storage). `checkForExternalUpdatesInDirectory` even parses every APK in the folder (incl. self/other) before filtering to the two `externalApps`. On a slow drive this janks app start and every resume.
- **Suggested fix:** split `checkForUpdates` into a background scan (run the directory walk + APK parsing on `Dispatchers.IO`, returning the resolved dirs + a `Map<pkg, ApkInfo>`) and a main-thread apply step that sets the Compose state / shows the manual-path dialog. Have `MainActivity` call it from `lifecycleScope.launch`. Keep all `mutableStateOf` writes and dialog calls on main. Verify the no-permission early-return (`requestStoragePermission`) and the manual-path re-entrancy (`showManualPathDialog` → `checkForUpdates`) still sequence correctly. Outward-facing update flow — deliberate change, not the loop.

### Minor: `findUpdateDirectory`/`findReleaseUpdateDirectory` duplicate the custom-path read + job-folder walk
- **Where:** `update/UpdateManager.kt:184-239`
- **Type:** cleanup
- **Risk:** low (deferred — sits in the outward-facing update flow; bundle with the threading fix above)
- **Found in pass:** D5 / 2026-06-27
- **What & why:** both methods re-read the `custom_update_path` pref and walk `JOB_FOLDER_NAMES`; the only real difference is the debug `.Testing_Updates` subfolder vs release `.Updates`/`Updates` set and whether `resolvedUpdatePath` is assigned. Two SharedPreferences reads + two external-storage walks per `checkForUpdates`.
- **Suggested fix:** factor the shared walk into one helper parameterized by the subfolder list + whether to record `resolvedUpdatePath`. Do it alongside the main-thread fix so the update flow is only restructured once.

### `TrackerChangeMonitor` flushJob self-clear never fires (`=== this` compares Job to CoroutineScope)
- **Where:** `data/TrackerChangeMonitor.kt:306-308`
- **Type:** bug (benign today)
- **Risk:** low (deferred — subtle, verify carefully)
- **Found in pass:** D1 / 2026-06-27
- **What & why:** inside `scope.launch { ... finally { synchronized(lock) { if (flushJob === this) flushJob = null } } }`, `this` is the launch block's `CoroutineScope` receiver, not the coroutine `Job`, so `flushJob === this` is always false and `flushJob` is never nulled by the finally. Currently harmless because `scheduleFlushLocked` guards on `existingJob?.isActive == true` (a completed job reports `isActive == false`), so the stale reference is ignored. Cleanup-only, no functional impact observed.
- **Suggested fix:** capture the launched job (`val job = scope.launch { ... }; flushJob = job`) and compare `flushJob === job`, or just rely on the `isActive` guard and remove the ineffective self-clear. Verify the `===` even compiles as intended (Kotlin normally forbids identity comparison of unrelated types).
