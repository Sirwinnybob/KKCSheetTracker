# Continuous PDF Pinch and Chrome Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make vertical continuous-PDF pinch zoom follow the finger centroid while supporting simultaneous two-finger panning, and render the document beneath continuous-mode chrome.

**Architecture:** Keep `ContinuousReferencePdfPane` as the single transform-gesture owner. Convert every transform frame's existing `computeZoomPan` main-axis result into a lazy-list delta, including multi-touch frames. Render continuous PDF as the full-size bottom layer, then place document controls, scrollbar, and the screen top app bar over it.

**Tech Stack:** Kotlin, Jetpack Compose, Compose LazyColumn/LazyRow, JUnit 4, Gradle Android plugin.

## Global Constraints

- Change continuous PDF mode only; paged mode retains its current top-app-bar inset.
- Preserve edge overscroll, cross-axis pan bounds, markup gesture exclusion, page tracking, and no multi-touch fling.
- Add no dependencies or test harnesses.
- Reuse `computeZoomPan`; do not replace PDF virtualization or the lazy-list architecture.

---

### Task 1: Forward multi-touch main-axis compensation to the lazy list

**Files:**

- Modify: `app/src/main/java/com/kkc/sheettracker/ui/components/ContinuousReferencePdfPane.kt:140-149,989-1006`
- Test: `app/src/test/java/com/kkc/sheettracker/ui/components/ContinuousReferencePdfPaneTest.kt`

**Interfaces:**

- Consumes: `ZoomPanResult.panY` or `ZoomPanResult.panX` and `sharedZoom`.
- Produces: `continuousMainAxisScrollDelta(panDelta: Float, zoom: Float, viewportExtent: Int): Float?`, a forward-positive lazy-list delta in list-space pixels.

- [ ] **Step 1: Write the failing focal-point test**

Add this test, importing `androidx.compose.ui.geometry.Offset` if needed:

```kotlin
@Test
fun continuousMainAxisScrollDelta_verticalOffCenterZoomCompensatesLazyList() {
    val transform = computeZoomPan(
        zoom = 1f, panX = 0f, panY = 0f,
        zoomChange = 2f, panChange = Offset.Zero,
        centroid = Offset(500f, 250f),
        viewWidth = 1000, viewHeight = 1000,
        minZoom = 1f, maxZoom = 20f
    )

    assertEquals(250f, transform.panY, 0.001f)
    assertEquals(
        -125f,
        continuousMainAxisScrollDelta(
            panDelta = transform.panY,
            zoom = transform.zoom,
            viewportExtent = 1000
        )!!,
        0.001f
    )
}
```

- [ ] **Step 2: Write the failing two-finger-pan test**

Add this independent regression:

```kotlin
@Test
fun continuousMainAxisScrollDelta_twoFingerPanIsNotDropped() {
    assertEquals(
        -30f,
        continuousMainAxisScrollDelta(
            panDelta = 60f,
            zoom = 2f,
            viewportExtent = 1000
        )!!,
        0.001f
    )
}
```

- [ ] **Step 3: Verify the tests fail for the changed contract**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.kkc.sheettracker.ui.components.ContinuousReferencePdfPaneTest"
```

Expected: compile failure because production still requires `isMultiTouch`; this proves the tests demand the new contract.

- [ ] **Step 4: Implement the minimal main-axis delta change**

Replace the helper with:

```kotlin
internal fun continuousMainAxisScrollDelta(
    panDelta: Float,
    zoom: Float,
    viewportExtent: Int
): Float? = if (viewportExtent <= 0 || panDelta == 0f) {
    null
} else {
    -panDelta / zoom
}
```

Remove `isMultiTouch = wasMultiTouch` from both orientation call sites. Continue passing `next.panY` for vertical and `next.panX` for horizontal. Retain `wasMultiTouch` for tap classification and the existing zero-fling velocities.

- [ ] **Step 5: Verify focused tests pass**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.kkc.sheettracker.ui.components.ContinuousReferencePdfPaneTest"
```

Expected: the new tests and the existing continuous-pane tests pass.

- [ ] **Step 6: Commit the gesture fix**

Run: `git add -- app/src/main/java/com/kkc/sheettracker/ui/components/ContinuousReferencePdfPane.kt app/src/test/java/com/kkc/sheettracker/ui/components/ContinuousReferencePdfPaneTest.kt; git commit -m "fix: preserve continuous pinch focus"`

### Task 2: Overlay continuous PDF chrome on a stable full-size document layer

**Files:**

- Modify: `app/src/main/java/com/kkc/sheettracker/ui/viewer/ReferencePdfViewerScreen.kt:176-185`
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/viewer/UnifiedReferenceViewer.kt:799-887`

**Interfaces:**

- Consumes: `continuousScrollEnabled`, `Scaffold` content `padding`, document-control state, and `PdfLabelScrollbar`.
- Produces: a continuous-mode pane that fills the screen, then paints continuous chrome above it; paged mode continues to receive the top-bar inset.

- [ ] **Step 1: Keep the scaffold inset in paged mode only**

Change the modifier passed from `ReferencePdfViewerScreen` to:

```kotlin
modifier = Modifier
    .fillMaxSize()
    .then(if (continuousScrollEnabled) Modifier else Modifier.padding(padding)),
```

This makes the transparent/fading `TopAppBar` overlay continuous PDF content and preserves the current paged layout.

- [ ] **Step 2: Replace the continuous `Column` with an overlay `Box`**

In `UnifiedReferenceViewer`, use one full-size parent:

```kotlin
Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
    ContinuousReferencePdfPane(
        modifier = Modifier
            .fillMaxSize()
            .let { source -> if (hazeState != null) source.hazeSource(hazeState) else source },
        // retain every existing argument
    )

    if (showHeaderRow || showNavigationButtons) {
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // retain existing document controls, page label, and sheet-list button
        }
    }

    PdfLabelScrollbar(
        modifier = Modifier.align(Alignment.CenterEnd),
        // retain every existing argument
    )
}
```

Remove only the continuous pane's `padding(end = PDF_LABEL_SCROLLBAR_IDLE_WIDTH)`. Leave the haze source on `ContinuousReferencePdfPane`, because `PdfLabelScrollbar` remains its overlay consumer.

- [ ] **Step 3: Run compilation and focused tests**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.kkc.sheettracker.ui.components.ContinuousReferencePdfPaneTest"
.\gradlew.bat assembleDebug
```

Expected: both commands exit successfully.

- [ ] **Step 4: Perform the device check without uninstalling**

After a successful build, install with:

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

Verify on a vertical continuous reference PDF:

1. Top/bottom off-center pinches hold the touched source position beneath the centroid.
2. Two fingers can pan vertically while pinching, with no release jump.
3. Cross-axis panning remains bounded while zoomed.
4. Showing/hiding the top app bar does not resize the PDF; PDF pixels remain underneath it.
5. The right scrollbar and its expanded panel have PDF pixels beneath them, and page selection still works.
6. Paged mode retains its original app-bar inset and controls.

- [ ] **Step 5: Commit the chrome overlay change**

Run: `git add -- app/src/main/java/com/kkc/sheettracker/ui/viewer/ReferencePdfViewerScreen.kt app/src/main/java/com/kkc/sheettracker/ui/viewer/UnifiedReferenceViewer.kt; git commit -m "fix: overlay continuous PDF chrome"`

## Final Verification

- [ ] Run `./gradlew.bat testDebugUnitTest`.
- [ ] Run `./gradlew.bat assembleDebug`.
- [ ] Inspect `git diff --check` and `git status --short` before reporting results.
