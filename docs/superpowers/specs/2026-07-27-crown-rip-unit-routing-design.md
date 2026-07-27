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
- A `Sheet` Crown row does not render tally controls. Its completion count is
  display-only in Hardwoods.
- A `BD FT` Crown row retains the existing tally controls and behavior.
- Non-Crown hardwood rip rows are unchanged.

## Data and Compatibility

Use the existing rip-item `mode` field. No migration, new persistence field,
or progress-key change is needed.

## Testing

- Unit-test Crown visibility in Specialty for `sheet`, `bd_ft`, and blank
  modes.
- Unit-test the Hardwood display label and tally-control eligibility for the
  same modes.
- Run the focused tests, the app unit-test suite, and a release build.

## Out of Scope

- Changing Crown item creation, editing, totals, or progress storage.
- Changing non-Crown rip rows.
