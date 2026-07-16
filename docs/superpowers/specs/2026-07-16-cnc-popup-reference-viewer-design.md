# CNC Popup Reference Viewer — Design

**Date:** 2026-07-16
**Feature:** Floating popup modal in the CNC sheet viewer for viewing Plans & Elevations / Assembly reference sheets inline, with tap-a-part-to-jump.

## Problem

In CNC mode (`SheetViewerScreen`), viewing a cabinet's reference sheet requires a long-press on a part → "Open Reference Sheet" dialog → full-screen navigation away from the cut diagram. Operators want the reference sheet visible *alongside* the diagram, and want tapping a part to jump the reference to that cabinet — without leaving the CNC view.

## Solution overview

Add a draggable/resizable floating modal (same UX pattern as the existing Calculator overlay) that embeds the existing `UnifiedReferenceViewer`. The modal has a Plans/Assembly toggle in its header; the viewer's own built-in nav arrows + sheet navigator provide page navigation. While the modal is open, tapping a part on the diagram jumps the modal to the page containing that part's cabinet.

## Approach

**Chosen: calculator-pattern floating overlay** (non-modal, draggable, resizable, state persisted in SharedPreferences), embedding `UnifiedReferenceViewer`.

Rejected alternatives:
- **ModalBottomSheet / Dialog** — blocks touches to the diagram underneath, killing the tap-a-part-to-jump interaction.
- **Fully custom renderer** — duplicates `UnifiedReferenceViewer`.

## Supporting refactor

The per-doc-type derived state (virtual page mapping, cabinet→pages, plan-view labels, PDF filename resolution, warning message) currently lives inline in `ReferencePdfViewerScreen.kt` (lines ~73–201). Extract it so both the full-screen screen and the new modal share identical behavior:

- New file `ui/viewer/ReferenceViewerData.kt`:
  - `data class ReferenceViewerData(defaultPdfFilename: String, virtualMapping: UnifiedVirtualPageMapping?, navigatorCabinetToPages: Map<String, List<Int>>, navigatorPlanViewLabels: Map<Int, String>, warningMessage: String?)`
  - `@Composable fun rememberReferenceViewerData(jobRepository, jobFolderName, docType, refreshGeneration, isDarkTheme): ReferenceViewerData` — lifted verbatim from `ReferencePdfViewerScreen`.
- `ReferencePdfViewerScreen.kt` — replace the inline derivation with a call to `rememberReferenceViewerData(...)`. Pure refactor; behavior identical (uses same async/`produceState` off-main-thread I/O pattern per project rules on engine I/O).

## Components

### New file: `ui/components/ReferenceModalOverlay.kt`

Cloned structurally from `CalculatorOverlay.kt`.

**`ReferenceModalOverlayState`** (SharedPreferences-backed, `kkc_tracker` prefs file):
- Persisted: `isOpen`, `modalX`, `modalY`, `modalWidth`, `modalHeight`, `docType` (`ASSEMBLY` | `PLANS_ELEVATIONS`), and per-docType `lastPage`.
- Methods: `toggleOpen()`, `setOpen(open)`, `setDocType(type)` (restores that doc's persisted `lastPage`), `setPage(page)` (persists as active doc's `lastPage`), `updateModalBounds(...)`, `clampToViewport(...)`, `showNoRefNote()` (transient, non-persisted).
- Default size ~360×480 dp; default docType = first available reference (Plans preferred), else persisted choice.

**`rememberReferenceModalOverlayState()`** — factory `@Composable`.

**`ReferenceModalHost(state, jobRepository, jobFolderName, refreshGeneration, isDarkTheme, hasPlans, hasAssembly, hazeState, modifier)`**:
- Returns early if `!isOpen`.
- Draggable/resizable Box (reuse calculator's drag-header + `◢` resize-corner + viewport clamping + frosted `hazeEffect`).
- Layout top→bottom:
  1. **Header row** (drag handle): `SingleChoiceSegmentedButtonRow` → `[Plans & Elevations | Assembly]`; a segment is disabled when that doc is absent (`hasPlans` / `hasAssembly`). Right-aligned close `✕`.
  2. **Viewer body** (`weight(1f)`): `UnifiedReferenceViewer(showHeaderRow = false, showNavigationButtons = true, compactArrows = true, ...)`, fed by `rememberReferenceViewerData(docType = state.docType)`. Controlled `displayPage = state.pageForActiveDoc`; `onDisplayPageChange = state::setPage`. Built-in bottom bar supplies prev/next arrows and the sheet-navigator (page/room/cabinet jump grid).
  3. **"No reference" note**: transient banner shown when `showNoRefNote()` fired; auto-clears on next successful jump or doc toggle.
- Non-modal: empty regions of the overlay fall through to the diagram underneath (same as calculator), keeping parts tappable.

### Edits: `ui/viewer/SheetViewerScreen.kt`

- **Popup Viewer button** — in the chip `Row` (~line 1359), right side, inline with filename / sheet-size chips:
  - `AssistChip` labeled "Popup Viewer", `leadingIcon = Icons.Filled.OpenInNew`.
  - `onClick = referenceModal.toggleOpen()`.
  - Rendered only when `hasAssemblyReference || hasPlansReference`.
- **Host placement** — add `ReferenceModalHost(...)` as an overlay sibling in the screen's root Box, layered above content (zIndex overlay, like the calculator), passed `hazeState`, repo, job identifiers, `hasPlansReference`, `hasAssemblyReference`.
- **Part-tap jump** — no change to existing `onTapPart` / `onLongPressPart`; reuse `selectedCabinetNumber` (already set on tap, ~line 1513):

```kotlin
LaunchedEffect(selectedCabinetNumber, referenceModal.snapshot.isOpen, referenceModal.snapshot.docType) {
    val snapshot = referenceModal.snapshot
    val cabinet = selectedCabinetNumber
    if (!snapshot.isOpen || cabinet == null) return@LaunchedEffect
    val pages = referenceData.navigatorCabinetToPages[cabinet.toString()]   // active docType's page space
    val target = pages?.firstOrNull()
    if (target != null) referenceModal.setPage(target) else referenceModal.showNoRefNote()
}
```

## Behavior decisions (confirmed)

1. **Part-tap target doc:** follows the modal's current Plans/Assembly selection. No auto-switch to the other doc.
2. **Cabinet missing in active doc:** show a small inline "no reference" note; modal stays on its current page.
3. **Persistence:** modal position + size, selected doc type, and last-viewed page (per doc) all persist across opens, mirroring the calculator.
4. **On open:** restores persisted `lastPage`; does *not* auto-jump to the currently-selected part. Jump fires only on a fresh part tap while the modal is open.

## Page-space correctness

`navigatorCabinetToPages` comes from the shared `ReferenceViewerData` bundle, which already resolves the correct page space per doc type: Assembly uses the virtual-combined `cabinetToPages` (virtual display pages) when a virtual mapping exists; Plans & Elevations uses the document index's real `cabinetToPages`. Jump targets therefore match whatever the active viewer is rendering.

## Error handling

- Missing/unreadable PDF: handled by `UnifiedReferenceViewer` (`missingText` / `unreadableText`).
- Doc absent: its toggle segment is disabled; the button hides entirely if neither doc exists.
- Cabinet with no page in active doc: transient "no reference" note.
- All file-backed lookups run off the main thread via `produceState` + `Dispatchers.IO`, per the project rule against synchronous engine I/O in `remember{}`.

## Testing

- Unit: `ReferenceModalOverlayState` persistence round-trip (bounds, docType, per-doc lastPage), doc-toggle restores correct lastPage, `showNoRefNote` is transient.
- Manual (tablet, release build via `adb-install-release.ps1`): open modal from CNC viewer; drag/resize persists across reopen; toggle Plans/Assembly; tap parts of different cabinets → modal jumps to correct page in active doc; tap a cabinet absent from active doc → "no reference" note; verify parts under the modal stay tappable; verify `ReferencePdfViewerScreen` full-screen path unchanged after refactor.

## Out of scope (YAGNI)

- Simultaneous side-by-side Plans *and* Assembly.
- Markup/pen tools inside the modal (full-screen viewer retains them).
- 3D target open from the modal.
