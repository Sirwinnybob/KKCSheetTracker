# Continuous PDF Edge Render Debounce Design

**Date:** 2026-08-11

## Goal

In continuous PDF mode, render the visible pages at full quality after they have stayed visually stationary for 300 ms, even if the custom fling coroutine is still decaying at a document edge.

## Root Cause

`ContinuousReferencePdfPane` currently defines its render-settled state as the absence of interaction, LazyList scrolling, and fling activity. At a document boundary, a fling can continue to decay after list movement and edge overscroll have both reached their clamps. Because `isFlinging` remains true, full base-page and zoom crop rendering remains suppressed until the velocity finally falls below its threshold.

## Design

### Visible-motion debounce

Keep the existing protection against rendering while the content is moving. Add a render-settled state driven by actual visual-position changes rather than fling lifetime alone. A change to the LazyList position, main-axis edge overscroll, or cross-axis pan restarts a 300 ms debounce. When none of those values changes for 300 ms, set the state to render-ready even if `isFlinging` is still true.

This preserves the existing behavior during a real fling: each animation frame that moves the visible PDF restarts the timer. It also removes the edge case: once the page is pinned at the start/end edge and every further animation frame is visually inert, the timer completes and visible page rendering begins.

### Idle behavior

When there is no gesture, list scroll, or fling, retain the current 120 ms idle delay. The 300 ms path applies only while a fling is technically active but produces no visible content movement. A new touch or visible movement invalidates the settled state immediately.

### Rendering consumers

Continue using the shared `settled` state as the gate for full base-page and zoom crop rendering. Thumbnail behavior remains immediate and unchanged. No PDF-engine, cache, scroll physics, or scrollbar changes are needed.

## Test Coverage

Extract or add a small pure helper that determines whether the render delay is 300 ms for a visually stationary active fling versus 120 ms for a fully idle pane. Add JVM tests proving:

- An active fling with no visible movement chooses the 300 ms render debounce.
- Visible movement keeps rendering blocked and restarts the timer.
- A fully idle pane retains the 120 ms delay.

## Acceptance Criteria

- At either PDF edge, full-quality visible-page rendering begins after approximately 300 ms of no visible content movement while fling momentum continues.
- While pages, overscroll, or zoom pan are visibly moving, full rendering remains deferred.
- After the fling actually ends, the existing short idle settle behavior is retained.
- Immediate thumbnail placeholders and all existing fling/navigation behavior remain unchanged.

## Scope

- `ContinuousReferencePdfPane.kt` and its focused JVM test class only.
- Paged PDF mode and unrelated viewer screens are out of scope.
