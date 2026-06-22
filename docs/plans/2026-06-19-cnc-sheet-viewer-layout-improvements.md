# CNC Sheet Viewer Layout Improvements Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Make the CNC sheet viewer's split layout automatically scale to fit the sheet image preview so it touches all 4 edges (sliding with an animation), and remove the redundant dynamic reference sheet chips in the screen header.

**Architecture:** We will modify `VerticalSplitLayout` to accept an optional `aspectRatio` parameter and animate height adjustments using `Animatable` (falling back to a default weight or user override). We will calculate the aspect ratio from the active bitmap in `SheetViewerScreen` and pass it down, while removing the dynamic `FilterChip` row from the header.

**Tech Stack:** Kotlin, Jetpack Compose UI

---

### Task 1: Update VerticalSplitLayout signature and properties

**Files:**
- Modify: [VerticalSplitLayout.kt](file:///c:/Scripts/KKCSheetTracker/app/src/main/java/com/kkc/sheettracker/ui/components/VerticalSplitLayout.kt)

**Step 1: Write the failing test / Verify signature changes compilation**

Modify the parameters of `VerticalSplitLayout` in `VerticalSplitLayout.kt` to accept `aspectRatio: Float? = null`.
Run the build to see if compile fails due to unresolved dependencies or usage elsewhere (such as in `AdaptiveSplitLayout.kt`).

Run: `.\gradlew.bat compileDebugKotlin`
Expected: Compile failure if caller parameters don't match, or success if defaults work.

**Step 2: Update signatures to maintain compatibility**

Ensure `aspectRatio: Float? = null` is defined as:
```kotlin
@Composable
fun VerticalSplitLayout(
    modifier: Modifier = Modifier,
    initialTopWeight: Float = DEFAULT_TOP_WEIGHT,
    aspectRatio: Float? = null,
    fullscreen: SplitFullscreen = SplitFullscreen.NONE,
    topContent: @Composable (Modifier) -> Unit,
    bottomContent: @Composable (Modifier) -> Unit
)
```

**Step 3: Verify clean compile**

Run: `.\gradlew.bat compileDebugKotlin`
Expected: PASS

**Step 4: Commit**
```bash
git add app/src/main/java/com/kkc/sheettracker/ui/components/VerticalSplitLayout.kt
git commit -m "refactor: add aspectRatio parameter to VerticalSplitLayout"
```

---

### Task 2: Implement dynamic aspect-ratio target height calculation and state transitions in VerticalSplitLayout

**Files:**
- Modify: [VerticalSplitLayout.kt](file:///c:/Scripts/KKCSheetTracker/app/src/main/java/com/kkc/sheettracker/ui/components/VerticalSplitLayout.kt)

**Step 1: Implement Animatable state and LaunchedEffect for targetHeightPx**

Replace the raw float `topHeightPx` with an `Animatable` and implement calculations to animate to `targetHeightPx`.

```kotlin
    val coroutineScope = rememberCoroutineScope()
    val topHeightAnim = remember { Animatable(-1f) }

    val targetHeightPx = remember(aspectRatio, totalHeight) {
        if (aspectRatio != null && aspectRatio > 0f && totalHeight.height > 0) {
            val width = totalHeight.width.toFloat()
            clampedTopPx(width / aspectRatio)
        } else {
            null
        }
    }

    var lastTargetHeightPx by remember { mutableFloatStateOf(-1f) }

    LaunchedEffect(targetHeightPx) {
        if (targetHeightPx != null && targetHeightPx != lastTargetHeightPx) {
            lastTargetHeightPx = targetHeightPx
            if (topHeightAnim.value < 0f) {
                topHeightAnim.snapTo(targetHeightPx)
            } else {
                topHeightAnim.animateTo(
                    targetValue = targetHeightPx,
                    animationSpec = tween(durationMillis = 350)
                )
            }
        }
    }
```

**Step 2: Update onSizeChanged, drag gestures, and layout measurements to use topHeightAnim**

Update the measurements block:
```kotlin
    Column(
        modifier = modifier.onSizeChanged {
            totalHeight = it
            if (topHeightAnim.value < 0f) {
                val initialHeight = it.height * initialTopWeight
                coroutineScope.launch {
                    topHeightAnim.snapTo(clampedTopPx(initialHeight))
                }
            } else {
                coroutineScope.launch {
                    topHeightAnim.snapTo(clampedTopPx(topHeightAnim.value))
                }
            }
        }
    )
```

Update drag gestures:
```kotlin
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                val nextHeight = clampedTopPx(topHeightAnim.value + dragAmount.y)
                                coroutineScope.launch {
                                    topHeightAnim.snapTo(nextHeight)
                                }
                            }
```

Update double-tap gesture:
```kotlin
                            detectTapGestures(
                                onDoubleTap = {
                                    if (totalHeight.height > 0) {
                                        val resetTarget = targetHeightPx ?: clampedTopPx(totalHeight.height * DEFAULT_TOP_WEIGHT)
                                        coroutineScope.launch {
                                            topHeightAnim.animateTo(resetTarget, tween(350))
                                        }
                                    }
                                }
                            )
```

Update DP height extraction:
```kotlin
                val topHeightDp = with(density) { topHeightAnim.value.coerceAtLeast(minTopPx).toDp() }
                topContent(Modifier.fillMaxWidth().height(topHeightDp))
```

**Step 3: Compile and verify**

Run: `.\gradlew.bat compileDebugKotlin`
Expected: PASS

**Step 4: Commit**
```bash
git add app/src/main/java/com/kkc/sheettracker/ui/components/VerticalSplitLayout.kt
git commit -m "feat: animate aspect ratio transitions and update drag gestures in VerticalSplitLayout"
```

---

### Task 3: Calculate aspect ratio in SheetViewerScreen and pass it to VerticalSplitLayout

**Files:**
- Modify: [SheetViewerScreen.kt](file:///c:/Scripts/KKCSheetTracker/app/src/main/java/com/kkc/sheettracker/ui/viewer/SheetViewerScreen.kt)

**Step 1: Calculate active bitmap aspect ratio**

Insert calculation for aspect ratio right before `VerticalSplitLayout` call (around line 1370):
```kotlin
            val bitmapAspectRatio = remember(bitmap) {
                bitmap?.let { it.width.toFloat() / it.height.toFloat() }
            }
```

**Step 2: Pass aspect ratio to layout**

Modify the invocation of `VerticalSplitLayout`:
```kotlin
            VerticalSplitLayout(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                aspectRatio = bitmapAspectRatio,
                topContent = { topModifier ->
```

**Step 3: Compile and verify**

Run: `.\gradlew.bat compileDebugKotlin`
Expected: PASS

**Step 4: Commit**
```bash
git add app/src/main/java/com/kkc/sheettracker/ui/viewer/SheetViewerScreen.kt
git commit -m "feat: pass bitmap aspect ratio from SheetViewerScreen to VerticalSplitLayout"
```

---

### Task 4: Remove redundant FilterChip row in SheetViewerScreen

**Files:**
- Modify: [SheetViewerScreen.kt](file:///c:/Scripts/KKCSheetTracker/app/src/main/java/com/kkc/sheettracker/ui/viewer/SheetViewerScreen.kt)

**Step 1: Delete the Row layout for FilterChips**

Locate the `Row` block at lines 1328–1362:
```kotlin
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ...
            }
```
Delete this block entirely.

**Step 2: Verify clean compile**

Run: `.\gradlew.bat compileDebugKotlin`
Expected: PASS

**Step 3: Commit**
```bash
git add app/src/main/java/com/kkc/sheettracker/ui/viewer/SheetViewerScreen.kt
git commit -m "cleanup: remove redundant dynamic reference document chips row from viewer screen header"
```

---

### Verification and Delivery

**Step 1: Build the final debug APK**

Run: `.\gradlew.bat assembleDebug`
Expected: Successful build resulting in `app-debug.apk`.

**Step 2: Commit and push changes**

Complete git staging and list final commit stats.
