# PDF Label Scrollbar Visual/Motion Polish Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restyle the PDF viewer's right-edge track (ticks + current-page indicator + rail) as a gradient capsule with springy-bounce motion and a progress-fill rail, add tap ripple feedback, and give the drag carousel a slide-in/out entrance transition.

**Architecture:** All changes are confined to `PdfLabelScrollbar` in one file. The pill's grow/shrink already happens through the existing per-row `Box` inside the `LazyColumn` (same mechanism the file's drag carousel already uses for its own per-card `animateDpAsState` sizing) — we extend that same idiom with a spring/snap-conditional `AnimationSpec` rather than introducing a parallel absolute-position `Animatable`. Rail fill and ripple positioning both read real measured bounds from `listState.layoutInfo`, the same source the file's existing `hitTest`/`indexForTouchY` already trust — never a separate pixel estimate, matching this file's established convention (see its own comments at lines 70–73 and 263–271).

**Tech Stack:** Kotlin, Jetpack Compose (Material3), `androidx.compose.animation` (spring/snap AnimationSpec, `AnimatedVisibility`).

---

## File Structure

Single file, no new files:

- **Modify:** `app/src/main/java/com/kkc/sheettracker/ui/components/PdfLabelScrollbar.kt`
  - Imports block
  - The idle-track `itemsIndexed` row content (pill shape + spring/snap height)
  - The rail background (add progress-fill layer)
  - The tap gesture handler (add ripple spawn)
  - A new ripple draw layer
  - The drag carousel's outer `if (isDragging)` gate (becomes `AnimatedVisibility`)

No test files — the spec confirms there is no new unit-testable logic; `indexForTouchY`/hit-testing are untouched. Verification is manual, on-device, via the `debug-android-tablet` skill's workflow (final task).

---

### Task 1: Add required imports

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/components/PdfLabelScrollbar.kt:1-62`

- [ ] **Step 1: Add the new imports**

Insert these lines into the existing import block (after the `androidx.compose.animation.core.tween` import at line 5 is a fine spot, but exact position doesn't matter — Kotlin import order isn't enforced by the compiler in this file):

```kotlin
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
```

- [ ] **Step 2: Verify it compiles**

Run: `.\gradlew.bat compileDebugKotlin`
Expected: `BUILD SUCCESSFUL` (unused-import warnings for the ones not wired up yet are fine and will disappear as later tasks use them).

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/components/PdfLabelScrollbar.kt
git commit -m "chore: add imports for scrollbar visual/motion polish"
```

---

### Task 2: Gradient capsule pill (shape + shadow + border)

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/components/PdfLabelScrollbar.kt:329-341`

- [ ] **Step 1: Replace the flat-color row background with a gradient capsule for the current row**

Find this block (the `itemsIndexed` body):

```kotlin
                itemsIndexed(entries, key = { _, entry -> entry.rowIndex }) { index, _ ->
                    val isCurrent = index == currentEntryIndex
                    Box(
                        modifier = Modifier
                            .padding(end = 6.dp)
                            .width(tickWidth)
                            .height(if (isCurrent) idlePillHeight else idleTickHeight)
                            .background(
                                if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                shape = RoundedCornerShape(4.dp)
                            )
                    )
                }
```

Replace it with:

```kotlin
                itemsIndexed(entries, key = { _, entry -> entry.rowIndex }) { index, _ ->
                    val isCurrent = index == focusIndex
                    val pillShape = RoundedCornerShape(4.dp)
                    val pillElevation = if (!lowEnd.shadowsDisabled) 3.dp else 0.dp
                    Box(
                        modifier = Modifier
                            .padding(end = 6.dp)
                            .width(tickWidth)
                            .height(if (isCurrent) idlePillHeight else idleTickHeight)
                            .then(
                                if (isCurrent) {
                                    Modifier
                                        .shadow(pillElevation, pillShape, clip = false)
                                        .background(
                                            Brush.verticalGradient(
                                                listOf(
                                                    lerp(MaterialTheme.colorScheme.primary, Color.White, 0.35f),
                                                    MaterialTheme.colorScheme.primary
                                                )
                                            ),
                                            shape = pillShape
                                        )
                                        .border(1.dp, Color.White.copy(alpha = 0.15f), pillShape)
                                } else {
                                    Modifier.background(
                                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                        shape = RoundedCornerShape(4.dp)
                                    )
                                }
                            )
                    )
                }
```

Note: `isCurrent` now compares against `focusIndex` (line 207, already `dragIndex ?: currentEntryIndex`) instead of `currentEntryIndex` directly — this makes the highlighted row track the drag immediately rather than waiting on `currentPage` to round-trip back down through the parent. Tick alpha (`0.5f`) is untouched here; Task 4 makes it position-aware.

- [ ] **Step 2: Verify it compiles and looks right**

Run: `.\gradlew.bat assembleDebug`
Expected: `BUILD SUCCESSFUL`. Install (`adb install -r app\build\outputs\apk\debug\app-debug.apk`) and open a PDF with the scrollbar visible — the current-page indicator should now show a blue-gradient capsule with a soft drop shadow instead of a flat rectangle.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/components/PdfLabelScrollbar.kt
git commit -m "feat: render scrollbar pill as gradient capsule with shadow"
```

---

### Task 3: Springy-bounce settle, instant snap during drag

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/components/PdfLabelScrollbar.kt` (the row `Box` from Task 2)

- [ ] **Step 1: Animate the row height instead of setting it directly**

Find (as left by Task 2):

```kotlin
                itemsIndexed(entries, key = { _, entry -> entry.rowIndex }) { index, _ ->
                    val isCurrent = index == focusIndex
                    val pillShape = RoundedCornerShape(4.dp)
                    val pillElevation = if (!lowEnd.shadowsDisabled) 3.dp else 0.dp
                    Box(
                        modifier = Modifier
                            .padding(end = 6.dp)
                            .width(tickWidth)
                            .height(if (isCurrent) idlePillHeight else idleTickHeight)
```

Replace the `.height(...)` line — and add the animated value above the `Box` — so the block becomes:

```kotlin
                itemsIndexed(entries, key = { _, entry -> entry.rowIndex }) { index, _ ->
                    val isCurrent = index == focusIndex
                    val pillShape = RoundedCornerShape(4.dp)
                    val pillElevation = if (!lowEnd.shadowsDisabled) 3.dp else 0.dp
                    val bounceSpring = spring<Dp>(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessLow
                    )
                    val animatedRowHeight by animateDpAsState(
                        targetValue = if (isCurrent) idlePillHeight else idleTickHeight,
                        animationSpec = if (isDragging) snap() else bounceSpring,
                        label = "trackRowHeight${entries[index].rowIndex}"
                    )
                    Box(
                        modifier = Modifier
                            .padding(end = 6.dp)
                            .width(tickWidth)
                            .height(animatedRowHeight)
```

(Everything below `.height(animatedRowHeight)` — the `.then(...)` gradient/border block — stays exactly as Task 2 left it.)

This gives two behaviors from one `AnimationSpec` switch: while `isDragging` is true, `snap()` makes the row jump straight to its target height every frame (the pill visually follows the finger 1:1, since the LazyColumn reflows instantly around the changed height); once `isDragging` goes false (tap-jump, or drag release), `bounceSpring` takes over and the row eases in with an overshoot-and-settle.

- [ ] **Step 2: Verify it compiles and behaves correctly**

Run: `.\gradlew.bat assembleDebug`, install, open a PDF with several pages.
Expected: tapping a tick makes the pill bounce-settle into place; dragging along the track makes the pill follow the finger with no lag or bounce, and it bounce-settles once you lift your finger.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/components/PdfLabelScrollbar.kt
git commit -m "feat: spring-animate scrollbar pill settle, snap during drag"
```

---

### Task 4: Rail progress fill + tick brightness

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/components/PdfLabelScrollbar.kt:306-316` (rail background)
- Modify: same file, the `itemsIndexed` tick `else` branch (from Task 2/3)

- [ ] **Step 1: Compute the real pixel center of the focused row**

Immediately above the rail background comment (originally at line 306, `// Thin continuous background rail...`), add:

```kotlin
            // Real measured center of the focused row, from the same layoutInfo source hitTest
            // already trusts — never a separate position estimate, consistent with this file's
            // established convention (see indexForTouchY above).
            val focusedItemInfo = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == focusIndex }
            val fillHeightPx = focusedItemInfo?.let { it.offset + it.size / 2f } ?: 0f
```

- [ ] **Step 2: Replace the single flat rail Box with a base line plus a filled overlay**

Find:

```kotlin
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 6.dp + (tickWidth - 4.dp) / 2)
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.outlineVariant, shape = RoundedCornerShape(2.dp))
            )
```

Replace with:

```kotlin
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 6.dp + (tickWidth - 4.dp) / 2)
                    .width(4.dp)
                    .fillMaxHeight()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.outlineVariant, shape = RoundedCornerShape(2.dp))
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .fillMaxWidth()
                        .height(with(density) { fillHeightPx.toDp() })
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    lerp(MaterialTheme.colorScheme.primary, Color.White, 0.35f),
                                    MaterialTheme.colorScheme.primary
                                )
                            ),
                            shape = RoundedCornerShape(2.dp)
                        )
                )
            }
```

- [ ] **Step 3: Brighten ticks that are at or before the focused row**

Find the `else` branch left by Task 2:

```kotlin
                                } else {
                                    Modifier.background(
                                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                        shape = RoundedCornerShape(4.dp)
                                    )
                                }
```

Replace with:

```kotlin
                                } else {
                                    Modifier.background(
                                        MaterialTheme.colorScheme.outlineVariant.copy(
                                            alpha = if (index <= focusIndex) 0.65f else 0.5f
                                        ),
                                        shape = RoundedCornerShape(4.dp)
                                    )
                                }
```

`index` here is the row's position in `entries` (top-to-bottom, same domain as `focusIndex`), so this is a plain ordering comparison — the list is already rendered top-to-bottom in page order, so "index at or before the focused row" and "vertically above or at the pill" are the same thing without needing a second real-position lookup per tick.

- [ ] **Step 4: Verify it compiles and looks right**

Run: `.\gradlew.bat assembleDebug`, install, open a PDF, drag the track.
Expected: the rail shows a gradient-filled segment from the top down to the pill's center, growing/shrinking live as you drag; ticks above the pill are visibly brighter than ticks below it.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/components/PdfLabelScrollbar.kt
git commit -m "feat: add progress-fill rail and tick brightness to scrollbar"
```

---

### Task 5: Tap ripple feedback

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/components/PdfLabelScrollbar.kt`

- [ ] **Step 1: Add a `RippleRing` data holder**

Near the top of the file, alongside the existing `ItemBounds`/`ScrollbarEntry` private types (after the `ScrollbarEntry` data class, around line 109), add:

```kotlin
/** One in-flight tap ripple on the track — [progress] animates 0f (just tapped) to 1f (faded
 * out), independent of the pill's own animation state so overlapping taps don't fight. */
private data class RippleRing(val id: Long, val centerYPx: Float, val progress: Animatable<Float>)
```

- [ ] **Step 2: Add ripple state to the composable**

In `PdfLabelScrollbar`, right after the existing `val thumbCache = remember { mutableStateMapOf<Int, Bitmap?>() }` line (line 155), add:

```kotlin
    val ripples = remember { mutableStateListOf<RippleRing>() }
    val rippleScope = rememberCoroutineScope()
    var nextRippleId by remember { mutableLongStateOf(0L) }
```

- [ ] **Step 3: Spawn a ripple on tap**

Find the tap gesture handler:

```kotlin
                .pointerInput(entries.size, displayMode) {
                    detectTapGestures { offset -> onPageSelected(entries[hitTest(offset.y)].page) }
                }
```

Replace with:

```kotlin
                .pointerInput(entries.size, displayMode) {
                    detectTapGestures { offset ->
                        onPageSelected(entries[hitTest(offset.y)].page)
                        if (!lowEnd.shadowsDisabled) {
                            val ring = RippleRing(nextRippleId++, offset.y, Animatable(0f))
                            ripples.add(ring)
                            rippleScope.launch {
                                ring.progress.animateTo(1f, animationSpec = tween(350))
                                ripples.remove(ring)
                            }
                        }
                    }
                }
```

- [ ] **Step 4: Draw the ripples**

Immediately after the rail `Box` block from Task 4 (still inside the inner track `Box`, before the `LazyColumn`), add:

```kotlin
            if (ripples.isNotEmpty()) {
                val ripplePrimary = MaterialTheme.colorScheme.primary
                val rippleCenterX = with(density) { (tickWidth / 2).toPx() }
                Canvas(modifier = Modifier.align(Alignment.TopEnd).fillMaxSize()) {
                    for (ring in ripples) {
                        val p = ring.progress.value
                        drawCircle(
                            color = ripplePrimary.copy(alpha = (1f - p) * 0.5f),
                            radius = with(density) { (8.dp + 24.dp * p).toPx() },
                            center = Offset(size.width - rippleCenterX, ring.centerYPx)
                        )
                    }
                }
            }
```

- [ ] **Step 5: Verify it compiles and behaves correctly**

Run: `.\gradlew.bat assembleDebug`, install, open a PDF, tap different spots on the track.
Expected: each tap flashes a small ripple that fades out over ~350ms, without blocking or delaying the pill's own jump-to-page.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/components/PdfLabelScrollbar.kt
git commit -m "feat: add tap ripple feedback to scrollbar track"
```

---

### Task 6: Carousel slide-in/out transition

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/components/PdfLabelScrollbar.kt:345-533`

- [ ] **Step 1: Replace the `if (isDragging)` gate with `AnimatedVisibility`**

Find:

```kotlin
        if (isDragging) {
```

Replace with:

```kotlin
        AnimatedVisibility(
            visible = isDragging,
            modifier = Modifier.align(Alignment.TopEnd),
            enter = slideInHorizontally(
                animationSpec = tween(200, easing = FastOutSlowInEasing)
            ) { fullWidth -> fullWidth } + fadeIn(tween(200)),
            exit = slideOutHorizontally(
                animationSpec = tween(200, easing = FastOutSlowInEasing)
            ) { fullWidth -> fullWidth } + fadeOut(tween(200))
        ) {
```

Everything between the old `if (isDragging) {` and its matching closing `}` (the whole carousel body — `fittedSlots`, `frostedTokens`, the `Column`, etc.) stays exactly as-is; only the opening condition changes to an `AnimatedVisibility` content lambda.

- [ ] **Step 2: Remove the now-redundant `align` on the inner `Column`**

Inside that block, find the `Column`'s modifier (originally):

```kotlin
            Column(
                modifier = Modifier
                    // Root Box is fillMaxWidth now (see the comment there) specifically so this
                    // ordinary TopEnd alignment works correctly without needing to escape a
                    // narrow parent — no oversized-child tricks left to get wrong.
                    .align(Alignment.TopEnd)
                    .offset(y = with(density) { carouselTopPx.toDp() })
                    .width(carouselWidth),
```

Replace with (drop `.align(Alignment.TopEnd)` — alignment now lives on `AnimatedVisibility`'s own modifier from Step 1):

```kotlin
            Column(
                modifier = Modifier
                    .offset(y = with(density) { carouselTopPx.toDp() })
                    .width(carouselWidth),
```

- [ ] **Step 3: Close the `AnimatedVisibility` call correctly**

The original block ended with two closing braces (one for the `Column`'s trailing content, one for `if (isDragging) { ... }`). Confirm the final closing brace of this section now closes the `AnimatedVisibility` content lambda — no other change needed, since `AnimatedVisibility { ... }` has the same brace shape as `if (...) { ... }`.

- [ ] **Step 4: Verify it compiles and behaves correctly**

Run: `.\gradlew.bat assembleDebug`, install, open a PDF, start and stop a drag on the track.
Expected: the carousel slides + fades in from behind the track when a drag starts, and slides + fades out (rather than popping) when the drag ends.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/components/PdfLabelScrollbar.kt
git commit -m "feat: animate scrollbar carousel entrance/exit with slide+fade"
```

---

### Task 7: Full manual verification pass

**Files:** none (verification only)

- [ ] **Step 1: Build and install a debug APK**

```bash
cd C:\Scripts\KKCSheetTracker
.\gradlew.bat assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

- [ ] **Step 2: Walk the checklist from the design spec, on-device**

Open a job PDF with enough pages that the track shows multiple ticks, and check each:

- [ ] Tap-to-jump: pill springs to the tapped row with a visible bounce; a ripple flashes and fades at the tap point.
- [ ] Drag: pill follows the finger with no lag or bounce while dragging; springs to rest the moment you lift your finger.
- [ ] Drag-release bounce is actually noticeable, not just technically present (the pill dips to 94% height while dragging so release has something to spring from — if it reads as too subtle/invisible, lower the multiplier in `draggingPillHeight` from `0.94f` toward `0.85f`–`0.90f`, don't retune the spring curve).
- [ ] Touch-down on the already-current row: pill snaps down to 94% height the instant you touch it (before any drag movement) — confirm this reads as an intentional "press-in, spring-out" feel rather than a glitchy jump-cut. If it looks wrong, that's the `isDragging -> draggingPillHeight` branch in the row height logic to revisit.
- [ ] While the release-bounce spring is playing, check whether sibling ticks visibly ripple/shift (the track's `LazyColumn` uses `Arrangement.SpaceBetween`, so one row's height animating reflows every visible row's gap, unlike the carousel's fixed-gap arrangement). If distracting, this needs a structural fix (e.g. switch to `Arrangement.spacedBy`), not a tuning tweak.
- [ ] Rail fill grows/shrinks live as you drag, and ticks above the pill are visibly brighter than ticks below it.
- [ ] Carousel slides + fades in from behind the track on drag start, slides + fades out on drag end (not an instant pop either direction).
- [ ] Repeat the above in both light and dark theme (Settings → theme toggle, or system dark mode).
- [ ] Repeat with low-end mode active (however `LocalLowEndMode` is toggled for testing in this codebase — check existing low-end test paths in the carousel code for the mechanism) — shadow and ripple should be absent, no crash, carousel transition still plays.
- [ ] Long document (BUCKETED display mode): confirm the fill/brightness logic still reads sensibly when multiple pages are bucketed into one entry.

- [ ] **Step 3: Fix any issues found, committing each fix separately**

If any checklist item fails, fix it in the relevant file, re-run Step 1, and commit with a `fix:` message describing exactly what was wrong.
