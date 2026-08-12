# PDF Measure Tool — Tablet-Side Design (KKCSheetTracker)

**Date:** 2026-08-12
**Status:** Approved design, pending implementation plan
**Companion doc:** `2026-08-12-pdf-scale-detection-rjw-design.md` (algorithm/contract, implemented in the Ready Jobs Watcher port living in the Hours Tracker worktree — not this repo)

## Summary

Add a measuring tool to the PDF viewer so shop users can tap two points on a reference
drawing (Assembly Sheets, Delivery Sheets, Plans & Elevations) and get a real-world
distance in decimal inches. Distance conversion depends on a per-page "points per inch"
scale factor that is auto-detected upstream by Ready Jobs Watcher and published as a
read-only sidecar; when no reliable auto-detected scale exists for a page, the tool falls
back to a manual two-point calibration that the tablet performs locally and also submits
upstream for the master sidecar to absorb.

## Scope

- **In scope:** Assembly Sheets, Delivery Sheets, Plans & Elevations PDFs.
- **Out of scope:** Door Cut List, Door List, Face Frame Cut List, Nailer Cut List — these
  are tabular/spec sheets, not scaled drawings; no measure tool needed there.
- **Out of scope for this repo:** the scale-detection algorithm itself. This repo only
  consumes the published result. See the companion RJW doc.

## Data contract (read side)

Path: `Y:\Ready Jobs\<job>\.metadata\pdf_scale\<pdf-stem>.json`

```json
{
  "pdf": "659 - PLANS & ELEVATIONS.pdf",
  "pages": {
    "0": {
      "pointsPerInch": 2.03,
      "method": "auto",
      "confidence": "high",
      "chainsAgreed": 14,
      "computedAt": "2026-08-12T10:03:00Z"
    },
    "3": {
      "pointsPerInch": null,
      "method": "manual_required",
      "reason": "no_dimension_chains_found"
    }
  }
}
```

Owned and written entirely by Ready Jobs Watcher (the ported version). KKCSheetTracker
treats this file as **read-only**. Missing file, missing page entry, `pointsPerInch: null`,
or any parse failure are all treated identically: "no scale available for this page" —
never a hard error, never blocks viewing the PDF.

## Data contract (write side — manual calibration)

Path: `Y:\Ready Jobs\<job>\.metadata\pdf_scale\calibration_request.<tabletId>.json`

```json
{
  "pdf": "659 - PLANS & ELEVATIONS.pdf",
  "page": 3,
  "pointA": {"x": 0.312, "y": 0.550},
  "pointB": {"x": 0.480, "y": 0.550},
  "knownDistanceInches": 24.0,
  "submittedAt": "2026-08-12T14:22:10Z",
  "tabletId": "tablet-07"
}
```

- Point coordinates are normalized (0-1) against page width/height, matching the existing
  `PdfMarkupStore` convention for strokes.
- One pending request per tablet per file at a time (new calibration overwrites the
  tablet's own prior pending request for that PDF); RJW consumes oldest-first and deletes
  after folding into the master sidecar — same pattern as `production_order_request.<tabletId>.json`.
- If the write fails (disk/sync hiccup), the tablet keeps using its locally computed scale
  for the session and retries the write in the background. It does not block or re-prompt
  the user.

## Tablet components

### `DrawingTool.MEASURE`

New value in the existing `DrawingTool` enum (`PdfMarkupSupport.kt`), alongside
`PAN_ZOOM/PEN/HIGHLIGHTER/ERASER`. Selecting it puts the viewer in two-tap measurement mode:

1. Tap point A — small marker drawn.
2. Tap point B — marker drawn, distance line rendered between A and B, distance label at
   the midpoint.
3. If the page has a usable scale (`method: "auto"` with `confidence: "high"`, or
   `method: "manual"`), the label shows the computed distance immediately in decimal
   inches (e.g. `36.38"`).
4. If the page has no usable scale, the two taps instead open the calibration flow (below)
   before any distance is shown.

### Scale lookup

A small repository (new file, e.g. `PdfScaleRepository.kt`, following the existing
repository pattern such as `PdfMarkupStore.kt`) reads and parses the per-PDF scale sidecar
on demand, cached in memory for the lifetime of the opened PDF (same caching lifecycle as
existing markup data — re-read on file open, not polled).

### Manual calibration flow

Triggered when the current page has no usable scale and the user places an A/B pair with
`MEASURE` active:

1. Prompt: "Calibrate this page — enter the real-world distance between these two points."
2. Numeric input (decimal inches).
3. On submit: compute `pointsPerInch` locally from the normalized A/B points and the page's
   known point dimensions (from `PdfRenderer.Page.width/height`, already available wherever
   PDFs are rendered today), apply it immediately for this session (optimistic), and write
   the calibration request sidecar.
4. Reject calibration if A and B are within ~5px of each other on screen (avoid divide-by-
   near-zero garbage).

### Persistence of placed measurements

Extends the existing per-page markup save shape (`PdfMarkupStore.kt`) with a parallel list:

```json
"measurements": [
  {"ax": 0.312, "ay": 0.550, "bx": 0.480, "by": 0.550, "distanceInches": 36.38}
]
```

Stored alongside existing pen/highlighter stroke data, same file, same per-tablet markup
sidecar (`.metadata\pdf_markup\.tracker\<tablet>.markup.json`). Tapping an existing
measurement selects it for deletion, mirroring how strokes are already removed with the
eraser tool.

## Error handling

| Condition | Behavior |
|---|---|
| Scale sidecar missing/unreadable | Treated as "no scale" — falls to manual calibration flow |
| `pointsPerInch: null` / `method: "manual_required"` | Same — falls to manual calibration flow |
| Two calibration points too close together | Reject with inline message, ask to re-tap further apart |
| Calibration request write fails | Keep local optimistic scale for session, retry write silently in background |
| PDF page has a scale but confidence is `"low"` | Treated as "no scale" for tablet purposes — only `"high"` confidence (or manual) is used directly, per the RJW doc's zone-agreement rule |

## Testing

- Unit tests for distance math: normalized coordinates + page point-dimensions + scale →
  inches, including edge cases (zero-length, sub-pixel taps).
- Unit tests for the calibration request JSON shape (round-trip serialize/parse).
- Manual on-device pass against job `659 - WIECHERT 88111 WYMORE`: confirm auto-detected
  pages (Plans p0/p3, Delivery p1) show distances matching known cabinet dimensions printed
  on those sheets; confirm manual calibration flow triggers correctly on a page with no
  detected scale.

## Open items / follow-ups

- Once the RJW-side algorithm is implemented and validated against a second job (see
  companion doc's open item on font-signature portability), confirm real sidecar files
  match this contract exactly before wiring the reader.
- Existing measuring program (separate, pre-existing effort) will be compared against this
  approach's real-world accuracy once both are testable end-to-end.
