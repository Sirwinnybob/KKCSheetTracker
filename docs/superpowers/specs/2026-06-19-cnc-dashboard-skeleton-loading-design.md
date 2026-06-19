# CNC Dashboard Skeleton Loading — Design

**Date:** 2026-06-19
**Mode scope:** CNC dashboard only

## Summary

The CNC dashboard's per-job/material derivation was recently sped up from
~2-2.8s to ~20-50ms (see `ProgressStore`/`AppStateStore` perf fix, same week).
At the new speed, the dashboard's initial render still visibly jumps: the
"Recent In-Progress Materials" and "Incomplete Remakes" card rows render at
their empty/collapsed size first, then grow once real data arrives a frame or
two later. The loading bar at the top of the screen — added when derivation
took seconds — now just flickers, since the work it signaled is over almost
instantly.

This change makes the two card-row sections render at their *populated* size
from the first frame (via a skeleton placeholder card using the exact same
layout as a real card, so the height is always correct with no hardcoded
numbers), and only shrinks them down — with an animation — if it turns out
there's genuinely nothing to show. It also removes the now-unnecessary loading
bar for the CNC dashboard specifically.

## Goals

- Eliminate the empty→populated layout jump on initial CNC dashboard load.
- No jump on subsequent background re-derivations (already a non-issue today
  since the dashboard StateFlow retains its last value between derives — this
  change must not regress that).
- Remove the CNC dashboard's loading bar.
- No hardcoded heights — skeleton sizing must come from the real card layout.

## Non-goals

- Any change to Hardwoods/Assembly/Specialty dashboards' loading indicators or
  layout (their underlying data sources were not part of the recent perf fix).
- A shimmer/pulse animation on the skeleton card. Given the loading window is
  now ~20-50ms, the skeleton is a static neutral placeholder, not an animated
  shimmer effect.
- Changing what counts as "empty" for either section (Recent's "Nothing is in
  progress right now" message and Remakes' fully-collapsed-when-empty behavior
  both stay as they are today — this only changes how we *arrive* at those
  states).

## "Has loaded once" signal

No new state. `AppUiState.lastUpdatedAt` (in `AppStateStore.kt`) already
starts at `0L` and is set only when a derivation cycle completes (`READY` or
`ERROR`); the in-between `DERIVING` update uses `.copy()` on the previous
state, so the field is preserved, not reset. It therefore never reverts to
`0L` once set.

```kotlin
val hasLoadedOnce = appUiState.lastUpdatedAt > 0L
```

This is computed inline in `CncDashboardContent` from the existing
`appUiState` collected state — no `remember`/`LaunchedEffect` needed. It's
correct across navigation (the underlying `AppStateStore` is a singleton, not
re-created per screen) and correct across background re-derives (stays `true`
forever after the first completion, whether success or error).

## Skeleton cards: nullable `item`, not a parallel composable

`CncRecentMaterialCard` and `CncRemakeMaterialCard` (in
`UnifiedModeDashboardScreen.kt`) change their `item` parameter from
`DashboardRecentMaterialItem` to `DashboardRecentMaterialItem?`.

Rule: when `item == null`, every element in the card still renders in the same
position — nothing is conditionally omitted. Only the *values* go neutral:

- Thumbnail box: same size, same icon fallback, no thumbnail fetch attempted.
- Material name / subtitle text: a single space (`" "`), not an empty string.
  An empty string's measured height isn't guaranteed across text-layout
  configurations; a real character guarantees the line reserves its normal
  height.
- Complete-count text + `ProgressPill`: pill renders with `done = 0, total = 0`
  instead of being omitted.
- `LinearProgressIndicator`: progress `0f`.
- The 4 accent pills row (`C`/`B`/`S`/`R`): all 4 still render, with blank
  counts, instead of the row being empty.
- `clickable` is a no-op (`{}`) when `item == null`.

This guarantees the skeleton's height is pixel-identical to a populated card's
height, automatically, because it's the same layout tree — no magic numbers
to keep in sync if the card design changes later.

Call sites (`CncRecentMaterialsSection`, `CncRemakesSection`) only invoke the
existing thumbnail-loading `produceState` block for real items; skeleton
iterations pass `thumbnail = null` directly.

## Section-level behavior

**`CncRecentMaterialsSection`** — header always renders. Content area wrapped
in `Modifier.animateContentSize()`:

| State | Content |
| --- | --- |
| `!hasLoadedOnce` | One skeleton card (`item = null`) |
| `hasLoadedOnce`, items non-empty | Real cards (today's behavior, unchanged) |
| `hasLoadedOnce`, items empty | "Nothing is in progress right now." (today's behavior, unchanged) |

Skeleton → real cards: no visible animation (heights match).
Skeleton → empty text: `animateContentSize()` shrinks smoothly.

**`CncRemakesSection`** — the whole section (header included) is wrapped at
the call site in `CncDashboardContent`:

```kotlin
AnimatedVisibility(
    visible = !hasLoadedOnce || dashboard.incompleteRemakeMaterials.isNotEmpty(),
    enter = fadeIn() + expandVertically(),
    exit = fadeOut() + shrinkVertically()
) {
    CncRemakesSection(...)
}
```

Inside `CncRemakesSection`, when visible and `!hasLoadedOnce`, render one
skeleton card; otherwise (visible implies `hasLoadedOnce && items.isNotEmpty()`
by the condition above) render real cards — no internal empty-state branch
needed, since the wrapper only shows the section when there's something to
show or it's still loading.

Skeleton → real cards: no animation (heights match, already visible).
Skeleton → genuinely empty: `AnimatedVisibility` collapses the whole section
away, matching today's "no section when empty" behavior but arrived at with a
shrink instead of just never appearing.

## Loading bar removal

In `CncDashboardContent`'s `DashboardShell(...)` call, change:

```kotlin
loading = scanState.status == ScanStatus.LOADING || appUiState.isRefreshing,
```

to:

```kotlin
loading = false,
```

`DashboardShell` itself is unchanged — Hardwoods/Assembly/Specialty call sites
keep their existing `loading = ...` wiring.

## Edge cases

| Case | Behavior |
| --- | --- |
| Derivation errors on first attempt (`AppDerivationStatus.ERROR`) | `lastUpdatedAt` is still set on the `ERROR` branch, so `hasLoadedOnce` becomes `true` and skeletons resolve to the empty state instead of spinning forever. The existing separate error banner (`DashboardShell`'s `errorMessage`) still surfaces the failure. |
| User navigates away and back to the dashboard before first load completes | `hasLoadedOnce` is sourced from the singleton `AppStateStore`, not screen-local state, so it reflects the real underlying status regardless of remounts. |
| Background re-derive after first load (e.g., a sheet marked complete on another tablet) | `hasLoadedOnce` stays `true`; sections update directly from real to real data, no skeleton reappears. |
| Remakes count goes from N>0 to 0 during normal use (not a load) | Same `AnimatedVisibility` path collapses the section. The section disappearing when empty is existing behavior; it now does so with a shrink animation instead of abruptly, which is an intentional side effect of this change, not just a load-time fix. |

## Files Changed

Modified:
- `app/src/main/java/com/kkc/sheettracker/ui/dashboard/UnifiedModeDashboardScreen.kt` — nullable `item` on both card composables, skeleton-card rendering, section wrapper changes, `loading = false`.

No data model, store, or navigation changes.

## Testing

No automated tests (no existing Compose UI test infrastructure in this
project for screenshot/semantics-based layout verification — confirmed by
the absence of `androidx.compose.ui.test` usage in `app/src/test`). Manual
test plan, on-device:

1. Force-stop the app, cold-launch into CNC mode → dashboard. Confirm no
   visible height jump in the Recent/Remakes sections; cards (or the
   "Nothing in progress" message / collapsed Remakes) appear directly in
   their final position.
2. On a job board with at least one incomplete remake: confirm the Remakes
   section is present from the first frame (as a skeleton) and fills with the
   real card without resizing.
3. On a job board with zero incomplete remakes: confirm the section briefly
   appears (skeleton) then collapses smoothly rather than never appearing or
   abruptly vanishing.
4. Confirm the top-of-screen loading bar no longer appears on the CNC
   dashboard, including during a manual pull-to-refresh.
5. Mark a sheet complete from another tablet (or simulate a background
   re-derive) while the CNC dashboard is open on this device; confirm the
   Recent/Remakes sections update without reverting to skeleton placeholders.
6. Navigate away from the dashboard and back before confirming step 1 again;
   confirm no skeleton replay on the second visit (since `hasLoadedOnce` is
   already `true` from the first visit this session).
