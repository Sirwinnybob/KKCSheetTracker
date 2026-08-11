# Continuous PDF Edge Render Debounce Design

**Date:** 2026-08-11

## Goal

In continuous PDF mode, render each page at full quality after it has remained actually on screen for 300 ms, even while a slow scroll or custom fling continues.

## Root Cause

`ContinuousReferencePdfPane` currently gates every full base-page and zoom crop render on one shared `settled` state. Its first attempted replacement made that state depend on global visual motion, which still postpones rendering during a slow scroll because every frame restarts the delay. Rendering eligibility must instead belong to each visible page.

## Design

### Per-page visibility dwell

Each page starts a 300 ms eligibility timer when it enters the unbuffered visible range. If it remains there when the timer completes, its full base-page render may start even if the document is moving. If it exits that range before the timer completes, the timer is cancelled; no full decode is started for that page.

This supports both reported cases: edge-pinned momentum does not block the page already on screen, and pages held on screen during a slow scroll become sharp after their own 300 ms dwell. Fast scrolling still avoids work for pages that leave before the dwell expires.

### Render consumers

Replace the shared `settled` condition in the base-page effect with page-local visibility eligibility. Keep thumbnails immediate. The crop effect continues to react to zoom and geometry as it does today; no PDF-engine, cache, scroll physics, or scrollbar changes are needed.

## Test Coverage

Extract or add a small pure helper that represents page-dwell eligibility. Add JVM tests proving:

- A page entering the render window receives a 300 ms dwell deadline.
- A page leaving before that deadline is not eligible.
- A page remaining in the window through that deadline becomes eligible regardless of scroll/fling state.

## Acceptance Criteria

- A visible page becomes eligible for full-quality rendering after approximately 300 ms in the render window during slow scrolling or continued fling momentum.
- A page that leaves the render window before 300 ms does not start a full base decode.
- Edge-pinned momentum does not delay the visible page's 300 ms timer.
- Immediate thumbnail placeholders and all existing fling/navigation behavior remain unchanged.

## Scope

- `ContinuousReferencePdfPane.kt` and its focused JVM test class only.
- Paged PDF mode and unrelated viewer screens are out of scope.
