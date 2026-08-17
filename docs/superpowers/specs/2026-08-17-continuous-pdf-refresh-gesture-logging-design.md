# Continuous PDF Refresh, Gesture, and Release Logging Design

**Date:** 2026-08-17

## Goal

Keep continuous PDF viewing responsive while background job metadata changes, and make release-build logging quiet by default. A background scan must not visibly reload an unchanged PDF, break pinch zoom, or reduce scroll distance.

This design covers app-owned Android logging. It does not attempt to suppress Android framework, Samsung, Syncthing, or third-party library logs.

## Verified failure mechanism

The failure was captured live on an SM-X800 running KKCSheetTracker 8.2.0.

- `ScanCoordinator` generations advanced repeatedly while the reference viewer remained open (`150` through `154` in the captured interval).
- `ReferencePdfViewerScreen` passes that global generation directly as `fileIdentitySeed`.
- `ContinuousReferencePdfPane` keys render caches and interaction state to that seed, so any generation change replaces its visible zoom state with `1.0` and rebuilds page renders even when the displayed PDF is unchanged.
- The pane's `pointerInput` owner is keyed only by orientation. It survives the refresh and retains the previous state objects.
- In the same gesture frames, `PdfFlingDebug` reported the stale gesture zoom as `2.626514`/`2.6431031`, while `PdfRenderTrace` reported the composed document at `1.0`.
- The stale zoom value also divides main-axis scroll deltas. A full-screen swipe therefore moves the visible 1x document by roughly one third of the expected distance, while pinch updates an abandoned state object and appears to do nothing.

The current viewer also writes render and gesture diagnostics at frame/gesture frequency. During evidence collection, Android's `logd` process consumed approximately one CPU core. The repository currently contains 98 `Log.d` and 6 `Log.i` app call sites, most of which are not release-gated.

## Approaches considered

### 1. Restart gestures on every global generation

Add `fileIdentitySeed` to the `pointerInput` key. This prevents stale state capture but still resets zoom, discards render caches, and visibly refreshes unchanged PDFs whenever unrelated metadata changes. It treats the symptom but retains the noisy invalidation boundary.

### 2. Preserve all interaction state across every refresh

Remove the global generation from zoom/pan state keys. This avoids the immediate glitch, but a genuinely replaced PDF could retain interaction state and a gesture owner tied to obsolete document content unless every affected key is kept manually synchronized.

### 3. Key the viewer to resolved document identity (approved)

Use the global generation only to re-evaluate the files. Derive a stable document identity from the PDFs actually backing the current continuous document, then use that one identity for caches, render state, interaction state, and the gesture owner. Unrelated metadata changes produce the same identity and no reset; an actual PDF or virtual mapping change produces a new identity and a coordinated reset.

## Design

### Document-scoped identity

Add a small, pure identity helper near the continuous PDF component. It will:

1. Resolve the distinct source PDF filenames used by the current document, including virtual assembly mappings.
2. Resolve the light/dark file variant already selected by `pdfFileForFilename`.
3. Record each source's absolute path, existence, byte length, and last-modified time, sorted deterministically with the document mapping identity.
4. Return an immutable identity value suitable as a Compose key.

`fileIdentitySeed` remains an input that causes this fingerprint to be recomputed, but it is not part of the returned identity. This matches the existing paged viewer's path/length/last-modified identity pattern.

`ContinuousReferencePdfPane` will use the resolved document identity instead of the global seed for:

- PDF engine and thumbnail cache lifetime;
- crop overlays and page-coordinate maps;
- zoom, cross-axis pan, edge overscroll, interaction/fling state, and page reporting;
- per-page bitmap, aspect-ratio, and render-effect keys.

The gesture modifier will be keyed by both orientation and the resolved document identity. If the actual document changes, Compose cancels the old pointer coroutine and starts a new owner over the same newly keyed state. If only an unrelated scan generation changes, neither the state nor gesture owner is replaced.

No new service, dependency, poller, or persistent state is introduced.

### Logging policy

Add one app logging facade with a pure emission-policy helper:

- verbose/debug/info: emitted only when `BuildConfig.DEBUG` is true;
- warning/error: retained in release because they are actionable failure evidence;
- fatal crash reporting and the existing on-disk crash reporter remain unchanged.

Migrate app-owned `Log.d` and `Log.i` calls through the facade so release builds cannot emit them. Existing warnings and errors may also use the facade for consistency, but their release behavior does not change.

Remove `PdfRenderTrace` canvas/frame logging and routine `PdfFlingDebug` gesture logging entirely rather than merely gating it. These traces are too expensive and noisy for normal debug sessions. Future temporary high-frequency diagnostics must be locally scoped and removed after investigation.

Release minification will not be enabled as part of this work. Changing R8 behavior would broaden deployment risk and is unnecessary when logging is gated at the call boundary.

### Error handling

- Missing source files contribute an explicit missing-file entry to document identity, preserving the viewer's existing missing/unreadable UI.
- File metadata reads are defensive: unavailable length or modification data yields a stable missing/unavailable identity rather than crashing composition.
- A real file replacement changes identity and performs one coordinated cache/gesture reset.

## Testing

Use test-driven development with focused JVM tests before production edits.

1. Add a regression asserting that two refresh generations resolving to identical PDF fingerprints produce equal continuous-document identities.
2. Assert that path, existence, length, last-modified time, source variant, or virtual mapping changes produce different identities.
3. Assert that the interaction/gesture owner key changes with actual document identity and orientation, but not with an unrelated scan generation.
4. Add logging-policy tests proving debug/info are disabled for release and warning/error remain enabled.
5. Run the focused continuous viewer tests, related viewer tests, the full debug unit-test suite, and `assembleDebug`.

## Tablet verification

Install with `adb install -r`; never uninstall. On the connected SM-X800:

1. Open the same Assembly Sheets document in continuous mode.
2. Zoom and scroll while background watcher generations advance.
3. Confirm the zoom remains visible, pinch continues to respond, and a full-screen swipe produces full-distance movement.
4. Confirm an unchanged PDF does not blank or rebuild on unrelated generation changes.
5. Confirm a deliberate real PDF identity change still reloads safely when one is available to test without modifying production data.
6. Capture focused logcat and process usage to confirm the viewer trace flood is gone and `logd` is no longer consuming abnormal CPU.

## Acceptance criteria

- Unrelated scan-generation changes do not reset or visibly refresh an unchanged continuous PDF.
- After any actual document refresh, pinch and scrolling remain controlled by the currently displayed state.
- Continuous zoom, fling, scrollbar navigation, page resume, dark-mode file selection, virtual mappings, and markup gesture exclusion continue to work.
- Release builds emit no app-owned verbose/debug/info logs.
- Release warning/error and crash evidence remain available.
- Frame-by-frame PDF render and routine gesture logs are absent in debug and release builds.
- Focused tests, the full debug unit-test suite, and `assembleDebug` pass before tablet installation.

## Scope boundaries

- The repair applies to `ContinuousReferencePdfPane` wherever `UnifiedReferenceViewer` uses it.
- Paged viewer rendering behavior is unchanged, aside from adopting the shared app logging policy where relevant.
- This work does not suppress logs produced outside KKCSheetTracker, enable release minification, alter Syncthing behavior, or change metadata ownership.
