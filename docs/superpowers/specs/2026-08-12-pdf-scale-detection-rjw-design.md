# PDF Dimension-Scale Detection — Algorithm Design (Ready Jobs Watcher)

**Date:** 2026-08-12
**Status:** Approved design, pending implementation plan
**Target repo:** Ready Jobs Watcher (the ported version in the Hours Tracker "Ready Jobs
Playground" worktree) — this doc was written from the KKCSheetTracker repo and is meant to
be copied over. It is self-contained and does not assume KKCSheetTracker's code layout.
**Companion doc:** `2026-08-12-pdf-measure-tool-tablet-design.md` (tablet-side consumer,
KKCSheetTracker repo) — defines the sidecar contract this algorithm must produce.

## Problem

Job PDFs (Assembly Sheets, Delivery Sheets, Plans & Elevations) contain vector cabinet/
room drawings with printed dimension callouts (e.g. `40.5`, `35`, `24`), but **no printed
scale statement** anywhere (confirmed: searched all sample pages for `SCALE`, fractional-
inch scale notation, and ratio notation like `1:24` — zero matches). To support a measuring
tool, we need to derive, per PDF page, a reliable **points-per-real-world-inch** ratio from
the drawing's own vector content — i.e., the ratio between PDF point-distances (72
points = 1 physical printed inch, fixed) and the real-world object inches those distances
represent.

## Evidence base

Findings below come from manual + scripted inspection of three sample PDFs from job
`659 - WIECHERT 88111 WYMORE` (Assembly Sheets, 21 pages; Delivery Sheets, 8 pages; Plans &
Elevations, 9 pages). All pages are vector content (`page.get_images()` empty on the
drawing-bearing pages; hundreds to thousands of vector path items via
`page.get_drawings()`), not scans — text and geometry are both programmatically readable.

### Approaches considered and rejected

- **Explicit scale text** (e.g. `1/2" = 1'-0"`, `1:24`) — searched, not present anywhere in
  these exports. Not usable.
- **Known reference object of fixed size** (e.g. a standard symbol/icon) — no consistently
  recurring fixed-size reference object found across pages. Too fragile to rely on.

### Approach: Chain Total-Span matching (recommended)

Naive per-segment matching (pair each individual tick/line segment to its nearest text
label, ratio = segment length / label value) is fragile in practice: sample data showed
duplicate overlapping paths (stroke + fill drawn as separate path items) producing
identical-length segment pairs, and segment boundaries don't always align cleanly under
their label, making 1:1 pairing ambiguous and error-prone. This is the likely failure mode
of naive/existing scale-detection attempts.

Instead: for each dimension string (a row/column of stacked tick marks + numeric labels),
compute the **outer span** — leftmost to rightmost tick x-position (or top/bottom for a
vertical chain) — and divide by the **sum of that chain's labels**. This was validated
against hand-extracted data: a chain with labels `40.5, 35, 30, 37.25, 24` sums to `166.75`,
which also appears independently printed on the page as its own overall-dimension label —
confirming the chain-sum interpretation is correct, not coincidental.

```
ratio = (max_tick_x - min_tick_x) / sum(chain_label_values)
```

This is immune to internal tick-pairing ambiguity — it only needs the overall extent of the
chain and the sum of its labels, both of which are robust to duplicate/overlapping path
noise.

### Cross-validation: chain agreement

A page typically has multiple independent dimension chains (e.g. two stacked rows, or a
width chain plus a height chain). Compute the ratio for **every** chain found on a page.

- If multiple chains' ratios agree within tolerance (proposed: **1.5%**), treat the scale
  as auto-detected with `confidence: "high"`.
- If chains disagree, or only zero/one chain is found, do **not** guess — mark the page
  `method: "manual_required"`. A wrong auto-detected scale is worse than admitting we don't
  know; the tablet's manual calibration flow (see companion doc) is the fallback, not a
  last resort to avoid.

This was validated against real data: pages with well-formed dimension chains (Plans p0,
p3; Delivery p1; Assembly p12) produced consistent ratios across multiple independent
chains (example: Plans p0 — 14 independent chains, ratio ≈ 2.03 pts/inch, agreeing within
the tolerance). Pages without clean dimension geometry correctly failed to reach agreement
and would fall to manual — this is expected and correct behavior, not a bug to fix away.

### Candidate filter: dimension-label font signature

A prototype using raw geometry + all numeric text on a page produced significant false-
positive "chains" from unrelated content (table borders, cutlist row dividers, spec-sheet
numeric fields). Inspecting the PDF's text span metadata revealed a strong filter: across
every sample page in all three files, dimension callout numbers share a **distinct text
run** — one specific embedded font subset, black fill color, and **100% of that font's text
content is a bare decimal number** — clearly separated from headers, room labels, colored
callout markers (e.g. `# 1`), and other numeric fields on the page (which use a different
font/size and are not 100% numeric).

**Do not hardcode the literal font subset name** (e.g. `CIDFont+F3` in the sample data).
Subset names are assigned by the exporting software per-document and are not guaranteed
stable across different job exports, even from the same source software. Instead, detect
the signature behaviorally per PDF:

1. Group all text spans by `(font, size, color)`.
2. For each group, compute the fraction of its span texts that parse as a bare decimal
   number (regex: `\d{1,4}(\.\d{1,4})?`).
3. Candidate dimension-label groups are those with `numeric_fraction >= 0.98` (allow tiny
   noise) **and** `color == black` **and** excluding any group whose color matches the
   page's callout-marker color (identify the callout color separately — it co-occurs with
   short alphanumeric tokens like `# 1`, `# 2`, not pure decimals).
4. **Caveat — only one job validated so far.** This behavioral signature was confirmed
   reliable across all sampled pages of one job (`659 - WIECHERT 88111 WYMORE`). Before
   trusting it in production, validate against at least one additional job's PDF exports to
   confirm the convention (dedicated numeric-only font run for dimensions) holds generally
   and isn't an artifact of this one job's export settings.

Use this filter to restrict candidate text labels *before* the chain-matching step above —
it should eliminate most of the false-positive chains seen in the unfiltered prototype
(cutlist tables, borders, spec fields).

### Secondary corroborating signal: dimension-text font size

Unexpected finding, worth building in as a bonus cross-check: the point-size of the
dimension-label font is **not** constant across pages, but scales in near-lockstep with the
page's detected chain ratio:

| Page | Dimension-label font size (pt) | Chain-detected ratio | size ÷ ratio |
|---|---|---|---|
| Plans p0 | 4.06 | 2.03 | 2.00 |
| Plans p3 | 11.11 | 5.49 | 2.02 |
| Delivery p1 | 5.02 | 2.50 | 2.00 |
| Assembly p12 | 7.31 | 3.23 | 2.27 |

Interpretation: dimension text is authored in model space at a fixed real-world height
(an annotation style in the source CAD/cabinet software) and gets scaled along with the
rest of the drawing geometry when placed on the page — the same transform applies to both
lines and text glyphs. This means `fontSize / scaleRatio` is a per-document(-type) constant
that can serve as an **independent second estimate** of the scale ratio, corroborating the
chain-matching result.

Note the constant is **not universal** — Plans and Delivery sheets both landed at ~2.00,
but Assembly sheets landed at ~2.27. Treat this constant as calibrated **per PDF file (or
per sheet-type template)**, not a hardcoded global value:

1. On any page where chain-matching reaches `confidence: "high"`, record
   `fontSize / chainRatio` for that file.
2. Average this constant across all high-confidence pages within the same PDF file.
3. For pages within that same file where chain-matching found zero/one chain or
   disagreement, if a dimension-label font run is present, estimate
   `ratio ≈ fontSize / calibratedConstant` and use it as a secondary confirmation — only
   promote a page to auto-detected if this estimate agrees with whatever weak chain
   evidence exists, or use it to rescue pages that would otherwise require manual
   calibration, at a lower confidence tier (`confidence: "medium"`, tablet treats as "no
   scale" per the companion doc — this is a future refinement, not required for v1).

## Output contract

Write one file per source PDF:

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

`method` values: `"auto"` (chain-matched, high confidence), `"manual"` (folded in from a
tablet calibration request), `"manual_required"` (no reliable auto scale, awaiting manual
calibration). `reason` is present only for `"manual_required"` and is a short machine
identifier (`no_dimension_chains_found`, `chains_disagree`, `font_filter_no_candidates`) to
aid debugging without needing full logs.

Recompute and rewrite this file whenever the source PDF changes (same trigger pattern as
other RJW-published per-PDF metadata, e.g. `cabinet_sheet_index.json`).

## Input contract (manual calibration requests)

Watch for and consume: `Y:\Ready Jobs\<job>\.metadata\pdf_scale\calibration_request.<tabletId>.json`

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

Consume oldest-first (per-tablet, per-PDF), same discipline as other tablet request
sidecars in this system (e.g. `production_order_request.<tabletId>.json`): fold into the
target page's entry in the master `pdf_scale\<pdf-stem>.json` as
`{"pointsPerInch": <computed>, "method": "manual", "computedAt": <now>}`, then delete the
request file. Distinguish malformed payloads (quarantine/discard) from transient write
failures on the master file (leave the request in place for retry) — do not lose a valid
tablet-submitted calibration to a transient I/O error.

## Testing

- Unit tests using the three sample PDFs from job 659 as fixtures:
  - Assert Plans p0, Plans p3, and Delivery p1 resolve to their validated ratios (2.03,
    5.49, 2.50 respectively) within tolerance.
  - Assert pages with no clean dimension chains resolve to `manual_required`, not a guess.
  - Assert the font-signature filter correctly excludes cutlist/table/spec-field numeric
    text (use Assembly p0's mixed-font-group content as a regression fixture — it contains
    both dimension labels and unrelated numeric spec values on the same page).
- Before production rollout: validate the font-signature approach against PDFs from at
  least one additional job, to confirm the numeric-only-font convention generalizes beyond
  job 659.
- Integration: confirm the calibration-request consume/delete cycle behaves correctly under
  the same oldest-first / quarantine-vs-retry discipline used elsewhere in this codebase.

## Open items

- Per-file font-size calibration constant (the "secondary corroborating signal" section) is
  a valuable future refinement but not required for a correct v1 — v1 can ship with chain-
  matching alone and add the font-size cross-check later without changing the output
  contract.
- Confirm whether any job in the shop's history has PDFs from a meaningfully different
  export pipeline/version where the font-signature convention might not hold, before
  assuming this generalizes shop-wide.
