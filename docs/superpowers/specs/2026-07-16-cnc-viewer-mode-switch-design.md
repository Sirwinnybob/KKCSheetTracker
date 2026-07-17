# CNC Viewer: Main-View Mode Switch + Popup Sheet Tab

## Problem

The CNC sheet viewer's popup reference viewer (Plans & Elevations / Assembly, added in the prior
`cnc-popup-reference-viewer` work) lets operators check a reference document in a floating overlay
while the Sheet stays visible underneath. Operators also want to swap the **entire main preview**
to a reference document — full-size, with its own paging and sheet navigator — not just a small
floating popup. And while in the popup, operators want a third option to view the current Sheet
itself (e.g. to compare it against a reference doc side-by-side isn't possible on a single tablet,
but flipping back to Sheet without closing the popup avoids losing popup position/state).

## Goals

- Main CNC viewer image area can display Sheet, Plans & Elevations, or Assembly full-size, with
  paging/navigator native to whichever document is active.
- Popup reference viewer gains a "Sheet" tab, showing the current Sheet as a plain page (no
  part-tap-to-select), snapped to the main view's current Sheet page each time it's selected.
- Both features reuse the existing `UnifiedReferenceViewer` + `ReferencePdfPane` pipeline built for
  the popup — no new PDF rendering/paging code.
- The two features are fully independent — switching main view mode does not affect the popup's
  state or vice versa.

## Non-goals

- Part-tap-to-select / diagram bounding-box overlay inside the popup's Sheet tab (main view keeps
  this; popup Sheet tab is a plain page view).
- Side-by-side (main view + popup) both showing reference docs synchronized to each other beyond
  the one-time sync-on-tab-switch for the popup's Sheet tab.
- Changing what `NavBarCncDecoration` (app scaffold nav bar) controls — it remains Sheet-only,
  always, regardless of main view mode.

## Design

### 1. New enum value

`ReferenceDocType` (`data/models/Models.kt`) gains `SHEET`, alongside existing `ASSEMBLY`,
`PLANS_ELEVATIONS`, `DELIVERY_SHEETS`.

### 2. Main view mode switch (`SheetViewerScreen.kt`)

New state: `mainViewMode: ReferenceDocType?` (`null` = Sheet, the current/default behavior).
Persisted the same way popup state is (SharedPreferences, `kkc_tracker` file), independent key.

**UI**: a 3-way segmented control — labels "Sheet", "Plans & Elev.", "Assembly" — placed in the
chip row next to the existing "Popup Viewer" `AssistChip` (~line 1380). "Plans & Elev." and
"Assembly" options disabled when `hasPlansReference` / `hasAssemblyReference` is false, matching
the popup's existing enable pattern. "Sheet" is always enabled.

**Main image area**: `VerticalSplitLayout`'s `topContent` (currently a `Crossfade` between
`MarkupPdfPageView`, `DiagramView`, and the loading placeholder) gains a branch: when
`mainViewMode != null`, render `UnifiedReferenceViewer` fed by
`rememberReferenceViewerData(docType = mainViewMode, ...)` (same helper the popup uses) instead of
the CNC bitmap pipeline. `showHeaderRow = false`, `showNavigationButtons = true`,
`compactArrows = true` — i.e. the same on-pane floating arrows + bottom-right prev/TOC/next pill
that Assembly's split view and the popup already use. This is a self-contained control; it does
**not** route through the app scaffold nav bar.

`bottomContent` (the part table) is unchanged — always shows the current Sheet's parts regardless
of `mainViewMode`. Tapping a part row still sets `selectedPartNumber` / `selectedCabinetNumber` as
today.

**Tap-to-jump into ref mode**: a new `LaunchedEffect(selectedCabinetNumber)`, active only when
`mainViewMode != null`, mirrors the popup's existing jump effect in `ReferenceModalOverlay.kt`:
resolve the tapped cabinet's page via `resolveJumpPage(referenceData.navigatorCabinetToPages, cabinet)`
and update the mode's page, or show the "no reference for this cabinet" note if none. The
DiagramView's own tap-to-jump (its on-image part highlighting) only applies in Sheet mode, since
`DiagramView` isn't rendered in ref modes.

**Nav bar**: `NavBarCncDecoration` — prev/next, TOC, skip/complete/re-nested — is completely
unaffected by `mainViewMode`. It always operates on the Sheet page and Sheet status, exactly as
today. No new wiring needed here.

**Per-mode page memory**: like the popup's `plansPage`/`assemblyPage`, the main view keeps its own
last-viewed page per doc type, independent of the popup's paging state (separate persisted keys).

### 3. Popup "Sheet" tab (`ReferenceModalOverlay.kt`)

`ReferenceModalSnapshot` gains a `sheetPage: Int` field; `pageForActiveDoc()` and `withPage()`
extend their `when` branches to cover `SHEET`. Persisted via a new `KEY_SHEET_PAGE` pref key,
following the existing `KEY_PLANS_PAGE`/`KEY_ASM_PAGE` pattern.

The segmented doc-type row in `ReferenceModalHost` gains a third `SegmentedButton` labeled "Sheet",
always `enabled = true` (the Sheet is definitionally always available — it's the document already
open behind the popup).

**Data source for SHEET**: unlike Plans/Assembly, `rememberReferenceViewerData` resolves reference
PDFs from the job's reference-doc lookup, which doesn't apply to the Sheet (it's the PDF already
open in `SheetViewerScreen`, identified by `pdfFilename`/`pdfFile`). `ReferenceModalHost` gains two
new params — `sheetPdfFilename: String` and `sheetPdfFile: File` — and when
`snapshot.docType == SHEET`, builds `UnifiedReferenceViewer`'s inputs directly from those (no
`virtualMapping`, no cabinet index) instead of calling `rememberReferenceViewerData`.

**Sync-on-switch**: `SheetViewerScreen` passes its current Sheet page to `ReferenceModalHost` as a
new `currentSheetPage: Int` param. `ReferenceModalOverlayState.setDocType` gains an optional
`syncPage: Int? = null` parameter; the segmented button's `onClick` for "Sheet" calls
`state.setDocType(ReferenceDocType.SHEET, syncPage = currentSheetPage)`, which sets `sheetPage` to
that value before persisting. Switching to Plans/Assembly (or reopening the popup on Sheet later)
does not re-sync — the popup then pages independently until the next explicit tab switch to Sheet.

**Jump-effect guard**: the popup's existing `LaunchedEffect(selectedCabinet)` jump logic
(`ReferenceModalOverlay.kt` ~line 253) is guarded to skip entirely when `snapshot.docType == SHEET`
— there's no reference document to jump within; the popup is already showing the Sheet.

**No diagram overlay**: the popup's Sheet tab uses `UnifiedReferenceViewer`/`ReferencePdfPane`
as-is — plain page rendering, no part-tap-to-select, no bounding-box overlay. That logic
(`DiagramView`, OCR bounding boxes) stays exclusive to the main CNC viewer.

## Testing

- Toggle main view through Sheet → Plans → Assembly → Sheet; confirm part table stays constant,
  each mode remembers its own last page across toggles, and the app scaffold nav bar's prev/next
  always moves the Sheet page (visible via the part table / page indicator) regardless of which
  mode is displayed.
- Tap a part row while in Plans/Assembly mode; confirm the ref doc jumps to the matching page (or
  shows the "no reference" note if none exists).
- Open the popup, switch to Sheet tab; confirm it opens on the main view's current Sheet page, then
  page forward in the popup, switch to Plans and back to Sheet — confirm it re-syncs to whatever
  the main view's Sheet page is *now* (not the page you left the popup on).
- Tap a part while the popup is open on the Sheet tab; confirm no "no reference" note fires and the
  popup doesn't attempt a jump.
- Confirm popup and main view mode switches are fully independent — e.g. main view on Assembly
  while popup is on Plans, or vice versa, with no cross-interference.
