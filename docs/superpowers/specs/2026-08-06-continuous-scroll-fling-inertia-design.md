# Continuous Scroll Fling Inertia — Design

**Date:** 2026-08-06

## Problem

When panning with a finger in continuous scroll mode, the content stops dead on finger lift — no fling momentum, no coast-to-stop. Standard Android scrollables fling on release; the continuous PDF viewer should too.

## Root cause

`ContinuousReferencePdfPane.kt` disables the LazyColumn's built-in scroll handling (`userScrollEnabled = false`) and owns all gestures through a low-level `awaitEachGesture`/`awaitPointerEvent` loop. Pan deltas route through `listState.scrollBy()`, which stops the instant the finger lifts. No velocity tracking or post-release animation exists.

## Design

### Gesture handler: switch to `detectTransformGestures`

Replace the current `awaitEachGesture`/`awaitPointerEvent` loop with `detectTransformGestures(panZoomLock = false)`. This single call handles zoom and pan simultaneously — no gesture disambiguation needed, no risk of scroll-vs-zoom races.

During the gesture: pan deltas route to `listState.scrollBy()` (with the existing `/ sharedZoom` division so zoomed-in panning feels natural), and zoom applies through the existing `computeZoomPan` function. No math changes.

On finger-up: velocity computed from recent frames feeds into an `Animatable` with `animateDecay`, producing a platform-native fling animation.

### Feedback loop guard

The `scrollToPage` LaunchedEffect must not fight a user-initiated fling. One additional guard:

    if (listState.isScrollInProgress) return@LaunchedEffect

This prevents `animateScrollToItem` from jerking the scroll to whatever page `snapshotFlow` reported mid-fling. External navigation (scrollbar drag, initial load, resume-to-page) works unchanged since `isScrollInProgress` is false at those times.

### Velocity computation

`detectTransformGestures` does not provide velocity directly. Track it using `withFrameNanos()` timestamps in the gesture callback. On release, use the last computed velocity. Launch fling only if velocity exceeds a ~100 px/s threshold to avoid tiny drifts.

### Fling decay

Compose's `Animatable` with `exponentialDecay` provides native friction physics:

    val decay = exponentialDecay<Float>(frictionMultiplier = 2f)
    Animatable(-velocityY).animateDecay(decay) { delta, _ ->
        listState.scrollBy(delta)
        delta
    }

### Zoom re-render compatibility

The existing `settled` debounce already monitors `listState.isScrollInProgress`. During the `animateDecay` fling, `isScrollInProgress` stays true, so `settled` stays false, and expensive crop-tile re-renders are suppressed until the fling fully decelerates. No rendering pipeline changes needed.

### Scroll orientation

The existing orientation switch applies unchanged: `Orientation.Vertical` uses `pan.y` for vertical scrolling; `Orientation.Horizontal` uses `pan.x` for horizontal scrolling (split-mode panes).

### `scrollDeltaChannel` simplification

The channel existed because `awaitPointerEvent` runs in a `@RestrictsSuspension` scope that cannot call arbitrary suspend functions. `detectTransformGestures` has no such restriction — `listState.scrollBy()` can be called directly in the callback. Remove the channel and its drain coroutine.

## Files touched

- `app/src/main/java/com/kkc/sheettracker/ui/components/ContinuousReferencePdfPane.kt` — gesture handler, velocity tracking, fling launch, feedback-loop guard, channel removal

## Out of scope

- Single-page viewer (`ReferencePdfPane.kt`) — no changes
- PdfLabelScrollbar — already paginated, stays paginated
- CNC Sheet Viewer — separate renderer, not touched
- Horizontal scroll orientation in split mode — same fling behavior applies, just along the other axis

## Testing

- Manual: swipe-and-release in continuous scroll mode on a multi-page PDF; confirm content coasts with momentum and decelerates naturally
- Manual: pinch-zoom in, then fling; confirm content scrolls smoothly at zoomed scale and crop-tile re-render only kicks in after fling settles
- Manual: drag the scrollbar while a fling is decelerating; confirm scrollbar takes over cleanly (no fight between two animations)
- Manual: fling, then immediately tap a different page on the scrollbar; confirm jump lands on tapped page
