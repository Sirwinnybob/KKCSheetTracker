# Specialty Job Screen — Experimental Kanban Layout

Date: 2026-09-24
Status: Approved design, not implemented

## Goal

Offer an experimental kanban layout for the specialty job screen (`SpecialtyJobDetailScreen`),
modeled on the Supply category board: one column per station, cards per item. The existing
sectioned list stays the default. If the kanban layout proves useful, its toggle moves to Settings.

## Toggle and persistence

- Icon button in the job screen's top bar: `ViewKanban` while in list view, `ViewList` while in
  kanban view. Tapping swaps layouts.
- Stored per tablet in `UiPreferencesStore` as `specialty_kanban_layout` (Boolean, default `false`).
  One global choice, shared across all jobs; survives leaving the screen and app restarts.

## Top action row (both list and kanban views)

- The four `SpecialtyActionWidget` cards (Door Panels, Rip List, Closet Rods, Split View) are
  removed and become pills in the existing reference pill row.
- Row order: Door Panels, Rip List, Closet Rods (only when `availability.hasClosetRods`),
  Split View, then a padded vertical `|` divider, then the existing reference pills (Assembly,
  Plans & Elevations, Delivery, Pulls, 3D, Print — each still gated by availability).
- `KKCPillActionRow` gains an optional divider between two groups of pills. Existing callers are
  unchanged (default: no divider).

## Kanban screen layout (top to bottom, top part fixed)

1. Summary line (`x / y items complete`, or the existing loading text).
2. Action pill row (above).
3. Station pill row: `KKCSlidingTabRow`, one pill per board column.
4. Board, filling the remaining height down to the bottom of the screen.

The top part does not scroll away; columns scroll vertically on their own.

## Board and columns

- Column order:
  1. Sheet Rips — first, only when the job has sheet rip items.
  2. Stations in the Settings station order (`viewerDefaults.stationOrder` via
     `specialtyDetailStationOrder`). Stations with no items for this job get no column.
  3. Other — last, only when there are station-less items.
- Default collapsed-section settings are ignored; columns are always open.
- Column data comes from the existing `buildSpecialtyDetailSections` (plus the sheet rip items).
- Column header: solid bar in the station color from `stationBarColor` (same colors as the list
  headers), white uppercase station name on the left, `done/total` on the right. Sheet Rips uses a
  dark slate color; Other uses gray.
- Columns are 260dp wide (about three visible in portrait) and fill the board height. Each column
  is a vertical lazy list; its bottom content padding lets the last card clear the nav bar and the
  Add Item decoration.
- Horizontal board: plain scrolling row (non-lazy), same as the Supply board. Columns are built a
  few up front, then one per frame. Column positions and the active column come from the existing
  `SupplyBoardState` (`rememberSupplyBoardState`, `position()`, `activeKey()`, `scrollToColumn`).
- Station pill row follows the board scroll while panning and settles on the column at the left
  edge. Tapping a pill scrolls to that column (instant jump when low-end animations are disabled).
- The Sheet Rips column renders each rip item with the existing sheet rip tally row, sized to the
  column width.

## Cards (station columns and Other)

Top to bottom:

- Top row: the checkbox for this column's station on the left (filled with the station color when
  checked); delete (red trash icon) in the top-right corner.
- Title (e.g. `#26 - 3 Panel Island End`).
- Step count (`1/3 steps complete`) and a thin progress bar in the station color.
- Station dots: one small dot per other station the item needs, in that station's color; filled =
  that station done, outline = not done. No station text tags.
- Material, or Order Date — whichever the item has, same as the list row — cut to one line with
  ellipsis.
- Bottom row: filled `View` button, outlined `Edit` button with the pencil icon after the label,
  then `Add dims...` on items where the list shows it today.

In the Other column (no stations), the checkbox uses the item's single toggle, exactly as the list.

## Behavior

- Checkbox completes only this column's station for the item, via the same toggle
  (`checklistTogglesForItem`) and save path as the list: optimistic override, in-flight tracking,
  revert and the existing "Failed to update checklist item" message on failure.
- View, Edit, delete, and Add dims call the same handlers as the list view.
- Checked cards drop to the bottom of their column and render dimmed. Unchecked cards keep list
  order; checked cards keep list order among themselves. With animations enabled the move animates
  (`animateItem`); in low-end mode (animations disabled) it jumps.
- Checking a station updates that item's dot in every other column it appears in.

## Edge cases

- No items: show the existing "No specialty checklist items found." message instead of a board.
- Loading: the summary line shows the existing loading text.
- Station order changed in Settings: columns reorder the next time the screen opens.
- Low-end mode: no move animations, instant pill jumps; incremental column building still applies.

## Structure

- New file `ui/specialty/SpecialtyKanbanBoard.kt`: board, column, and card composables plus pure
  helpers (column building, card ordering).
- `SpecialtyJobDetailScreen`: top-bar toggle, preference read/write, new action row, and a switch
  between the existing list and the kanban board. The list view's content is otherwise unchanged.
- `KKCPillActionRow`: optional group divider.
- `UiPreferencesStore`: `specialty_kanban_layout` getter/setter.

## Testing

- Unit tests (pure helpers):
  - Column building: Sheet Rips first, stations in Settings order, empty stations skipped, Other
    last.
  - Card ordering: done cards move to the bottom; both groups keep list order.
  - Kanban preference defaults to off.
- Pill row: grouped actions with a divider lay out in order.
- On-device: debug build on the tablet, open job 106, toggle kanban, screenshot. Verify column
  colors, horizontal panning, pill tracking and tap-to-jump, checking an item off (moves to bottom,
  dots update), and the new action row in both views.

## Out of scope

- Moving the toggle to Settings (only if the layout is kept).
- Dragging cards between columns.
- Dark-mode primary contrast (handled as a separate theme fix).
