# Specialty Plywood Crown Label Design

## Goal

Make a plywood-crown checkbox in Specialty job details self-identifying instead of showing only its configured crown name.

## UI behavior

- A sheet-mode board-stock item whose type is `crown` displays `Plywood Crown — <name>` as its secondary label.
- All other sheet-rip items retain their existing name-only secondary label.
- The material line, completion state, tally, checkbox behavior, molding preview, and persisted data remain unchanged.

## Implementation boundary

- Add a small pure label helper beside the existing Specialty sheet-rip helpers.
- Use the helper for the checkbox secondary text in `SpecialtyJobDetailScreen`.
- Add unit tests for a sheet-mode crown item and a non-crown item.

## Acceptance criteria

- A plywood crown named `Crown` is shown as `Plywood Crown — Crown`.
- A non-crown sheet-rip item still shows its configured name exactly.
- Existing Specialty job-detail tests pass.
