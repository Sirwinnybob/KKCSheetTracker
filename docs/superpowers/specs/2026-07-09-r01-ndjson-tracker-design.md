# R-01: Tablet ndjson tracker-event producer

Status: design approved, not yet implemented.
Tracked in: `C:\Scripts\Hours Tracker\METADATA_AUDIT.md` (R-01, §7; frames in §3; supersedes/dissolves
C-01, H-06, M-06, M-10 once fully rolled out and legacy paths retired).
Repos touched: `KKCSheetTracker` (tablet, Kotlin), `Ready Jobs Watcher` (Python). No changes to
Hours Tracker backend.

## Problem recap

The watcher already has a complete, dormant append-only ndjson event-stream reader
(`ready_jobs_watcher/tracker_action_stream.py`) with Lamport-tiebreak ordering and corruption-tolerant
parsing. It has no producer. The tablet instead writes a per-tablet `<tabletId>.json` snapshot
(`ProgressStore.kt`, `HardwoodsProgressStore.kt`) that is periodically merged and **deleted** by
`metadata_cache.consolidate_cnc_tracker`/`consolidate_hardwoods_tracker` — a destructive path that has
already caused real data loss (C-01: dropped `bad_part_submitted`) and carries an unlocked
cross-process race (H-06) and a delete-time TOCTOU (M-10).

Goal: make the tablet the ndjson producer; point consumers at the existing union reader; retire the
lossy legacy consolidation once the field proves the new path solid.

## Architecture

### File layout

- CNC: `<job>\CNC\.tracker\events\<tabletId>.ndjson`
- Hardwoods: `<job>\.metadata\hardwoods\.tracker\events\<tabletId>.ndjson`

This matches the watcher's existing glob (`tracker_dir/events/**/*.ndjson` in
`_collect_ndjson_files`) — no watcher path change needed. `consolidated.json` keeps its current
location and format; it remains the durable, collapsed-state record other consumers (HT backend) read.

### Event schema (one compact JSON object per line)

```json
{"op": "set_complete_true", "payload": {"file": "Sheet_12.pdf", "page": 3, "part": null, "fileFingerprint": "abc123", "timestamp": "2026-07-09T14:22:01Z", "reNested": false}, "wallTime": "2026-07-09T14:22:01Z", "lamport": 47, "eventId": "b2e1..."}
```

- `lamport`: a single per-tablet monotonic counter (not per-job, not a true merged Lamport clock —
  the watcher's sort key already primaries on `timestamp`; `lamport` only tie-breaks same-instant
  events). Persisted in local, non-Syncthing app storage; increments across all jobs/trackers the
  tablet touches; never resets.
- `eventId`: UUID, for stream dedup/debugging.
- CNC op-map (`_CNC_OP_MAP` in `tracker_action_stream.py` and its Kotlin mirror) gets extended with
  `bad_part_submitted` and `view` (currently silently dropped — `bad_part_submitted` dropping would
  reopen C-01 the moment tablets switch), and `reNested` is threaded through `payload` for the skip
  ops.
- Hardwoods ops are already 1:1 between op name and action name in both languages
  (`SET_DONE_COUNT`, `SET_BAD_COUNT`, `SET_SKIPPED`, `CLEAR_SKIPPED`, `SET_TOTALS_RIP10_DONE_COUNT`,
  `ADD_TOTALS_RIP10_DONE_COUNT`) — verified against `HardwoodsProgressStore.kt` and `_HARDWOODS_OPS`;
  no gap, no mapping table needed.

### Write mechanics

Real line-append (`Files.write(path, lineBytes, CREATE, APPEND)`, newline-terminated), not the
load-whole → append → rewrite-whole-file pattern `appendActions` uses today for the legacy snapshot.
The tablet's in-memory `JobProgressIndex`/`JobCache` update (`applyActionToIndex`/`applyActionToCache`)
is unchanged — only the on-disk write path changes.

## Watcher-side changes (`Ready Jobs Watcher`)

1. **`tracker_action_stream.py`**: extend `_map_cnc_event_to_action` for `bad_part_submitted`,
   `view`, and `reNested` passthrough (see schema above). Hardwoods mapper needs no change.
2. **`metadata_cache.py` — `_consolidate_tracker`**: split what is currently one merge+delete pass
   into:
   - **Merge input**: switch from the raw `os.scandir` legacy-only scan to
     `load_cnc_tracker_actions`/`load_hardwoods_tracker_actions` (the union reader — already dual-reads
     ndjson + legacy). `consolidated.json` keeps being written on the existing debounce cycle, same
     cost/cadence as today.
   - **Deletion targets**: keep two separate lists. Legacy top-level `*.json` files keep the existing
     mtime/size-checked delete, running on every daytime debounce cycle (unchanged behavior). ndjson
     `events/*.ndjson` files are never touched by the daytime pass.
   - Add a `compact: bool = False` parameter. Daytime debounce calls with `compact=False`. The
     existing `metadata_end_of_day_scheduler` sweep (already runs after hours,
     `run_scheduled_sweep(consolidate_trackers=True)`) calls with `compact=True` — after a final merge,
     each `events/<tabletId>.ndjson` gets the same mtime/size "unchanged since we read it" guard the
     legacy path already uses, then is truncated to empty. The existing `.consolidate.lock` covers
     both passes; no new locking primitive.

### Backward compatibility (RJW ships before tablets)

Verified safe by construction: with zero tablets producing ndjson yet, `_load_tracker_actions`
(`tracker_action_stream.py`) falls through to `legacy_actions` only — behavior is identical to today.
The new `compact=True` end-of-day pass finds no `events/*.ndjson` files and is a no-op. No feature
flag needed on the RJW side; it can ship standalone and sit dormant exactly like the reader it's built
on.

## Tablet-side changes (`KKCSheetTracker`)

Applies to both `ProgressStore.kt` (CNC) and `HardwoodsProgressStore.kt` (hardwoods):

1. **Producer**: replace the whole-file `atomicWrite` in `appendActions`/`appendAction` with a
   real append to `events/<tabletId>.ndjson`.
2. **Lamport counter**: persisted per-tablet monotonic counter, see schema section above.
3. **Peer reader (new)**: `loadAllProgress` (CNC) / `readProgressFromDir` (hardwoods) must also glob
   `events/*.ndjson` per tablet and fold parsed events into the same `TrackerAction`/
   `HardwoodTrackerAction` shape used today. Downstream (`buildJobIndex`, `applyActionToSheets`,
   `applyActionToCache`) does not change — only the ingestion step gains a second source. Since the
   tablet is the only writer of its own ndjson file, the Kotlin reader can be simpler than the
   Python one: strict one-JSON-object-per-line, skip-and-continue on any line that fails to parse
   (handles a torn last line read mid-append by a peer).
4. **`TrackerChangeMonitor.kt` fix (required, not optional)**: its `FileObserver` watches
   `CNC/.tracker` and hardwoods `.tracker` directly, but Android's `FileObserver` is **not
   recursive** — it will never see writes inside a new `events/` subdirectory. Its filter is also
   `path.endsWith(".json")`, which excludes `.ndjson` even if it did see them. Without fixing this, a
   tablet silently stops noticing peer-tablet progress — a staleness bug easy to miss in testing since
   nothing crashes or errors. Fix: add a watched `FileObserver` per tracker dir for its `events/`
   subdirectory, and extend the suffix filter to accept `.ndjson`.

## Retention / compaction

Each ndjson file has exactly one legitimate writer (the owning tablet), so unlike today's
cross-process consolidation race, compaction of it can only safely be done by that same tablet during
a window with no concurrent writer — which is exactly what the existing after-hours end-of-day sweep
is. See "watcher-side changes" above: the end-of-day pass truncates each tablet's ndjson file to
empty after folding its content into `consolidated.json`, guarded by the same unchanged-since-read
check the legacy path already relies on. Daytime consolidation never touches ndjson files at all, so
there's no race with a tablet mid-shift.

## Rollout

1. Ship RJW changes first (mapper fix, merge-input switch, compact-flag split). Verified
   backward-compatible/no-op with no tablet changes present (see above).
2. Ship the tablet update to all devices at once, across both shop locations, before work starts on
   a given day (per existing `adb-install-release.ps1` deploy process — no remote feature flag,
   rollback is reinstalling the previous release APK if something's wrong).
3. Field-verify at one shop first: watch through a full day plus the overnight compaction, confirm
   `consolidated.json` still updates on schedule, confirm ndjson files actually truncate afterward,
   re-run a live bad-part-submitted repro end-to-end (the actual C-01 scenario, not just a unit test),
   confirm cross-tablet real-time visibility holds. Then the second shop.
4. Only after field verification: consider retiring the legacy `os.scandir`-only code paths, and
   update `METADATA_AUDIT.md` — mark R-01 `RESOLVED` with a sign-off per §1.2 (requires real
   `Verified:` evidence, not "looks correct"), and reassess M-10 (currently open; effectively moot
   once no tablet ever writes a legacy snapshot again, but don't mark it resolved until that's actually
   true in the field). Also update `kkc-metadata-map` `SKILL.md` row SK-01 (currently says the ndjson
   path is "dormant — no tablet producer") and add `CROSS-PROGRAM:` comments at each new
   producer/consumer site per §1.3.

## Tests to add

- RJW: op-map coverage for `bad_part_submitted`/`view`/`reNested` in
  `tests/test_tracker_action_stream.py`; `_consolidate_tracker` split-behavior tests in
  `tests/test_tracker_condensing.py` — daytime never deletes ndjson, end-of-day truncates only when
  the mtime/size-unchanged guard passes, a concurrent write during the end-of-day pass causes a clean
  skip (not data loss).
- Tablet: ndjson append/read round-trip for both stores; `TrackerChangeMonitorTest` fires on
  `events/` dir creation and `.ndjson` writes; lamport counter persists across a simulated process
  restart.

## Explicitly out of scope for this pass

- Hours Tracker backend — untouched; it keeps reading `consolidated.json`/`cache_static.json` exactly
  as today.
- Deleting legacy code paths — deferred until field verification (see Rollout step 4).
- A remote/runtime feature flag for producer switching — not needed given the all-at-once tablet
  deploy process.
