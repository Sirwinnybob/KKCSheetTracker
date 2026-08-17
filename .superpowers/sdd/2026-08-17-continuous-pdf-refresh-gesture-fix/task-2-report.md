# Task 2 report — Coordinate render, state, and pointer lifetimes

## Status

Completed. The continuous PDF pane now derives one `ContinuousPdfDocumentIdentity` at the Compose boundary and uses it to coordinate render, interaction, cache, gesture, and per-page cleanup lifetimes.

## Implementation

- Added the unrelated-refresh geometry identity regression test and migrated the dark-mode geometry test to the document identity API.
- Changed `ContinuousPageGeometryIdentity`/`continuousPageGeometryIdentity` to carry `ContinuousPdfDocumentIdentity`.
- Derived `documentIdentity` with the required `remember(fileIdentitySeed, totalPages, docKey, preferDarkMode)` boundary key; `fileIdentitySeed` has no other production use.
- Re-keyed engine/thumbnail caches, crop/coordinate state, programmatic scroll guard, zoom/pan/overscroll, interaction/fling state, page reporting, settled state, page render effects, and delta channel to the document identity as appropriate.
- Keyed the single pointer owner with `(orientation, documentIdentity)` while preserving `rememberLazyListState()` behavior.
- Removed `PdfFlingDebug`/`PdfRenderTrace` diagnostics, the `Log` import/tag, and debug-only counters.
- Added `DisposableEffect(displayPage, documentIdentity)` cleanup for page coordinates and crop overlays without recycling shared Compose bitmaps.

## TDD evidence

RED command:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.ui.components.ContinuousReferencePdfPaneTest" --tests "com.kkc.sheettracker.ui.viewer.UnifiedReferenceViewerTest" --tests "com.kkc.sheettracker.ui.viewer.ReferencePdfViewerScreenTest"
```

Result: expected compilation failure before the production API change. The new test calls the desired document-identity signature, and Kotlin reported `Argument type mismatch: actual type is 'ContinuousPdfDocumentIdentity', but 'Long' was expected` at the two new assertions.

GREEN command:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.ui.components.ContinuousReferencePdfPaneTest" --tests "com.kkc.sheettracker.ui.viewer.UnifiedReferenceViewerTest" --tests "com.kkc.sheettracker.ui.viewer.ReferencePdfViewerScreenTest"
```

Result: `BUILD SUCCESSFUL in 4s` (26 actionable tasks; 5 executed, 21 up-to-date).

Additional checks:

```powershell
rg -n "PdfFlingDebug|PdfRenderTrace|android\.util\.Log" app/src/main/java/com/kkc/sheettracker/ui/components/ContinuousReferencePdfPane.kt
```

Result: no output.

```powershell
git diff --check
```

Result: clean; only Git line-ending normalization warnings were emitted by status/diff inspection.

## Files changed

- `app/src/main/java/com/kkc/sheettracker/ui/components/ContinuousReferencePdfPane.kt`
- `app/src/test/java/com/kkc/sheettracker/ui/components/ContinuousReferencePdfPaneTest.kt`
- `.superpowers/sdd/2026-08-17-continuous-pdf-refresh-gesture-fix/task-2-report.md`

## Self-review

- Confirmed `rememberLazyListState()` remains unkeyed so the current display page is preserved.
- Confirmed the pointer modifier and coalescing channel share `documentIdentity`.
- Confirmed source cleanup has no remaining `Log`, `PdfFlingDebug`, or `PdfRenderTrace` references.
- Confirmed non-visible page crop removal remains in place in addition to disposal cleanup.
- Confirmed no `Bitmap.recycle()` was added.
- Confirmed the working diff is limited to the assigned task files plus this report.

## Concerns

None found in the scoped checks. The focused viewer tests do not exercise Compose pointer disposal directly; verification is compile plus the existing unit-test coverage.

## Fix round 1/5 — outgoing fling lifetime

### Change

Reviewer finding: `flingScope = rememberCoroutineScope()` outlived `documentIdentity`, while the `flingJob` holder was reset with the identity key and could lose the only cancellation handle. The fix adds `continuousPdfDocumentFlingScope(parentScope)`, a child scope with the composition scope as parent, and remembers it by `(documentIdentity, orientation)`. A `DisposableEffect(documentFlingScope)` cancels that child scope on document/orientation disposal before the replacement gesture owner can launch work. Fling jobs now launch in this per-document scope instead of the shared composition scope.

### TDD evidence

Added `continuousPdfDocumentFlingScope_cancelsOutgoingJobsWithScope` to `ContinuousReferencePdfPaneTest.kt` before the production helper.

RED command:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.ui.components.ContinuousReferencePdfPaneTest"
```

Result: expected compile failure, `Unresolved reference 'continuousPdfDocumentFlingScope'` at `ContinuousReferencePdfPaneTest.kt:168`.

GREEN covering command:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.ui.components.ContinuousReferencePdfPaneTest"
```

Result: `BUILD SUCCESSFUL in 7s` (26 actionable tasks; 5 executed, 21 up-to-date).

Full focused viewer command:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.ui.components.ContinuousReferencePdfPaneTest" --tests "com.kkc.sheettracker.ui.viewer.UnifiedReferenceViewerTest" --tests "com.kkc.sheettracker.ui.viewer.ReferencePdfViewerScreenTest"
```

Result: `BUILD SUCCESSFUL in 4s` (26 actionable tasks; 1 executed, 25 up-to-date).

Additional check:

```powershell
git diff --check
```

Result: clean; only Git line-ending normalization warnings were emitted during status/diff inspection.

### Covering files

- `app/src/main/java/com/kkc/sheettracker/ui/components/ContinuousReferencePdfPane.kt`
- `app/src/test/java/com/kkc/sheettracker/ui/components/ContinuousReferencePdfPaneTest.kt`
- `.superpowers/sdd/2026-08-17-continuous-pdf-refresh-gesture-fix/task-2-report.md`

### Fix-round concerns

The new unit test verifies child-scope cancellation and the focused viewer suite passes. Compose disposal ordering is exercised by the `DisposableEffect(documentFlingScope)` binding; there is no existing Compose pointer-disposal test harness in this module.
