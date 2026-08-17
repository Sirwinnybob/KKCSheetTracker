# Tablet Performance Cleanup and OCR Ownership Design

**Date:** 2026-08-17

## Goal

Remove verified unnecessary work from KKCSheetTracker without changing operator workflows, and make
the upstream ownership of OCR/PDF-derived metadata explicit.

## Evidence and scope

Two independent read-only Luna audits inspected UI/rendering/navigation and data/background/update
code. The findings below were then checked against the exact Android call sites. This design includes
only low-risk, high-impact work that can be tested independently.

Included:

- dispose continuous-view page-coordinate and crop-bitmap retention;
- eliminate duplicate watcher refresh epochs for one CNC invalidation batch;
- prevent hidden Jobs tabs from launching refresh and badge work;
- move specialty badge and route-availability filesystem work off the main dispatcher;
- make update scans single-flight and reuse APK parsing by file fingerprint;
- bound the modal Sheet-page bitmap cache;
- remove dead tablet-local OCR cache APIs, disk scans, and memory retention;
- clarify that upstream sidecar bounds remain consumable metadata.

Deferred because they need separate architecture/ownership work:

- domain-aware watcher fan-out instead of forced refreshes of several coordinators;
- unmounting hidden tab NavHosts while preserving navigation state;
- lifecycle eviction of old unified-metadata engines and removed jobs.

## OCR ownership

KKCSheetTracker must not execute OCR or create/cache OCR results. Ready Jobs Watcher, Hours Tracker,
or the PDF splitter performs the expensive upstream work for the streams each system owns. The
Android app consumes the resulting durable metadata.

The remaining `ProgressStore` OCR cache is dead code: `hasOcrCache`, `getOcrCache`, and `saveOcrCache`
have no callers. Its `ConcurrentHashMap`, JSON DTOs, `localStateDir/ocr/...` paths, and pruning walk
will be deleted. Existing on-device OCR cache files are harmless orphaned local cache data; the app
will stop reading and scanning them. This change will not recursively delete them during deployment.

Published CNC sidecars still expose fields named `ocrBoxes`, `ocrSource`, `ocrGeneratedAt`, and
`ocrVersion`. Those are an upstream schema contract and remain parseable. The viewer can map
`ocrBoxes` to diagram bounds, but Android-facing helpers and log tags should describe sidecar/render
data rather than implying local OCR execution.

ML Kit barcode scanning is unrelated and remains for the Supply scanner.

## Performance changes

### Continuous PDF retention

Each lazy page registers `LayoutCoordinates`, and zoomed pages can register multi-megapixel crop
bitmaps. Page disposal currently does not remove both maps. Add per-page disposal cleanup and keep
only visible/current crop entries. This belongs in the existing refresh-safe gesture work because it
touches the same component and directly addresses the 391 MB bitmap allocation observed on-device.

### Watcher refresh de-duplication

For a CNC tracker change, `onCncJobsChanged` invalidates the affected unified jobs and immediately
changes the global watcher epoch. The same monitor cycle always schedules a coalesced epoch two
seconds later. Keep targeted invalidation but remove the immediate epoch write so one batch produces
one coordinator refresh.

### Hidden tabs and specialty I/O

`TabLayer` keeps all tab NavHosts composed. Do not change that state-preservation architecture in
this pass. Instead, make `UnifiedJobsScreen.active` gate refresh and per-card badge effects. Move the
specialty badge resolver to `Dispatchers.IO`, and replace route-level synchronous `remember` file
checks with one IO-backed availability state.

### Update scanning

`UpdateManager.checkForUpdates` starts an unmanaged thread on every Activity `onStart`. Add one
single-flight gate. Cache parsed APK metadata by absolute path, length, and last-modified time so a
self-update and external-update pass over the same directory parse each archive once.

### Modal bitmap cache

Replace the unbounded per-document `MutableMap<Int, Bitmap>` with a four-page LRU. Do not recycle an
evicted bitmap because Compose may still draw the shared buffer; allow GC to reclaim it safely.

## Logging relationship

The separate release-logging plan remains authoritative. High-frequency continuous-view traces are
removed, debug/info logs are disabled in release, and warnings/errors plus crash reports remain.
Rename the legacy `KKC_OCR` viewer tag to sheet-render terminology for any warning/error sites that
remain after routine diagnostics are removed.

## Verification

- Focused unit tests for watcher wiring, active-tab gating policy, update single-flight/fingerprint
  behavior, and the modal LRU bound; source/lifecycle verification for continuous page disposal.
- Existing `ProgressStoreTest` before and after removal of unused OCR cache code.
- Source audits proving no tablet-local OCR cache symbol/path remains while upstream sidecar fields
  remain in the data model and metadata engine.
- Full `testDebugUnitTest`, `assembleDebug`, and `assembleRelease`.
- `adb install -r` only, followed by continuous-view memory/gesture checks on the SM-X800.

## Acceptance criteria

- KKCSheetTracker performs no OCR and owns no OCR memory/disk cache.
- Published upstream sidecar bounds continue to drive existing diagram behavior.
- One CNC invalidation batch produces one watcher refresh epoch.
- Hidden Jobs tabs do not initiate refresh or badge filesystem work.
- Specialty availability reads never block the Compose main dispatcher.
- Update scans cannot overlap and unchanged APKs are not reparsed within the process cache.
- Continuous and modal PDF bitmap retention is bounded.
- Deferred architectural items remain explicitly out of scope.
