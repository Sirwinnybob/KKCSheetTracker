# Impeccable critique — Hardwoods workspace

Target: representative Android tablet workflow in `app/src/main/java/com/kkc/sheettracker/ui/hardwoods/HardwoodsWorkspaceScreen.kt`, with shared navigation/theme and current tablet screenshots.

## Design Health Score

| # | Heuristic | Score | Key issue |
| --- | --- | ---: | --- |
| 1 | Visibility of System Status | 3/4 | No persistent save, sync, or offline-freshness state. |
| 2 | Match System / Real World | 3/4 | Shop vocabulary is good; `R2`, `CHANGED`, and `Open Ref` are unexplained. |
| 3 | User Control and Freedom | 3/4 | Complete/zero actions depend on undiscoverable long press. |
| 4 | Consistency and Standards | 3/4 | Skip and reference actions vary in naming and form. |
| 5 | Error Prevention | 2/4 | Immediate tally/skip changes lack a clear undo or sync guardrail. |
| 6 | Recognition Rather Than Recall | 3/4 | Users must infer long press and cryptic labels. |
| 7 | Flexibility and Efficiency | 3/4 | List/classic modes and reference jumps help, but repeated per-row work remains. |
| 8 | Aesthetic and Minimalist Design | 2/4 | PDF, tabs, filters, row actions, and navigation compete for attention. |
| 9 | Error Recovery | 2/4 | Errors do not keep recovery guidance near the affected row. |
| 10 | Help and Documentation | 1/4 | No visible legend or contextual explanation. |
| **Total** |  | **25/40** | **Acceptable; significant improvements needed** |

## Design Specificity Verdict

The workflow itself is product-specific: a cut list beside assembly/plans, material sections, cabinet IDs, dimensions, and tally states directly support cabinet production. The visual vocabulary is still largely generic Material 3—pills, cards, colored actions, and an icon-only floating navigation bar—so the screen feels functional but not yet as precise or calm as a shop-floor instrument.

The deterministic detector reported zero findings for `HardwoodsWorkspaceScreen.kt`; this is not a clean bill of health because the detector is web-oriented and does not perform Compose-specific runtime/layout analysis. Browser overlay was not applicable: this is native Android Compose with raster screenshot evidence, not a mutable DOM target.

## What’s Working

- The split reference/cut-list workflow is a strong decision: workers can verify the assembly while tallying parts.
- Concrete progress is redundant in helpful ways: section bars, counts, pills, and completion checks make work state scannable.
- Important row information is co-located: dimension, cabinet, revision/status, tally, and reference access.

## Priority Issues

### [P0] Responsive row-collapse regression

In the older screenshot, dimensions wrap character by character and the red minus control expands across nearly the entire row, blocking reading and tallying. Preserve a shrinkable text column and a fixed-width action rail; forbid character wrapping for dimensions; add smallest-supported-tablet screenshot/UI coverage. If the screenshot reflects an obsolete build, label it and retain the test.

Suggested command: `$impeccable adapt`.

### [P1] Targets are too small for shop-floor use

The tally controls, `View`, and `Skip` use 32dp-class sizing; `MaterialSkipPill` is 22dp high. Give actions 48dp tap areas while keeping their visible treatment compact. Keep `+` and `−` primary; move `View` and `Skip` behind selection or a secondary row on constrained widths.

Suggested command: `$impeccable adapt`.

### [P1] Floating navigation obscures the working list

The lower rows sit beneath the navigation overlay in both screenshots; the list uses a hard-coded 200dp bottom spacer rather than actual overlay/inset measurement. Use a measured/inset-based spacer or hide/minimize navigation while tallying, and ensure a focused row scrolls fully above it.

Suggested command: `$impeccable adapt`.

### [P1] Too many simultaneous decisions

The screen can present seven document/filter choices, a mode control, PDF controls, seven navigation destinations, and five row actions. Consolidate document types under a labelled selector, demote `CHANGED` to a filter/badge, and expose only the primary row action until a row is selected or expanded.

Suggested command: `$impeccable distill`.

### [P2] Status and completion feedback need clearer meaning

Counts such as `89` beside `127/128`, `R2`, color bands, and hold-to-complete/zero behavior require inference. Label counts (`89 lines · 127/128 pcs`), explain or replace `R2`, make hold actions visible, add `Undo`, and surface durable sync/offline state.

Suggested command: `$impeccable clarify` and `$impeccable harden`.

## Persona Red Flags

- **Alex, power user:** Repeated per-row work remains; complete/zero is hidden behind long press and batch action is inconsistent.
- **Jordan, first-timer:** `R2`, `CHANGED`, `Cab(s)`, mode choices, and icon-only navigation lack orientation or a legend.
- **Sam, accessibility-dependent user:** a row’s ordinary click does nothing while long press carries utility; custom clickable tabs/mode control need semantics/focus verification; small controls are a motor-accessibility risk.

## Minor Observations

- Current source and the newer screenshot disagree on the mode selector (dropdown versus visible segmented control); validate on the current build.
- Long job titles may crowd the top-bar actions.
- Pale fills and width-band accents need non-color labels in glare.

## Questions to Consider

- Could workers operate from a single “next unfinished cut” queue instead of seven peer tabs?
- Should `+` and `−` be glove-safe primary controls while `View` and `Skip` appear only on selected rows?
- At `128/128`, what confirms both physical completion and a persisted/synchronized record?
