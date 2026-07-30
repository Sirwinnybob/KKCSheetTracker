# Barcode Scanning Reliability Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eliminate false positive barcode detections, improve focus speed, and add clear visual scan-area boundaries.

**Architecture:** Three independent changes to `SupplyScannerOverlay.kt` (ML Kit config + multi-frame verification + torch) and `ScannerReticle.kt` (scrim/border/text). No new files, no architectural changes, no API additions.

**Tech Stack:** CameraX 1.4.2, ML Kit Barcode Scanning 17.3.0, Compose Canvas

## Global Constraints

- Only 2 files modified: `app/src/main/java/com/kkc/sheettracker/ui/supply/SupplyScannerOverlay.kt` and `app/src/main/java/com/kkc/sheettracker/ui/supply/ScannerReticle.kt`
- No new dependencies — CameraX + ML Kit already present
- Barcode format whitelist: `FORMAT_EAN_13`, `FORMAT_UPC_A`, `FORMAT_CODE_128`, `FORMAT_CODE_39`, `FORMAT_ITF`, `FORMAT_QR_CODE`
- Multi-frame verification threshold: 3 consecutive frames with same barcode value
- No changes to `SupplyBarcodeStore`, `SupplyBarcodeResultSheets`, or any other file
- Build verification: `.\gradlew.bat assembleDebug`

---

### Task 1: Format Restriction + Multi-frame Verification

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/supply/SupplyScannerOverlay.kt`

**Interfaces:**
- Consumes: existing `lockedBarcodeValue`, `detectedBox`, `barcodeStore` — unchanged interfaces
- Produces: new `consecutiveHits` state var, new `candidateBarcodeValue` state var, modified lock gate logic

- [ ] **Step 1: Add BarcodeScannerOptions import + format whitelist**

Add import at top of file:
```kotlin
import com.google.mlkit.vision.barcode.Barcode
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
```

Replace line 113:
```kotlin
// old:
val barcodeScanner = BarcodeScanning.getClient()
// new:
val options = BarcodeScannerOptions.Builder()
    .setBarcodeFormats(
        Barcode.FORMAT_EAN_13,
        Barcode.FORMAT_UPC_A,
        Barcode.FORMAT_CODE_128,
        Barcode.FORMAT_CODE_39,
        Barcode.FORMAT_ITF,
        Barcode.FORMAT_QR_CODE
    )
    .build()
val barcodeScanner = BarcodeScanning.getClient(options)
```

- [ ] **Step 2: Add multi-frame verification state**

After `var lockedBarcodeValue by remember { mutableStateOf<String?>(null) }` (line 63), add:
```kotlin
var candidateBarcodeValue by remember { mutableStateOf<String?>(null) }
var consecutiveHits by remember { mutableIntStateOf(0) }
```

- [ ] **Step 3: Reset candidate state when unlocked**

In the `LaunchedEffect(isModalActive)` block (line 70-78), add reset for the new vars:
```kotlin
LaunchedEffect(isModalActive) {
    if (!isModalActive && lockedBarcodeValue != null) {
        lockedBarcodeValue = null
        candidateBarcodeValue = null
        consecutiveHits = 0
        detectedBox = null
        isCooldownActive = true
        delay(2000)
        isCooldownActive = false
    }
}
```

- [ ] **Step 4: Replace single-frame lock with multi-frame verification**

In the analyzer's unlocked branch (lines 153-205), replace the lock-on-first-hit logic:

```kotlin
} else {
    val filteredBarcodes = barcodes.filter { barcode ->
        val rect = barcode.boundingBox ?: return@filter false
        if (parentSize.width <= 0f || parentSize.height <= 0f) return@filter false

        val mapped = mapBoundingBox(
            rect = rect,
            imageWidth = imageProxy.width,
            imageHeight = imageProxy.height,
            rotation = rotation,
            parentWidth = parentSize.width,
            parentHeight = parentSize.height
        )

        val reticlePx = with(density) { 260.dp.toPx() }
        val paddingPx = with(density) { 36.dp.toPx() }
        val windowSize = reticlePx + paddingPx * 2f

        val windowLeft = (parentSize.width - windowSize) / 2f
        val windowRight = (parentSize.width + windowSize) / 2f
        val windowTop = (parentSize.height - windowSize) / 2f
        val windowBottom = (parentSize.height + windowSize) / 2f

        val cx = mapped.centerX()
        val cy = mapped.centerY()
        cx >= windowLeft && cx <= windowRight && cy >= windowTop && cy <= windowBottom
    }

    val barcode = filteredBarcodes.firstOrNull()
    val raw = barcode?.rawValue?.takeIf { it.isNotBlank() }

    if (raw != null && raw == candidateBarcodeValue) {
        consecutiveHits++
        if (consecutiveHits >= 3) {
            lockedBarcodeValue = raw

            val rect = barcode.boundingBox
            if (rect != null && parentSize.width > 0 && parentSize.height > 0) {
                detectedBox = mapBoundingBox(
                    rect = rect,
                    imageWidth = imageProxy.width,
                    imageHeight = imageProxy.height,
                    rotation = rotation,
                    parentWidth = parentSize.width,
                    parentHeight = parentSize.height
                )
            }

            scope.launch {
                delay(450)
                val item = barcodeStore.resolveItem(raw)
                if (item != null) onKnownBarcode(item, raw)
                else onUnknownBarcode(raw)
            }
        }
    } else {
        candidateBarcodeValue = raw
        consecutiveHits = if (raw != null) 1 else 0
    }
}
```

- [ ] **Step 5: Build verification**

Run: `.\gradlew.bat assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/supply/SupplyScannerOverlay.kt
git commit -m "fix(supply): restrict barcode formats + add 3-frame verification"
```

---

### Task 2: Torch Toggle

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/supply/SupplyScannerOverlay.kt`

**Interfaces:**
- Consumes: `CameraX.Camera` returned from `provider.bindToLifecycle()`
- Produces: torch icon button in top bar Row, `isTorchOn` state

- [ ] **Step 1: Add import for Camera**

Add to existing `import androidx.camera.core.*` (already imported — `Camera` is in that wildcard).

- [ ] **Step 2: Add camera reference state + torch state**

After `var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }` (line 65), add:
```kotlin
var camera by remember { mutableStateOf<Camera?>(null) }
var isTorchOn by remember { mutableStateOf(true) }
```

- [ ] **Step 3: Capture camera reference on bind**

In the `bindToLifecycle` call (line 214), capture the return value. Replace:
```kotlin
provider.bindToLifecycle(
    lifecycleOwner,
    CameraSelector.DEFAULT_BACK_CAMERA,
    preview, imageAnalysis
)
```
with:
```kotlin
camera = provider.bindToLifecycle(
    lifecycleOwner,
    CameraSelector.DEFAULT_BACK_CAMERA,
    preview, imageAnalysis
)
```

- [ ] **Step 4: Add LaunchedEffect to auto-enable torch when camera binds**

After the `DisposableEffect(Unit)` block (line 94-96), add:
```kotlin
LaunchedEffect(camera) {
    if (camera != null && isTorchOn) {
        camera?.cameraControl?.enableTorch(true)
    }
}
```

- [ ] **Step 5: Add torch toggle button to top bar**

In the top Row (line 236-255), after the close button, add a torch toggle button before it. Replace the Row content block (lines 236-255) with:

```kotlin
Row(
    modifier = Modifier
        .fillMaxWidth()
        .statusBarsPadding()
        .padding(horizontal = 8.dp, vertical = 4.dp),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically
) {
    Text(
        "Scan a barcode or QR code",
        color = Color.White,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier
            .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp)
    )
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        IconButton(onClick = {
            isTorchOn = !isTorchOn
            camera?.cameraControl?.enableTorch(isTorchOn)
        }) {
            Icon(
                if (isTorchOn) Icons.Filled.FlashOn else Icons.Filled.FlashOff,
                "Toggle flashlight",
                tint = if (isTorchOn) Color.Yellow else Color.White
            )
        }
        IconButton(onClick = onDismiss) {
            Icon(Icons.Filled.Close, "Close scanner", tint = Color.White)
        }
    }
}
```

- [ ] **Step 6: Reset torch on dismiss**

In the `LaunchedEffect(isModalActive)` block, add `isTorchOn = false` after `lockedBarcodeValue = null`:

```kotlin
LaunchedEffect(isModalActive) {
    if (!isModalActive && lockedBarcodeValue != null) {
        lockedBarcodeValue = null
        candidateBarcodeValue = null
        consecutiveHits = 0
        detectedBox = null
        isTorchOn = false
        camera?.cameraControl?.enableTorch(false)
        isCooldownActive = true
        delay(2000)
        isCooldownActive = false
    }
}
```

- [ ] **Step 7: Build verification**

Run: `.\gradlew.bat assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/supply/SupplyScannerOverlay.kt
git commit -m "feat(supply): add torch toggle to barcode scanner"
```

---

### Task 3: Enhanced Scrim + Border + Instructional Text

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/supply/ScannerReticle.kt`
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/supply/SupplyScannerOverlay.kt`

**Interfaces:**
- Consumes: existing `detectedBox` parameter — unchanged
- Produces: modified scrim, new border line, new composable text

- [ ] **Step 1: Increase scrim alpha in ScannerReticle.kt**

In `ScannerReticle.kt` line 75, change:
```kotlin
val scrimAlpha = if (detectedBox != null) 0.70f else 0.55f
```
to:
```kotlin
val scrimAlpha = if (detectedBox != null) 0.80f else 0.75f
```

- [ ] **Step 2: Add solid white border line around reticle**

After the scrim rectangles and before `drawCornerBrackets` (after line 81), add:
```kotlin
// Solid border outline
drawRect(
    color = Color.White.copy(alpha = 0.6f),
    topLeft = Offset(animLeft, animTop),
    size = Size(animRight - animLeft, animBottom - animTop),
    style = Stroke(width = 2.dp.toPx())
)
```

Add import at top (if not already):
```kotlin
import androidx.compose.ui.graphics.drawscope.Stroke
```

- [ ] **Step 3: Convert ScannerReticle to Box layout + add instructional text**

`ScannerReticle` currently returns a `Canvas`. Replace the return with a `Box` containing the Canvas + text below.

Replace the current function body (from `Canvas(modifier = ...)` through the closing `}` of `drawCornerBrackets`) with:

```kotlin
Box(modifier = modifier) {
    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned {
                viewWidth = it.size.width.toFloat()
                viewHeight = it.size.height.toFloat()
            }
    ) {
        if (viewWidth <= 0f || viewHeight <= 0f) return@Canvas

        val scrimAlpha = if (detectedBox != null) 0.80f else 0.75f

        drawRect(color = Color.Black.copy(alpha = scrimAlpha), topLeft = Offset.Zero, size = Size(viewWidth, animTop))
        drawRect(color = Color.Black.copy(alpha = scrimAlpha), topLeft = Offset(0f, animBottom), size = Size(viewWidth, viewHeight - animBottom))
        drawRect(color = Color.Black.copy(alpha = scrimAlpha), topLeft = Offset(0f, animTop), size = Size(animLeft, animBottom - animTop))
        drawRect(color = Color.Black.copy(alpha = scrimAlpha), topLeft = Offset(animRight, animTop), size = Size(viewWidth - animRight, animBottom - animTop))

        drawRect(
            color = Color.White.copy(alpha = 0.6f),
            topLeft = Offset(animLeft, animTop),
            size = Size(animRight - animLeft, animBottom - animTop),
            style = Stroke(width = 2.dp.toPx())
        )

        val cLen = cornerLength.toPx()
        val sw = strokeWidth.toPx()
        val finalColor = if (detectedBox != null) animColor else animColor.copy(alpha = alpha)

        drawCornerBrackets(animLeft, animTop, animRight, animBottom, cLen, sw, finalColor)
    }

    Text(
        text = "Aim barcode inside the box",
        color = Color.White.copy(alpha = 0.7f),
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = 48.dp)
    )
}
```

Add import at top:
```kotlin
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
```

- [ ] **Step 4: Build verification**

Run: `.\gradlew.bat assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/supply/ScannerReticle.kt app/src/main/java/com/kkc/sheettracker/ui/supply/SupplyScannerOverlay.kt
git commit -m "fix(supply): enhance scrim, add border, add instructional text"
```
