# Barcode Scanner Hardening Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans.

**Goal:** Fix critical reliability issues, imageProxy leaks, UI state bugs, and lock rotation during scan.

**Architecture:** Changes to `SupplyScannerOverlay.kt` only (except rotation lock which touches the composable). Try/finally guarding, guard clauses, null checks.

**Tech Stack:** CameraX 1.4.2, ML Kit 17.3.0, Compose

## Global Constraints

- Only modify `app/src/main/java/com/kkc/sheettracker/ui/supply/SupplyScannerOverlay.kt`
- Rotation lock uses `DisposableEffect` + `Activity.setRequestedOrientation`
- Build verification: `.\gradlew.bat assembleRelease`

---

### Task 1: Guard imageProxy lifecycle with try/finally

**Files:**
- Modify: `SupplyScannerOverlay.kt`

**Problem:** `InputImage.fromMediaImage()` throws on unsupported formats. `barcodeScanner.process()` may throw synchronously. Either throw skips `addOnCompleteListener { imageProxy.close() }` — frame leaks indefinitely, CameraX blocks delivery after threshold, scanner freezes.

**Fix:** Wrap the entire analyzer body in `try { ... } finally { imageProxy.close() }`. Remove the `addOnCompleteListener { imageProxy.close() }` since close is now in finally.

- [ ] **Step 1:** Wrap analyzer body in try/finally

Replace:
```kotlin
imageAnalysis.setAnalyzer(ContextCompat.getMainExecutor(ctx)) { imageProxy ->
    if (isCooldownActive) {
        imageProxy.close()
        return@setAnalyzer
    }
    val mediaImage = imageProxy.image
        ?: run {
            imageProxy.close()
            return@setAnalyzer
        }
    val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
    barcodeScanner.process(image)
        .addOnSuccessListener { barcodes -> ... }
        .addOnFailureListener { Log.w(...) }
        .addOnCompleteListener { imageProxy.close() }
}
```

with:
```kotlin
imageAnalysis.setAnalyzer(ContextCompat.getMainExecutor(ctx)) { imageProxy ->
    try {
        if (isCooldownActive) return@setAnalyzer
        val mediaImage = imageProxy.image ?: return@setAnalyzer
        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        barcodeScanner.process(image)
            .addOnSuccessListener { barcodes -> ... }
            .addOnFailureListener { Log.w(SCANNER_TAG, "Scan failed", it) }
    } catch (e: Exception) {
        Log.w(SCANNER_TAG, "Analyzer error", e)
    } finally {
        imageProxy.close()
    }
}
```

- [ ] **Step 2:** Build + commit

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/supply/SupplyScannerOverlay.kt
git commit -m "fix(supply): guard imageProxy lifecycle with try/finally"
```

---

### Task 2: Guard mapBoundingBox against zero dimensions

**Files:**
- Modify: `SupplyScannerOverlay.kt`

**Problem:** `mapBoundingBox` uses `imageProxy.width / height` in division. If either is 0 → `Infinity` scale → `Int.MAX_VALUE` coords → negative `Size` in reticle → potential GPU crash.

**Fix:** Skip coordinate mapping when image dimensions are 0.

- [ ] **Step 1:** Add zero-dimension guard at top of mapBoundingBox

```kotlin
private fun mapBoundingBox(
    rect: android.graphics.Rect,
    imageWidth: Int,
    imageHeight: Int,
    rotation: Int,
    parentWidth: Float,
    parentHeight: Float
): android.graphics.Rect {
    if (imageWidth <= 0 || imageHeight <= 0) return rect
    val isRotated = rotation == 90 || rotation == 270
    ...
}
```

Also add guard at the call site in the unlocked branch (line ~166) where `imageProxy.width`/`height` are used for filtering:

```kotlin
if (imageProxy.width <= 0 || imageProxy.height <= 0) {
    imageProxy.close()
    return@setAnalyzer
}
```

Add this early-exit right after the `if (isCooldownActive)` check.

- [ ] **Step 2:** Build + commit

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/supply/SupplyScannerOverlay.kt
git commit -m "fix(supply): guard mapBoundingBox against zero dimensions"
```

---

### Task 3: Clear detectedBox on candidate timeout

**Files:**
- Modify: `SupplyScannerOverlay.kt`

**Problem:** When `candidateFrameMisses >= 10` resets the candidate, `detectedBox` still holds the last tracked position. Reticle shows green box but no barcode visible — false positive UX.

**Fix:** Set `detectedBox = null` in the miss timeout block.

- [ ] **Step 1:** Add `detectedBox = null` in the timeout block

Find the `if (candidateFrameMisses >= 10)` block and add:

```kotlin
} else if (candidateBarcodeValue != null && raw == null) {
    candidateFrameMisses++
    if (candidateFrameMisses >= 10) {
        candidateBarcodeValue = null
        consecutiveHits = 0
        candidateFrameMisses = 0
        detectedBox = null
    }
}
```

- [ ] **Step 2:** Build + commit

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/supply/SupplyScannerOverlay.kt
git commit -m "fix(supply): clear detectedBox on candidate timeout"
```

---

### Task 4: Guard post-lock callbacks against stale dispatch

**Files:**
- Modify: `SupplyScannerOverlay.kt`

**Problem:** `scope.launch { delay(450); resolveItem(raw); callback(...) }` — the 450ms delay creates a window where the overlay can be dismissed (scanMode set to Idle, lockedBarcodeValue reset) before the callback fires. The stale callback sets `knownBarcodeResult`/`itemScanResult` in the parent, causing a result sheet to appear on next scanner open.

**Fix:** After the delay, verify `lockedBarcodeValue == raw` before calling callbacks. If user dismissed, lockedBarcodeValue is null — skip.

- [ ] **Step 1:** Add stale-guard before callbacks

```kotlin
scope.launch {
    delay(450)
    if (lockedBarcodeValue != raw) return@launch
    val item = barcodeStore.resolveItem(raw)
    if (item != null) onKnownBarcode(item, raw)
    else onUnknownBarcode(raw)
}
```

- [ ] **Step 2:** Build + commit

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/supply/SupplyScannerOverlay.kt
git commit -m "fix(supply): guard callbacks against stale dispatch"
```

---

### Task 5: Lock rotation while scanner is active

**Files:**
- Modify: `SupplyScannerOverlay.kt`

**Problem:** Rotation during scan causes Activity re-creation → camera unbind → jarring UX. Need to lock sensor orientation when scanner overlays.

**Fix:** Use `DisposableEffect` to set `Activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LOCKED)` on enter, restore `SCREEN_ORIENTATION_UNSPECIFIED` on exit.

- [ ] **Step 1:** Add imports

```kotlin
import android.content.pm.ActivityInfo
import android.app.Activity
```

- [ ] **Step 2:** Add DisposableEffect for rotation lock

After the existing `DisposableEffect(Unit) { onDispose { cameraProvider?.unbindAll() } }` block, add:

```kotlin
DisposableEffect(isModalActive) {
    val activity = context as? Activity
    if (activity != null && isModalActive) {
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
    }
    onDispose {
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }
}
```

Note: `isModalActive` is `true` when scan result sheet is showing, `false` when scanning. Use `Unit` key instead or add a separate `isScanning` state. Actually, simpler: make rotation lock always-on while the composable is alive by keying on `Unit`:

```kotlin
DisposableEffect(Unit) {
    val activity = context as? Activity
    activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
    onDispose {
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }
}
```

The overlay composable only exists while scanning (rendered conditionally by parent), so its entire lifecycle = scanning session. On dismiss, the composable leaves tree → rotation unlocks.

- [ ] **Step 3:** Build + commit

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/supply/SupplyScannerOverlay.kt
git commit -m "feat(supply): lock rotation while scanner active"
```
