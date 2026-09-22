# CNC Dashboard idle flicker — "data goes away and comes back"

**Date:** 2026-09-21
**Status:** Root cause identified on live hardware. No code, data, or configuration changes were made.
**Device / build:** Galaxy Tab S8 Ultra (`SM-X800`), Android 16 — `com.kkc.sheettracker` **8.5.1 (versionCode 80501)**, **release build** (installed `pkgFlags` has no `DEBUGGABLE`).
**Symptom:** With the tablet sitting idle on the CNC Dashboard and no operator input, the "Overall Progress" totals (jobs tracked / sheets), the Quality Review alert row, and the Recent In-Progress + Incomplete Remakes cards disappear for 1–3 s and then come back, repeatedly. No crash, no error banner, no visible spinner.

## 1. How it was monitored

Two bounded `adb`-only windows (no rebuild, no app change, no data change):

| Window | Capture |
|---|---|
| 12:49:43 – 12:53:43 | tag-filtered logcat + unfiltered logcat |
| 12:56:40 – 12:58:11 | logcat + 55 `screencap` frames + a `uiautomator` text dump at every frame where the screen changed |

```powershell
adb logcat -v threadtime -s KKC_APP_STATE:V KKC_SCAN:V KKC_PROGRESS:V StaticCachePoller:V `
  LiveIndexClient:V CacheIndex:V TrackerChangeMonitor:V DeliveryScheduleLiveClient:V `
  AndroidRuntime:E ActivityManager:E *:S
```

### 1.1 The logging blind spot that hides this bug

`app/src/main/java/com/kkc/sheettracker/logging/AppLog.kt:8-9` gates all `AppLog` output on build type:

```kotlin
internal fun shouldEmitAppLog(priority: AppLogPriority, isDebugBuild: Boolean): Boolean =
    isDebugBuild || priority == AppLogPriority.WARN || priority == AppLogPriority.ERROR
```

Production tablets run release builds, so every `AppLog.d` / `AppLog.i` site is silent — including the lines that would show this bug directly:

| Site | Would have shown | On the release tablet |
|---|---|---|
| `AppStateStore.kt:153-159` — `AppLog.i(KKC_APP_STATE, "derive_done … index_jobs=N …")` | the job count the dashboard totals are computed from | silent |
| `ProgressStore` / engine index-rebuild `AppLog.d` lines | per-job index invalidation | silent |
| `StaticCachePoller.kt:80,89,140` | 20 s index/gate poll start/stop/change | silent |
| `TrackerChangeMonitor` invalidation dispatch | tracker-dir change detection | silent |
| `ScanCoordinator.kt:176` — `Log.e("KKC_SCAN", "runRefresh EXCEPTION…")` | refresh failures | visible (Log.e) |
| `LiveIndexClient.kt:131,140,158` | socket open / snapshot / close | visible (`Log.d`) |
| `LiveIndexClient.kt:144-151` — `delta` frames | applied silently | **never logged at all** |

So on a release tablet the dashboard's data path is effectively unobservable in logcat, which is why watching "the dashboard logs" showed nothing.

## 2. What was observed

### 2.1 Reproduction — dashboard text while idle (no input at any point)

| Dump | Time | Overall Progress | Quality alert row | Recent In-Progress | Incomplete Remakes |
|---|---|---|---|---|---|
| ui_001 | 12:56:40 | **432 / 493 sheets, 11 jobs, 88%** | `1 skipped sheet need review`, `61 sheets remaining` | 2 cards (`592 - GIELISH` ×2) | 3 cards, incl. `001 REMAKE - 3_4 Alder / 657 - TRUNORTH 3412 ONYX PL` |
| ui_002 | 12:56:44 | **399 / 458, 10 jobs, 87%** | `No active bad-part or skipped sheet alerts.` | 2 cards | **2 cards** — the 657 card is gone |
| ui_003 | 12:56:47 | 432 / 493, 11 jobs | restored | 2 cards | 3 cards (restored) |
| ui_006 | 12:56:54 | 399 / 458, 10 jobs | `No active…alerts.` | 2 cards | 2 cards |
| ui_007 | 12:56:57 | 432 / 493, 11 jobs | restored | 2 cards | 3 cards |
| ui_014 | 12:57:08 | **338 / 340, 9 jobs, 99%** | `No active…alerts.`, `2 sheets remaining` | **"Nothing is in progress right now."** | 2 cards |
| ui_015 | 12:57:12 | 432 / 493, 11 jobs | restored | 2 cards (592 ×2 back) | 3 cards |
| ui_024 → end | 12:57:26–12:58:11 | 432 / 493 | unchanged | unchanged | unchanged (clock text only) |

Whole jobs drop out of the derived model (`11 → 10 → 9 jobs`, `493 → 458 → 340 sheets`) and return within 1–3 s. Full text-node lists and the diffs are in `tmp/dashboard-monitor/evidence/ui-text-evidence.txt`.

### 2.2 Trigger-class activity on disk in the same minutes

This tablet is `SM-X800`. Tracker files named `SM-X808U-*`, `SM-T738U-*` and the other `SM-X800-*` IDs belong to **other tablets** in the fleet and land in these same job folders through Syncthing:

| File | Last write | Note |
|---|---|---|
| `657 - TRUNORTH…/CNC/.tracker/events/SM-X808U-5254.ndjson` | 12:56:46.121 | 90 KB; its `events` dir mtime is 12:56:53.669 |
| `592 - GIELISH/CNC/.tracker/events/SM-X808U-5254.ndjson` | 12:56:01.641 | 177 KB |
| `592 - GIELISH/CNC/.tracker/events/SM-X800-9231.ndjson` | 12:58:24.961 | another device |
| `592 - GIELISH/.metadata/cache_index.json` + `CNC/.tracker/consolidated.json` | 12:54:10 | earlier degraded cycle |
| `657 - TRUNORTH…/.metadata/cache_index.json` | 12:43:38 | |

A 5-sample / 32 s poll of every job's `cache_index.json` at 12:55:21–12:55:53 recorded **zero** changes, so the flicker is **event-driven (sync arrivals), not a fixed local poll interval**.

### 2.3 logcat, window 1 — the complete app output

```
09-21 12:49:54.740 31904 17386 D LiveIndexClient: WebSocket closed: code=1000 reason=client stop
09-21 12:49:56.309 18085 18140 D DeliveryScheduleLiveClient: WebSocket opened, sending hello
09-21 12:49:56.310 18085 18139 D LiveIndexClient: WebSocket opened, sending hello
09-21 12:49:56.320 18085 18140 D DeliveryScheduleLiveClient: Received snapshot frame: revision=0
09-21 12:49:56.321 18085 18139 D LiveIndexClient: Received snapshot: revision=38, jobs=15
```

Everything else the app emitted in those 4 minutes was `View : setRequestedFrameRate` draw lines (12:50:50.7, 12:51:06.0, 12:51:26.4, 12:52:26.4, 12:53:26.4) plus bitmap decodes at 12:50:41.8 and 12:50:50.7 — i.e. the dashboard was redrawing and re-decoding card bitmaps with nobody touching it.

### 2.4 Ruled out

No `FATAL EXCEPTION`, no ANR, no low-memory kill, no `derive_failed`, no `KKC_SCAN runRefresh EXCEPTION`, no GC storm, no `onTrimMemory` on the live process, no app-config or theme change. `DashboardShell(loading = false)` (`UnifiedModeDashboardScreen.kt:224`) means this is **not** the shell loader and **not** the skeleton/`hasLoadedOnce` path.

## 3. Root cause

**Whole jobs are removed from the in-memory job list, and the Dashboard re-derives its totals and cards from that partially-populated list.**

1. Another tablet punches production progress (or the Watcher republishes a job's `cache_index.json` / tracker files). Syncthing lands the write in this tablet's `Ready Jobs/<job>/…` (see §2.2).
2. `TrackerChangeMonitor` detects it — `FileObserver` on the `.tracker` dirs plus a 10 s signature poll (`TrackerChangeMonitor.kt:499`) — and `NavGraph.kt:257-259` invalidates that job's CNC index:
   ```kotlin
   onCncJobsChanged = { jobFolderNames ->
       jobFolderNames.forEach { scanCoordinator.unifiedEngine.invalidateJob(it) }
   }
   ```
   It also bumps `ProgressStore.progressVersion` and schedules the coalesced coordinator refresh (`FULL_REFRESH_COALESCE_MS = 2_000`, `TrackerChangeMonitor.kt:506`).
3. **`invalidateJob` deletes the job from the cached job list** — `FileBackedUnifiedMetadataEngine.kt:129-135`:
   ```kotlin
   override fun invalidateJob(jobFolderName: String) {
       staticByJob.remove(jobFolderName)
       trackerByJob.remove(jobFolderName)
       cncSearchByJob.remove(jobFolderName)
       cacheIndexByJob.remove(jobFolderName)
       cachedJobInfoList = cachedJobInfoList.filterNot { it.folderName == jobFolderName }
   }
   ```
   and `getCachedJobInfos(): List<UnifiedJobInfo> = cachedJobInfoList` (`FileBackedUnifiedMetadataEngine.kt:424`) is a plain read of it.
   This defeats the deliberate "don't blank a usable projection" guard in `listJobsFromCacheIndex()` (`FileBackedUnifiedMetadataEngine.kt:385-391`): that guard only stops a *scan* from writing an empty/partial list — it does nothing once `invalidateJob` has already removed an entry.
4. `AppStateStore.startDerivation()` (`AppStateStore.kt:74-141`) computes the entire Dashboard from that list:
   `val jobInfos = engine.getCachedJobInfos()` → `dashboardCandidates` (the Recent/Remake cards) → `indexCncJobs` for `totalJobs` / `totalSheets` / `completedSheets` / `badPartsSheets` / `skippedSheets`.
   Its `combine(...)` is debounced only 120 ms while no job is focused (`AppStateStore.kt:81`), so the `progressVersion` bump fires a derive **immediately — before** the 2 s-coalesced refresh has rebuilt the list. That derive correctly reports "11 jobs minus 1", producing the exact states in §2.1 (`399/458, 10 jobs`; `338/340, 9 jobs`; alert row gone; cards gone).
5. **It returns** because `ScanCoordinator.refresh()` re-runs `listJobsFromCacheIndex()` (`ScanCoordinator.kt:186-194`), which repopulates `cachedJobInfoList` from `cache_index.json` and bumps `generation`; `UnifiedModeDashboardScreen.kt:206-210` then forces the next derive:
   ```kotlin
   LaunchedEffect(appFlags.dashboardEnabled, scanState.snapshot.generation, appUiState.scanGeneration, appUiState.progressVersion) {
       if (appFlags.dashboardEnabled) appStateStore.requestRecompute()
   }
   ```
   The card thumbnails re-render too (`produceState` + `loadRecentMaterialThumbnail` / `renderPdfThumbnail`, `UnifiedModeDashboardScreen.kt:379-392, 449-465, 618-669`) — that is the `pdf_document_jni` / `BitmapFactory` / `Kumiho` churn at 12:56:46.6, 12:56:56.4 and 12:57:11.4, i.e. the cards visibly re-populating.

The CNC Dashboard reads `scanCoordinator.unifiedEngine`, which is the registry-backed **FileBacked** engine (`ScanCoordinator.kt:41-45`, `MainActivity.kt:286-287`); nothing reassigns it. The `LiveAwareUnifiedMetadataEngine` wrapper serves the Jobs tab / Hardwoods / Assembly paths (`NavGraph.kt:589, 941, 999`). So this flicker is a **local index-invalidation** artifact — not a live-index WebSocket artifact. (`delta` frames are never logged, so the live path cannot be excluded from logs alone; it simply is not what the CNC dashboard's totals are read from.)

Net effect: any outside change to a job folder puts the Dashboard through a 1–3 s window where a whole job is genuinely absent from its data source, and nothing holds the last good model.

## 4. Secondary finding — cold restart via Recents task removal

At 12:49:55 the app process was replaced mid-session:

```
09-21 12:49:55.064  2801  2843 I ActivityManager: Killing 31904:com.kkc.sheettracker/u0a409 (adj 905): remove task
09-21 12:49:55.741  2801  2850 I ActivityManager: Start proc 18085:com.kkc.sheettracker/u0a409 for activelaunch {com.kkc.sheettracker/com.kkc.sheettracker.MainActivity}
09-21 12:49:56.370 18085 18085 I Choreographer: Skipped 51 frames!  The application may be doing too much work on its main thread.
09-21 12:49:56.380 18085 18085 I Choreographer: Skipped 52 frames!  The application may be doing too much work on its main thread.
```

Reason `remove task` = the task was removed from Recents (not a crash). A cold start wipes `dashboardUiModel` / `hasLoadedOnce`, so the whole Dashboard rebuilds from empty — a separate, user/system-initiated cause of the same visual symptom.

## 5. Open questions (not verified)

- The per-event chain "tracker write → `invalidateJob` → degraded `derive_done index_jobs=N`" is inferred from the code path in §3 plus the timing correlation in §2.2. Both log sites are silent on release builds, so no second-by-second attribution per flap was possible.
- Unexplained: in window 1 the idle redraws landed at 12:51:06, 12:51:26, 12:52:26 and 12:53:26 — three of them on `:26` seconds. The `cache_index.json` sampling did not support a local 60 s timer, but that pattern was not explained either.

Verifying either point needs a debug build or temporary WARN-level logging — a change, so it was not done here.

## 6. Fix direction (proposed, not applied)

1. **`invalidateJob` should not drop the job from `cachedJobInfoList`** (`FileBackedUnifiedMetadataEngine.kt:134`). Clearing the four per-file caches is enough to force a re-read; the list entry is replaced by the next `listJobsFromCacheIndex()` pass anyway (it is keyed on the file signature). This restores the intent of the guard at line 388.
2. **Make the derive tolerant of an index-set shrink**: hold the last emitted `DashboardUiModel` while a refresh is in flight (or coalesce the `progressVersion`-driven derive until the refresh's re-list has completed), instead of publishing a model missing whole jobs.
3. **Make it diagnosable on release tablets**: emit an `AppLog.w` when `index_jobs` *decreases* on a derive, rather than only the `AppLog.i` line, so the next occurrence is provable from logcat.
4. **Coverage**: a unit test that `invalidateJob` leaves the job visible in `getCachedJobInfos()`, and a derivation test asserting totals do not regress when a job is invalidated without a rescan.

## 7. Retained evidence (uncommitted, under `tmp/`)

| Path | Contents |
|---|---|
| `tmp/dashboard-monitor/evidence/logcat-evidence.txt` | the complete filtered stream, the process lifecycle/jank lines, and per-second app activity timelines for both windows |
| `tmp/dashboard-monitor/evidence/ui-text-evidence.txt` | UI text nodes for every confirming dump, plus the full/degraded and degraded/recovered diffs |
| `tmp/dashboard-monitor/evidence/device-metadata-mtimes.txt` | the on-disk tracker/cache mtimes quoted in §2.2 |
| `tmp/dashboard-monitor/tagged.log` | the raw 6-line filtered stream (unmodified) |
| `tmp/dashboard-monitor/frames/f_001.png, f_002.png, f_003.png, f_014.png, f_015.png` + matching `ui_001.xml, ui_002.xml, ui_003.xml, ui_014.xml, ui_015.xml` | screenshots and hierarchy dumps for full → degraded → recovered |

The other 50 frames, both unfiltered logcat dumps (1.3–2.3 MB each) and the duplicate dumps were deleted after the evidence files above were distilled. The device-side `/sdcard/kkc_*` temp files created during the capture were also removed; `/sdcard/kkc_after_tap.png`, `kkc_insets_fix.png` and `kkc_screen.png` predate this investigation and were left untouched.

## 8. Re-running this check

```powershell
adb logcat -c
adb logcat -v threadtime -s KKC_APP_STATE:V KKC_SCAN:V KKC_PROGRESS:V StaticCachePoller:V `
  LiveIndexClient:V CacheIndex:V TrackerChangeMonitor:V AndroidRuntime:E ActivityManager:E *:S
# second shell — sample the UI while the dashboard sits idle:
adb shell uiautomator dump /sdcard/ui.xml
adb exec-out cat /sdcard/ui.xml | Select-String 'Overall Progress','jobs tracked','Nothing is in progress'
# correlate with foreign-device writes:
adb shell "ls -la --full-time '/storage/emulated/0/Ready Jobs'/*/CNC/.tracker/events/"
```

On a **debug** build the `KKC_APP_STATE derive_done … index_jobs=N` line makes the job-count drop visible directly.



