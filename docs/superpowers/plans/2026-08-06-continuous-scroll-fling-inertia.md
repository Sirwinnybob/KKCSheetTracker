# Continuous Scroll Fling Inertia — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add fling inertia to the continuous scroll PDF viewer so finger-swipes coast to stop with momentum.

**Architecture:** Replace the low-level `awaitEachGesture`/`awaitPointerEvent` gesture loop with `detectTransformGestures`, track velocity during the gesture, and launch an inertia decay on finger-up using a simple friction loop. Add a guard to prevent the `scrollToPage` LaunchedEffect from fighting user-initiated flings. Remove the now-unnecessary `scrollDeltaChannel`.

**Tech Stack:** Kotlin, Jetpack Compose, Compose Foundation gestures

## Global Constraints

- Only `ContinuousReferencePdfPane.kt` is modified; single-page viewer, scrollbar, and CNC viewer untouched
- Must preserve existing pinch-zoom behavior and zoomed-in crop-tile re-render debounce
- Must preserve existing markup-drawing scroll lock (`gesturesEnabled = !markupEnabled`)
- Scroll orientation (Vertical for fullscreen, Horizontal for split-mode panes) unchanged

---

### Task 1: Remove `scrollDeltaChannel` and its drain coroutine

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/components/ContinuousReferencePdfPane.kt`

`detectTransformGestures` has no `@RestrictsSuspension` limitation, so `listState.scrollBy()` can be called directly in the callback.

- [ ] **Step 1: Remove the channel creation and drain coroutine**

Delete lines that create `scrollDeltaChannel` (~lines 267-271):

```kotlin
// REMOVE this block:
val scrollDeltaChannel = remember(listState) { Channel<Float>(Channel.UNLIMITED) }
LaunchedEffect(scrollDeltaChannel, listState) {
    for (delta in scrollDeltaChannel) {
        listState.scrollBy(delta)
    }
}
```

- [ ] **Step 2: Clean up unused import**

Remove `import kotlinx.coroutines.channels.Channel` from imports.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/components/ContinuousReferencePdfPane.kt
git commit -m "refactor: remove scrollDeltaChannel from ContinuousReferencePdfPane"
```

---

### Task 2: Add `isScrollInProgress` guard to `scrollToPage` LaunchedEffect

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/components/ContinuousReferencePdfPane.kt:~287-298`

Prevent `animateScrollToItem` from fighting a user-initiated fling. The existing guard `if (current == scrollToPage)` protects against the steady-state case but doesn't help during a fling where the list is still moving through pages.

- [ ] **Step 1: Add the guard**

Add one line at the top of the scrollToPage LaunchedEffect body:

```kotlin
LaunchedEffect(scrollToPage, totalPages) {
    if (totalPages <= 0 || scrollToPage !in 1..totalPages) return@LaunchedEffect
    if (listState.isScrollInProgress) return@LaunchedEffect  // NEW: don't fight user fling
    val current = listState.firstVisibleItemIndex + 1
    if (current == scrollToPage) return@LaunchedEffect
    isProgrammaticScroll = true
    try {
        listState.animateScrollToItem((scrollToPage - 1).coerceIn(0, totalPages - 1))
    } finally {
        isProgrammaticScroll = false
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/components/ContinuousReferencePdfPane.kt
git commit -m "fix: prevent scrollToPage from fighting user fling inertia"
```

---

### Task 3: Replace gesture handler with `detectTransformGestures` and add fling

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/components/ContinuousReferencePdfPane.kt`

This is the core change. Replace the entire gesture handler block (~lines 460-520) with `detectTransformGestures` plus velocity tracking and post-gesture fling.

- [ ] **Step 1: Update imports**

Remove these imports:
```kotlin
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
```

Add these imports:
```kotlin
import androidx.compose.foundation.gestures.detectTransformGestures
import kotlinx.coroutines.coroutineScope
```

`coroutineScope` is needed so the fling loop can call `delay()` within the `pointerInput` suspend scope while keeping the gesture handler re-entrant.

- [ ] **Step 2: Replace gesture handler**

Replace the existing `Modifier.pointerInput(orientation) { ... }` block (the entire `awaitEachGesture` loop) with:

```kotlin
Modifier.pointerInput(orientation) {
    detectTransformGestures(panZoomLock = false) { centroid, pan, zoom, rotation ->
        // Track velocity for post-gesture fling
        val now = System.nanoTime()
        if (lastPanTimeNanos > 0L) {
            val dt = ((now - lastPanTimeNanos) / 1e9f).coerceAtLeast(0.001f)
            lastVelocityY = pan.y / dt
            lastVelocityX = pan.x / dt
        }
        lastPanTimeNanos = now
        isInteracting = true

        val viewW = paneSize.width
        val viewH = paneSize.height
        val next = computeZoomPan(
            zoom = sharedZoom,
            panX = if (orientation == Orientation.Vertical) sharedCrossPan else 0f,
            panY = if (orientation == Orientation.Vertical) 0f else sharedCrossPan,
            zoomChange = zoom,
            panChange = pan,
            centroid = centroid,
            viewWidth = viewW,
            viewHeight = viewH,
            minZoom = CONTINUOUS_MIN_ZOOM,
            maxZoom = CONTINUOUS_MAX_ZOOM
        )
        sharedZoom = next.zoom
        when (orientation) {
            Orientation.Vertical -> {
                val maxCross = maxCrossAxisPan(viewW.toFloat(), next.zoom)
                sharedCrossPan = next.panX.coerceIn(-maxCross, maxCross)
                if (viewH > 0 && next.panY != 0f) {
                    listState.scrollBy(-next.panY / next.zoom)
                }
            }
            Orientation.Horizontal -> {
                val maxCross = maxCrossAxisPan(viewH.toFloat(), next.zoom)
                sharedCrossPan = next.panY.coerceIn(-maxCross, maxCross)
                if (viewW > 0 && next.panX != 0f) {
                    listState.scrollBy(-next.panX / next.zoom)
                }
            }
        }
    }
    // Gesture ended — reset state and launch fling if velocity exceeds threshold
    isInteracting = false
    val flingVelocity = when (orientation) {
        Orientation.Vertical -> -lastVelocityY
        Orientation.Horizontal -> -lastVelocityX
    }
    isFlinging = true
    if (abs(flingVelocity) > 100f) {
        coroutineScope {
            launch {
                var vel = flingVelocity
                while (isActive && abs(vel) > 1f) {
                    vel *= 0.95f
                    listState.scrollBy(vel / 60f)
                    delay(16)
                }
            }
        }
    }
    isFlinging = false
    lastPanTimeNanos = 0L
    lastVelocityY = 0f
    lastVelocityX = 0f
}
```

**Critical detail:** `detectTransformGestures` passes `zoom` as a multiplicative factor (1.0 = no change, 1.02 = 2% zoom in). The current code uses `calculateZoom()` which returns the same multiplicative factor. `computeZoomPan` takes `zoomChange: Float` and does `zoom * zoomChange` — this is already a multiplicative factor, so the math is unchanged.

**Another critical detail:** `detectTransformGestures` passes `pan: Offset` as screen-pixel deltas. The current code uses `calculatePan()` which also returns `Offset` in screen pixels. So `panChange: Offset` in `computeZoomPan` works identically. The only thing that changes is we call `listState.scrollBy()` directly instead of through the channel.

- [ ] **Step 3: Add velocity tracking state variables**

Add these state variables near the existing `isInteracting` declaration (~line 258):

```kotlin
var lastVelocityY by remember(fileIdentitySeed, orientation) { mutableFloatStateOf(0f) }
var lastVelocityX by remember(fileIdentitySeed, orientation) { mutableFloatStateOf(0f) }
var lastPanTimeNanos by remember(fileIdentitySeed, orientation) { mutableLongStateOf(0L)
var isFlinging by remember(fileIdentitySeed, orientation) { mutableStateOf(false) } }
```

Note: `mutableLongStateOf` needs import `import androidx.compose.runtime.mutableLongStateOf`.

- [ ] **Step 4: Build and verify compilation**

```bash
cd C:\Scripts\KKCSheetTracker
.\gradlew.bat assembleDebug
```

Expected: BUILD SUCCESSFUL


**Note on `isFlinging` and the `settled` debounce:** The existing `settled` `LaunchedEffect` at line ~273 watches `isInteracting || listState.isScrollInProgress`. Since `scrollBy()` is instantaneous and `isScrollInProgress` could flicker false between fling frames, add `isFlinging` to the effect keys and condition:

```kotlin
LaunchedEffect(isInteracting, listState.isScrollInProgress, isFlinging, fileIdentitySeed, orientation) {
    if (isInteracting || listState.isScrollInProgress || isFlinging) {
        settled = false
    } else {
        delay(120)
        settled = true
    }
}
```



```bash
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

Test scenarios:
1. Open an Assembly PDF with many pages, enable continuous scroll, swipe-and-release → content coasts to stop
2. Pinch-zoom in, then fling → content scrolls smoothly at zoomed scale
3. Drag scrollbar while a fling is decelerating → scrollbar takes over cleanly
4. Fling, then tap scrollbar → jump lands on tapped page
5. Toggle markup pen on → scrolling disabled (existing behavior preserved)

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/components/ContinuousReferencePdfPane.kt
git commit -m "feat: add fling inertia to continuous scroll gesture handler"
```

