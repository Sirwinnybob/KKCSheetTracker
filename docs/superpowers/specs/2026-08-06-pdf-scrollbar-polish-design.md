# PDF Label Scrollbar — Visual & Motion Polish

## Scope

`PdfLabelScrollbar` in [PdfLabelScrollbar.kt](../../../app/src/main/java/com/kkc/sheettracker/ui/components/PdfLabelScrollbar.kt): the always-visible track (idle ticks + current-page indicator + rail) and the drag carousel's entrance/exit transition. The carousel's internal contents (thumbnails, per-card grow/shrink) are unchanged.

## Current state

- Current-page indicator is a flat `MaterialTheme.colorScheme.primary` rectangle that instantly resizes from a 2dp tick to a 64dp pill when `currentEntryIndex` changes — no interpolation.
- Rail is a single flat `outlineVariant` 4dp line, uniform top to bottom.
- Drag carousel appears/disappears with a hard `if (isDragging)` — no enter/exit transition.
- Idle ticks are static; there is no tap-feedback effect on the track.

## Changes

### 1. Pill shape — solid gradient capsule

Replace the flat-color background with:
- `Modifier.shadow(elevation, shape, clip = false)` for the external drop shadow (per this repo's frosted-shadow rule in [CLAUDE.md](../../../CLAUDE.md) — external shadow only, no bleed-through risk here since the fill is solid, not semi-transparent).
- `.background(Brush.verticalGradient(listOf(lighter primary, primary)), shape = RoundedCornerShape(4.dp))` for the fill.
- A 1dp inner highlight stroke for depth (`Modifier.border` or drawn inset).

No haze/blur — this shape is a solid gradient fill, not a frosted/translucent element, so it does not need `hazeEffect` and is not subject to the shadow-bleed issue that applies to semi-transparent backgrounds.

### 2. Position/size motion — springy bounce, drag-aware

- The pill's `top` offset and `height` are driven by `Animatable<Float>` (or `animateDpAsState`/`animateFloatAsState`) with a bouncy spring spec (`Spring.DampingRatioMediumBouncy`, `Spring.StiffnessLow`, tuned to match the overshoot-then-settle feel validated in the visual mockup) whenever `currentEntryIndex` changes via **tap or programmatic jump**.
- **During active drag** (`isDragging == true` and the finger is moving), the pill's position tracks `touchYPx` / the hit-tested entry directly, 1:1, with no spring lag — same responsiveness as today.
- When the drag ends (finger lifts, `onDragEnd`), the pill springs from its last direct-follow position to the resolved entry's resting position/size using the same bouncy spec as a tap-jump.
- This requires distinguishing "animate via spring" vs. "set directly" states — e.g. an `isDragging` gate around whether the position `Animatable` is `snapTo`'d (during drag) or `animateTo`'d (spring, on settle/tap).

### 3. Rail — progress fill

- Add a second rail layer, same width/shape as the existing `outlineVariant` line, painted with the pill's gradient, drawn from the track's top down to the pill's current vertical center.
- Recompute the fill height every time the pill's position changes (drag-follow or spring-settled) — reuse the same real-position values already driving pill placement, no separate estimate.
- Ticks whose vertical position falls within the filled region render at a brighter alpha than ticks below it (e.g. `0.55f` filled vs. the current `0.5f` unfilled — tune during implementation for clear-enough contrast without being loud).

### 4. Idle & tap feedback

- Idle (untouched, not dragging): fully static — no pulse/glow/breathing loop on the pill or rail.
- Tap-to-jump (`detectTapGestures` in the existing `pointerInput`): on tap, spawn a small radial ripple centered on the tap's Y position, fading out over ~300–350ms. Implemented as a lightweight independent `Animatable<Float>` (alpha/radius) per tap — not coupled to the pill's own animation state, so overlapping taps don't fight each other.
- Ripple draws on the rail layer, behind or alongside the pill (z-order: rail → fill → ripple → ticks → pill, or equivalent — exact order tuned in implementation so the ripple reads clearly without obscuring the pill).

### 5. Carousel entrance/exit transition

Wrap the existing carousel `Column` (currently gated by a bare `if (isDragging)`) in `AnimatedVisibility(visible = isDragging)`:
- Enter: `slideInHorizontally(animationSpec = tween(200, easing = FastOutSlowInEasing)) { fullWidth -> fullWidth } + fadeIn(tween(200))` — the carousel starts fully offset to the right (behind/under the track) and slides + fades into its resting position.
- Exit: mirrored `slideOutHorizontally(tween(200, easing = FastOutSlowInEasing)) { fullWidth -> fullWidth } + fadeOut(tween(200))`.
- Deliberately a plain tween, not the bouncy spring used for the pill — the carousel is 5 stacked cards; a bounce there would read as jitter rather than liveliness. The bouncy spring is reserved for the single pill.

## Low-end device handling

Reuse the existing `LocalLowEndMode` pattern already used for the carousel's haze/shadow:
- Pill shadow: skip (`0.dp` elevation) when `lowEnd.shadowsDisabled`, same as the carousel chips do today.
- Ripple: skip entirely on low-end (cheap to omit, avoids extra per-frame `Animatable` work on constrained devices).
- Carousel slide/fade transition: keep as-is even on low-end — it's a single `AnimatedVisibility`, not per-item, so cost is negligible compared to the per-card thumbnail decoding already gated elsewhere.

## Testing

No new unit-testable logic — `indexForTouchY` and the hit-testing math are unchanged. Verify manually on-device (the `debug-android-tablet` skill's workflow): build debug APK, install, and check:
- Tap-to-jump: pill springs with visible bounce, ripple appears and fades at tap point.
- Drag: pill follows finger 1:1 with no lag, then springs to rest on release.
- Rail fill updates live during drag, tick brightness matches filled/unfilled region correctly.
- Carousel slides out from behind the track on drag start, slides back out on drag end.
- Light and dark theme.
- Low-end mode flag path (shadow/ripple skipped, no crash, carousel transition still plays).
