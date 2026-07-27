# Sheet Rip Tallies and Length Labels Design

## Goal

Show the configured stock length for every board-stock rip item and let the
Hardwoods Saw Rip List track Sheet-mode rips individually, while retaining the
Specialty Job Detail screen's simple checkbox workflow.

## Screen Behavior

### Hardwoods Saw Rip List

- Sheet-mode board-stock rows show the existing decrement button, progress
  pill, and increment button.
- The tally target remains `ceil(feet / ripLength)` and supports any valid
  positive whole-number `ripLength`.
- Sheet rows do not expose item-level or material-level Skip controls.
- The regular Hardwoods Rip List remains unchanged: Sheet rows there are
  display-only and do not expose tally or Skip controls.

### Specialty Job Detail

- Sheet Rip rows remain checkbox checklist items; they do not add tally
  controls.
- A row is checked when its matching Saw Rip tally has reached its target.
- Checking a row sets its tally to the target; unchecking it resets the tally
  to zero.

## Progress and Compatibility

- The existing Hardwoods admin-board-stock tally is the canonical count for
  an interactive Sheet-mode Saw Rip List row.
- Existing `sheet_rip_done.json` Boolean entries remain a read-compatible
  fallback for rows without a tally record. A legacy `true` presents as fully
  complete until the user changes its tally or checkbox.
- Tally and checkbox actions maintain the Boolean completion projection so
  existing readers continue to see whether a row is fully complete.
- No new shared metadata file or migration is introduced.

## Length Labels

- The Hardwood admin-board-stock line item shows the stock length with its
  calculated count, for example: `Need 2 x 9 ft boards · 12 ft`.
- The Specialty Sheet Rip line item shows the configured stock length beside
  its rip count, for example: `2 rips x 9 ft`.
- Labels use the parsed `ripLength` value, so 9 ft and every other valid
  positive whole-number length display as configured.

## Error Handling

- Existing parser behavior remains the safety boundary: invalid, absent, or
  non-integral stock lengths resolve to 10 ft before display or calculation.
- Tally values are clamped from zero through the calculated target.
- A missing progress record resolves to zero unless a legacy Boolean entry is
  explicitly `true`.

## Testing

- Test length labels for 9 ft and another non-preset length.
- Test that only Sheet rows in the Saw Rip List are tally-eligible; regular
  Hardwoods Sheet rows remain display-only and no Sheet Skip controls return.
- Test tally-to-checkbox and checkbox-to-tally synchronization.
- Test legacy Boolean completion fallback and tally clamping.

## Out of Scope

- Changing board-stock authoring or `ripLength` parsing.
- Adding tally controls to Specialty Job Detail.
- Changing BD FT tally behavior or ordinary Hardwood cut-list progress.
