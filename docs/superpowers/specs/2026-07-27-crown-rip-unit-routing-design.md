# Crown Rip Unit Routing Design

## Goal

Route Crown rip items by their existing `mode` without changing stored data or
progress accounting.

## Behavior

### Specialty Sheet Rips

- A Crown item appears only when its normalized mode is `sheet`.
- Crown items whose mode is `bd_ft`, blank, or unknown do not appear in this
  section.
- Non-Crown specialty rip behavior is unchanged.

### Hardwoods Rip List

- Crown items remain visible for both unit modes.
- Every Crown row displays a unit label: `Sheet` for `sheet`; `BD FT` for
  `bd_ft`, blank, or unknown modes. Treating absent legacy metadata as `BD FT`
  preserves the model's current default.
- Any `Sheet` row rendered in Hardwoods does not render tally controls. Its
  completion count is display-only there; Specialty remains the interactive
  sheet-rip surface.
- A `BD FT` Crown row retains the existing tally controls and behavior.
- Non-Crown `BD FT` hardwood rip rows are unchanged; existing `Sheet` rows
  receive only the tally-control visibility change above.

## Flexible Stock-Length Compatibility

- The shared `board_stock.json` contract accepts any positive whole-number
  `ripLength` value.
- KKCSheetTracker must preserve a valid `9` (or any other positive length)
  rather than falling back to `10`; existing generic rip-count calculations
  then require no special-case math.
- Hours Tracker owns the authoring side of this file and must accept/persist a
  positive whole-number length rather than a fixed preset list.

## Data and Compatibility

Use the existing rip-item `mode` field. No migration, new persistence field,
or progress-key change is needed.

## Testing

- Unit-test Crown visibility in Specialty for `sheet`, `bd_ft`, and blank
  modes.
- Unit-test the Hardwood display label and tally-control eligibility for the
  same modes.
- Unit-test parsing of `ripLength: 9` and another positive non-preset value,
  plus the existing `ceil(feet / ripLength)` calculation path.
- Run the focused tests, the app unit-test suite, and a release build.

## Out of Scope

- Changing Crown item creation, editing, totals, or progress storage.
- Changing non-Crown rip-row behavior other than hiding tally controls for
  existing `Sheet` rows in Hardwoods.
