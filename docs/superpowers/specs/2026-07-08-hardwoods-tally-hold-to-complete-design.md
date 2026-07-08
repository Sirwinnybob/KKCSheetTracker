# Hardwoods Cut List — Hold-to-Complete / Hold-to-Zero Tally Buttons Design

**Date:** 2026-07-08
**Repo:** KKCSheetTracker (Android app)
**Status:** Approved

## Goal

In every tally UI on the Hardwoods workspace, tap-and-hold on the `+` or `-` tally
button jumps the line straight to complete or zero, instead of stepping by one.
Regular taps keep their existing single-step increment/decrement behavior.

## Scope

Three tally areas, all in the hardwoods module, get the gesture:

1. **Admin Board Stock** list (`HardwoodsWorkspaceScreen.kt`, item rows ~1968-2130) —
   feet-based board items with a `+`/`-` `Button` pair.
2. **Rip Cut / Board Stock** list (`HardwoodsWorkspaceScreen.kt`, `HardwoodsBoardStockList`,
   rows ~2259-2419) — the "Need N rips" rows, same `+`/`-` `Button` pair pattern.
3. **Classic Cut List** table (`ClassicCutListTable.kt`, `TableRow`, ~888-959) — the
   `IconButton` decrement/increment pair in the Tally column.

Not in scope: the existing row-level long-press in `ClassicCutListTable.kt` (jumps to
the row's location on the reference PDF via `onRowLongPress`/`startRowJump`) — that
gesture lives on the row background, not on the tally buttons, and is unaffected. The
finger-drawing drag-tally overlay (`trackTallyTarget`/`tallyHitTargets`) is also
unaffected — it only tracks button screen position, no gesture logic changes.

## Gesture mapping

- Hold `+` → **complete**: set `done = target` (i.e. `neededRips`, `boards`, or `qty`
  depending on the area).
- Hold `-` → **zero out**: set `done = 0`.
- Regular tap keeps existing single-step behavior on both buttons.

## Guards (all three areas)

Long-press is a no-op (no haptic, no state change, no store call) when:
- the line is skipped, or
- the line is already at the gesture's target value (already 0 for hold-`-`, already
  at max for hold-`+`).

Each area already computes an `enabled` flag per button that encodes exactly this
(`decrementEnabled`/`incrementEnabled` in Classic view; the equivalent inline
skip/bounds checks in the Admin and Rip Cut rows) — the long-press handler reuses the
same flag rather than duplicating the condition.

No confirmation dialog. Long-press fires a strong haptic pulse
(`HapticFeedbackType.LongPress`, matching the existing `startRowJump` pattern) so the
operator gets clear feedback that a bulk action happened, distinct from the light
feedback (if any) of a single tap.

## Implementation

No new `ProgressStore`/`HardwoodsProgressStore` functions — each area already has a
setter that takes an explicit `doneCount`, reused for both complete and zero:

| Area | Setter | Complete call | Zero call |
|---|---|---|---|
| Admin Board Stock | `setAdminBoardStockDone(jobFolderName, material, itemId, doneCount)` | `doneCount = boards` | `doneCount = 0` |
| Rip Cut / Board Stock | `setBoardStockRipDone(jobFolderName, material, normalizedWidth, source, doneCount)` | `doneCount = line.neededRips` | `doneCount = 0` |
| Classic Cut List | `setDoneCount(jobFolderName, docType, rowId, qty, doneCount)` | `doneCount = qty` | `doneCount = 0` |

**Admin Board Stock / Rip Cut / Board Stock (`HardwoodsWorkspaceScreen.kt`):**
The `+`/`-` `Button`s don't expose an `onLongClick` slot. Replace each with a
`Box` (`.heightIn(min = 32.dp).widthIn(min = 32.dp).clip(shape).background(color)`)
using `.combinedClickable(onClick = ..., onLongClick = ...)`, centering the same
`Icon` as today — same visual size/color/icon, adds the long-press branch.

**Classic Cut List (`ClassicCutListTable.kt`):**
The `IconButton`s are replaced the same way — a `Box(Modifier.size(36.dp).combinedClickable(...).trackTallyTarget(...))`
wrapping the existing `Icon`, preserving the current `trackTallyTarget` position
tracking (unrelated modifier, no interaction with the new gesture). Two new callbacks,
`onCompleteProgress: () -> Unit` and `onZeroProgress: () -> Unit`, are threaded through
`TableRow` alongside the existing `onIncrement`/`onDecrement`, wired in
`HardwoodsWorkspaceScreen.kt` to `hardwoodsProgressStore.setDoneCount(..., doneCount = qty)`
and `setDoneCount(..., doneCount = 0)` respectively.

## Testing

Existing `ProgressStoreTest`/`HardwoodsProgressStoreTest` already cover the setters
(`setDoneCount`, `setBoardStockRipDone`, `setAdminBoardStockDone`) at the store level —
no new store tests needed since no store code changes.

UI verification is manual (Compose gesture wiring, not easily unit-tested in this
codebase's existing test setup): on-device check with `debug-android-tablet` skill that
for each of the three areas, holding `+` jumps a partially-done line to complete,
holding `-` zeroes a partially-done line, and both are no-ops on a skipped line or a
line already at the target extreme.

## Out of scope

- Any change to the row-level long-press (PDF jump) or finger-drawing drag-tally
  overlay in Classic view.
- Confirmation dialogs or undo for the zero-out action.
- Applying the gesture to any tally UI outside the hardwoods module (e.g. sheets
  progress, CNC).
