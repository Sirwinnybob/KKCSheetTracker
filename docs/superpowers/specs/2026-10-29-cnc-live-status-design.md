# CNC Live Status on Tablets — Design

Date: 2026-10-29
Status: draft, awaiting approval

## Why

People walk back to the CNC to see what it is doing. The PGM Mix Service now exposes a
read-only live snapshot of the machine (`GET /machine/state`), including the program that
is loaded/running right now. This is a convenience feature, not a safety or monitoring
system: the main value is seeing at a glance that **the sheet you are looking at on the
tablet is the one on the machine**, and being able to jump straight to whatever the
operator just loaded.

## Goals (v1 — only these)

1. **Dashboard: what's on the CNC.** On the CNC work-mode dashboard, the material whose
   sheet is loaded on the machine is pinned to the top of "Recent In-Progress Materials"
   with an ON CNC pill. Tapping it opens the sheet viewer on that exact sheet.
2. **Sheet viewer: is this the loaded sheet.** The loaded sheet shows an ON CNC pill in the
   sheet header; the last sheet the machine finished shows a CUT ON CNC pill with its end
   time. Sheet Navigator tiles carry the same markers.
3. **Unknown files still show.** When the loaded program doesn't match anything on the
   tablet (common — many files run that the tablets never see), the dashboard still shows
   that a file is loaded/running and its name. Nothing else, not tappable.

## Non-goals (later, maybe)

- Today's piece count, cut time, first start / last end, event timeline.
- Alarm display of any kind (alarm text, alarm state treatment, nuisance filtering).
- A dedicated Machine screen, screensaver/shop-floor display.
- Any history of finished sheets beyond the single last-finished program.

## Anti-goals

- **Never writes anything.** No tracker status changes (CUT ON CNC does *not* mark a sheet
  complete), no Ready Jobs metadata, nothing sent to the Mix Service except GETs. This is a
  new read-only consumer of the Mix Service, parallel to `MixServiceClient`.
- No noisy failure UI. CNC off / service down must never produce error banners or toasts.
- No X/Y/Z position or "percent through the sheet" — impossible (settled, see server notes).

## Data source

`GET http://<mix-service-host>:8477/machine/state` — server code
`C:\Scripts\PGM_BCR_Loader\mix_service\state.py` (+ route in `service.py`). File-derived
(Xilog Plus `.pro`/`.dia` + Apc\A companions), no websocket; poll it. Live sample
(2026-10-29):

```json
{
  "ok": true, "asOf": "2026-10-29T07:25:35", "reportDate": "2026-10-29",
  "haveReport": true,
  "state": "cutting", "producing": true,
  "alarm": { "active": false, "text": null, "since": null },
  "lastEvent": { "type": "START PROD", "time": "07:06:36" },
  "lastProgram": null,
  "today": { "completed": 0, "firstStart": null, "lastEnd": null, "totalCutSeconds": 0 },
  "currentProgram": {
    "name": "R82010BN", "running": true, "loadedAt": "2026-10-29T07:06:35",
    "dx": 96.5, "dy": 48.5, "dz": 0.746, "material": "3/4 Prefinished 19mm S",
    "path": "C:\\AAAAAAAAAAAAAAAA\\709 - BRAD TAYLOR\\19mm Pre_Finished\\R82010BN.pgm",
    "pathSource": "scan", "jobFolder": "709 - BRAD TAYLOR"
  }
}
```

Fields this feature uses:

| Field | Meaning |
|---|---|
| `currentProgram` | Loaded program, or `null`. `name` = PGM stem. `running` = machine producing. `path`/`jobFolder` may be `null` if unresolved. |
| `currentProgram.ambiguous` + `candidates[]` | Present only when the name exists in several jobs and dims/material couldn't break the tie. Each candidate `{ path, jobFolder }`. |
| `lastProgram` | Last **finished** program today (`.pro` line): `name` (with `.PGM` extension), `path`, `start`, `end` (HH:MM:SS, local, on `reportDate`). `null` until the first program finishes today; resets each day. Has no `jobFolder` field. |
| `state` / `producing` | Used only to pick CUTTING vs IDLE wording. |

Everything else is ignored in v1. **Parse tolerantly**: unknown fields ignored, any field
may be missing or `null` (the endpoint will grow).

## Behavior

### Matching a CNC program to a tablet sheet

Verified on real data (2026-10-29): the CNC folder `C:\AAAAAAAAAAAAAAAA\709 - BRAD TAYLOR`
and the Ready Jobs folder `Y:\Ready Jobs\709 - BRAD TAYLOR` have identical names, and the
loaded program `R82010BN` appears verbatim in
`709 - BRAD TAYLOR/CNC/.metadata/709 - 19mm Pre_Finished.json` as page 11's
`"sheetId": "R82010BN"`, `"sheetFiles": ["R82010BN"]` — bare stem, no extension, same
case. Compare case-insensitively anyway.

Input: a program name (stem; strip a trailing `.pgm`, case-insensitive) plus a job hint
(`jobFolder`, or for `lastProgram` the job folder derived from `path`).

1. **Find the job.** Match the job hint to a tablet `Job` by `folderName`
   (case-insensitive). Fallback only if no folder match: the leading job number (`709`
   from `"709 - BRAD TAYLOR"`; lettered jobs like `530a` keep their letter) — and only when
   exactly one tablet job has that number; otherwise unmatched. For `lastProgram`, derive
   the hint from the `path` segment that equals a tablet job folder name (case-insensitive).
   Only active (non-archived) jobs the tablet has loaded are considered.
2. **Find the sheet.** Within that job, across all materials, find the page whose sheet
   files contain the stem — use the same resolution the app already uses
   (`inferSheetFiles(page)`, which handles the Z/A pair fallback). A PGM name is unique
   within a job, so the material doesn't need matching. Skip `trackingExcluded` /
   `hiddenInApp` pages; a part-list continuation page resolves to its head page the same
   way the viewer does (`resolveHeadPage`).
3. **Ambiguous:** if `ambiguous` is true, run step 1 on every candidate; if exactly one
   candidate resolves to a tablet sheet, use it, otherwise treat as unmatched.
4. **Result:** `Matched(jobFolderName, pdfFilename, page)` or `Unmatched(name)`.

A Z/A pair is one sheet: A or Z loaded both mark that sheet.

### Dashboard (CNC work mode)

In `CncRecentMaterialsSection` (`ui/dashboard/UnifiedModeDashboardScreen.kt`):

- **Matched:** that material's row is moved to the top of "Recent In-Progress Materials",
  with a blue pill. If the material isn't in the recent list (untouched or fully complete),
  a row for it is built from the same derivation inputs `AppStateStore` already uses for
  recent items (real counts/progress/thumbnail of the loaded sheet) and inserted at the top;
  it is not added to the "N recent materials" subtitle count. Pill:
  - `ON CNC` + pulsing dot when `running` is true,
  - `LOADED · IDLE` (grey, no pulse) when false.
  Row tap → existing `onOpenSheet(jobFolderName, pdfFilename, page)` with the **loaded
  sheet's** page (not "next incomplete").
- **Unmatched:** a plain, non-tappable row at the top of the same list: the program name
  (e.g. `R82010BN`) and `Running` / `Loaded · idle`. No thumbnail, no job, no progress.
- **No program / unreachable / stale:** nothing extra in the list — it looks exactly like
  today.

Picked from mockups (option C over a dedicated card or a status strip).

### Sheet viewer

In `SheetViewerScreen` (`ui/viewer/SheetViewerScreen.kt`), in every viewer instance
(including mix-scoped ones) whose job + material + page match. The displayed page is
compared after head-page resolution (`resolveHeadPage`, currently private — expose or
reuse it), so continuation pages of the loaded sheet show the pill too.

- **Header pill** (picked option A, over banner / glowing outline) next to the sheet
  name/dimensions when the displayed sheet is:
  - the loaded sheet → `ON CNC · CUTTING` (blue, pulsing dot) or `LOADED · IDLE` (grey);
  - the last finished sheet → `CUT ON CNC 7:04` (green; time = `lastProgram.end`, h:mm).
  If the same sheet is both (re-loaded after finishing), the loaded pill wins.
- **Sheet Navigator** (`SheetNavigatorSheet`): the loaded sheet's tile gets a small
  `ON CNC` tag and outline; the last-finished tile gets `CUT h:mm`.
- Display only. Never touches sheet status.

### Polling and staleness

- Poll every **5 s**, only while the CNC dashboard or a sheet viewer is on screen and the
  app is foreground. Stop otherwise. One shared poller for the process, regardless of how
  many screens observe it.
- Timeouts short (connect ~2 s, read ~3 s) so a dead CNC never stacks requests; one
  request in flight at a time.
- **Unreachable / non-200 / `ok:false` / unparseable:** state becomes "unavailable" — all
  pills hide. No error UI, no logging spam (log transitions only).
- **Stale:** if no successful response for ~30 s, treat as unavailable (pills hide) rather
  than showing an old "ON CNC".
- `haveReport: false` / `currentProgram: null` → no loaded pill. `lastProgram: null` → no
  CUT pill.

## Architecture (follow existing live-client conventions)

- **Host:** reuse the Mix Service base URL. Today it is only the constructor default in
  `MixServiceClient` (`"http://192.168.20.4:8477"`) with the singleton in
  `KKCApplication.mixServiceClient`; there is no host setting. Extract that default into one
  shared constant used by both clients — do not duplicate the literal.
- **`MachineStateClient`** (new, `data/mixservice/` or `data/cnc/`): a suspend `fetch()`
  returning a tolerant parsed `MachineState` (Gson, defaults on every field), or a failure.
  OkHttp, like `MixServiceClient`.
- **`MachineStatusStore`** (new, process-scoped, owned by `KKCApplication` like the other
  live stores): holds a `StateFlow<MachineStatus>` where
  `MachineStatus = Unavailable | Live(loaded: LoadedProgram?, lastFinished: FinishedProgram?, asOf)`.
  Runs the poll loop while it has active observers (ref-counted start/stop, mirroring
  `SupplyLiveClient`/`LiveIndexClient` start/stop), applying the staleness rule.
- **Program index** (built in `AppStateStore.derive`, which already walks full `Job`
  objects with `material.metadata.pages`): a map from lowercase PGM stem →
  `(jobFolderName, pdfFilename, headPage)` per job folder, plus a job-number → folders map
  for the fallback. Exposed on the app state alongside `recentInProgressMaterials`, so it is
  rebuilt only when jobs change and lookups are cheap. Screens never see raw `Job`s.
- **Matcher** (pure function, unit-tested): `(MachineStatus, ProgramIndex) → CncSheetMarks`
  (`loaded: Matched|Unmatched|null`, `lastFinished: Matched|null`). Lives outside Compose;
  screens combine the store's flow with the index and render.
- **Host constant:** the Mix Service base URL is final as a shared constant (no setting);
  the second shop reaches `192.168.20.4` over the existing site-to-site VPN, same as
  `MixServiceClient` today.
- No disk persistence; nothing in DataStore. State is live-only and gone on restart.

## Testing

- Unit: response parsing (sample above, missing fields, extra fields, `ok:false`,
  `ambiguous` + `candidates`, `lastProgram` with `.PGM` extension and no `jobFolder`).
- Unit: matcher — folder match, job-number fallback, lettered job, Z/A pair, ambiguous
  resolved by tablet jobs, unmatched, loaded == last finished.
- Unit: store staleness/unavailable transitions with a fake clock and fake client.
- Manual on a tablet against the live service (CNC running a known job sheet, an unknown
  file, and with the service unreachable).

## Left to the builder

- Exact package/file names, pill styling within the existing dashboard/viewer look, the
  pulse animation (must respect the low-end `animationsDisabled` setting — no pulse then).
- How observers register with the store (lifecycle-aware collection is fine).
- Whether to share the OkHttp client with `MixServiceClient`.

## Decisions made by Claude (not explicitly chosen by the user)

- Unmatched/ambiguous/unavailable handling and "hide quietly" failure behavior.
- 5 s poll, visible-screens-only, 30 s staleness.
- Loaded material is inserted into the recent list if it isn't already there.
- Loaded pill wins over CUT pill on the same sheet.
- Tapping the dashboard row opens the loaded sheet, not the next incomplete sheet.
- Program index precomputed in `AppStateStore.derive`; screens don't get raw jobs.
- Job-number fallback only when exactly one tablet job has that number; archived jobs ignored.
- Inserted (not-recent) material rows show real progress, don't change the subtitle count.
- Viewer pills apply in every viewer instance, compared after head-page resolution.
