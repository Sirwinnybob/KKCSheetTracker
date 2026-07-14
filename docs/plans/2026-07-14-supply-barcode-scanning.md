# Supply Tab — Barcode & QR Scanning Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add barcode/QR scanning to the Supply tab so workers can scan product barcodes to update stock status instantly, and link/manage barcodes per item, with QR label printing in Hours Tracker.

**Architecture:** Overlay composable (Approach A) — `SupplyBarcodeStore` owns barcode index state and scan session state; `SupplyScannerOverlay` renders CameraX + ML Kit on top of the existing supply screen inside a `Box`. No new nav destinations. `barcodes.json` is the primary lookup index; each item JSON carries a redundant `barcodes` mirror field.

**Tech Stack:** CameraX (`camera-camera2`, `camera-lifecycle`, `camera-view`), ML Kit `barcode-scanning`, Kotlin Coroutines/StateFlow, Jetpack Compose `ModalBottomSheet`, Python `qrcode` lib + pdfme for label PDFs.

> **Note:** At implementation time, use **Context7 MCP** to verify exact latest-stable versions of CameraX and ML Kit Barcode before adding dependencies.

---

## Repo roots

- **Android app:** `c:\Scripts\KKCSheetTracker\`
- **Hours Tracker:** `c:\Scripts\Hours Tracker\`

---

## Phase 1 — Data Layer (Android)

### Task 1: Add CameraX + ML Kit Barcode dependencies

**Files:**
- Modify: `app/build.gradle.kts` (around line 84 in the `dependencies` block)

**Step 1: Verify latest versions via Context7 MCP**

Use Context7 to look up current stable versions for:
- `com.google.mlkit:barcode-scanning`
- `androidx.camera:camera-camera2`
- `androidx.camera:camera-lifecycle`
- `androidx.camera:camera-view`

**Step 2: Add dependencies**

In the `dependencies { }` block, after line 104 (`implementation("com.google.mlkit:text-recognition:16.0.1")`):

```kotlin
implementation("com.google.mlkit:barcode-scanning:17.3.0")   // confirm version via Context7
implementation("androidx.camera:camera-camera2:1.4.2")        // confirm version via Context7
implementation("androidx.camera:camera-lifecycle:1.4.2")      // confirm version via Context7
implementation("androidx.camera:camera-view:1.4.2")           // confirm version via Context7
```

**Step 3: Add CAMERA permission to AndroidManifest**

File: `app/src/main/AndroidManifest.xml`

After line 13 (`FOREGROUND_SERVICE_DATA_SYNC`), add:

```xml
<uses-permission android:name="android.permission.CAMERA" />
<uses-feature android:name="android.hardware.camera" android:required="false" />
```

**Step 4: Verify sync succeeds**

```powershell
cd c:\Scripts\KKCSheetTracker
.\gradlew.bat dependencies --configuration releaseRuntimeClasspath | Select-String "barcode-scanning|camera-camera2"
```

Expected: both artifacts listed with resolved versions, no resolution errors.

**Step 5: Commit**

```powershell
git add app/build.gradle.kts app/src/main/AndroidManifest.xml
git commit -m "build: add CameraX + ML Kit Barcode dependencies and CAMERA permission"
```

---

### Task 2: Add `barcodes` field to data models

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/data/models/SupplyModels.kt`
- Modify: `app/src/main/java/com/kkc/sheettracker/data/SupplyRepository.kt`
- Test: `app/src/test/java/com/kkc/sheettracker/data/SupplyRepositoryTest.kt`

**Step 1: Write the failing test**

Add to `SupplyRepositoryTest.kt`:

```kotlin
@Test
fun storedSupplyItemDeserializesWithMissingBarcodesField() {
    // Old JSON on disk has no barcodes field — must deserialize cleanly as empty list
    val json = """{"id":"i1","categoryId":"c1","name":"Screws","notes":null,
        "fields":{},"customFields":{},"attachmentIds":[],"createdAt":"","updatedAt":""}"""
    val item = Gson().fromJson(json, StoredSupplyItem::class.java)
    assertEquals(emptyList<String>(), item.barcodes)
}

@Test
fun resolvedSupplyItemCarriesBarcodesFromStoredItem() {
    val basePath = createTempBasePath()
    val itemsDir = File(basePath, ".supply/items").also { it.mkdirs() }
    val stored = StoredSupplyItem(
        id = "i1", categoryId = "c1", name = "Screws", notes = null,
        fields = emptyMap(), customFields = emptyMap(), attachmentIds = emptyList(),
        barcodes = listOf("CODE128-ABC", "QR-XYZ"),
        createdAt = "2026-01-01T00:00:00Z", updatedAt = "2026-01-01T00:00:00Z"
    )
    File(itemsDir, "i1.json").writeText(Gson().toJson(stored))
    val result = SupplyRepository(basePath).getItem("i1")
    assertEquals(listOf("CODE128-ABC", "QR-XYZ"), result?.barcodes)
}
```

**Step 2: Run to verify it fails**

```powershell
.\gradlew.bat test --tests "com.kkc.sheettracker.data.SupplyRepositoryTest.storedSupplyItemDeserializesWithMissingBarcodesField" --tests "com.kkc.sheettracker.data.SupplyRepositoryTest.resolvedSupplyItemCarriesBarcodesFromStoredItem"
```

Expected: compilation failure — `barcodes` field doesn't exist yet.

**Step 3: Add `barcodes` to `StoredSupplyItem` and `SupplyItem`**

In `SupplyModels.kt`, modify `StoredSupplyItem`:

```kotlin
data class StoredSupplyItem(
    val id: String,
    val categoryId: String,
    val name: String,
    val notes: String?,
    val fields: Map<String, String> = emptyMap(),
    val customFields: Map<String, String> = emptyMap(),
    val attachmentIds: List<SupplyAttachment> = emptyList(),
    val barcodes: List<String> = emptyList(),   // mirror of barcodes.json — backup only
    val createdAt: String = "",
    val updatedAt: String = ""
)
```

Modify `SupplyItem`:

```kotlin
data class SupplyItem(
    val id: String,
    val categoryId: String,
    val name: String,
    val status: String,
    val statusBy: String,
    val statusAt: String,
    val notes: String?,
    val fields: Map<String, String>,
    val customFields: Map<String, String>,
    val attachmentIds: List<SupplyAttachment>,
    val barcodes: List<String> = emptyList(),   // mirror field
    val createdAt: String,
    val updatedAt: String
)
```

In `SupplyRepository.kt`, update `resolveWith` to pass `barcodes`:

```kotlin
private fun StoredSupplyItem.resolveWith(s: SupplyStatusRecord): SupplyItem {
    return SupplyItem(
        id = id, categoryId = categoryId, name = name,
        status = s.status, statusBy = s.by, statusAt = s.at,
        notes = notes, fields = fields, customFields = customFields,
        attachmentIds = attachmentIds,
        barcodes = barcodes,
        createdAt = createdAt, updatedAt = updatedAt
    )
}
```

**Step 4: Run tests**

```powershell
.\gradlew.bat test --tests "com.kkc.sheettracker.data.SupplyRepositoryTest.*"
```

Expected: all `SupplyRepositoryTest` tests PASS.

**Step 5: Commit**

```powershell
git add app/src/main/java/com/kkc/sheettracker/data/models/SupplyModels.kt `
        app/src/main/java/com/kkc/sheettracker/data/SupplyRepository.kt `
        app/src/test/java/com/kkc/sheettracker/data/SupplyRepositoryTest.kt
git commit -m "feat(supply): add barcodes mirror field to StoredSupplyItem and SupplyItem"
```

---

### Task 3: Add `updateItemBarcodes` to `SupplyRepository`

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/data/SupplyRepository.kt`
- Test: `app/src/test/java/com/kkc/sheettracker/data/SupplyRepositoryTest.kt`

**Step 1: Write the failing test**

```kotlin
@Test
fun updateItemBarcodesWritesBarcodesFieldAtomically() {
    val basePath = createTempBasePath()
    val itemsDir = File(basePath, ".supply/items").also { it.mkdirs() }
    val stored = StoredSupplyItem(
        id = "i1", categoryId = "c1", name = "Screws", notes = null,
        fields = emptyMap(), customFields = emptyMap(), attachmentIds = emptyList(),
        barcodes = emptyList(), createdAt = "2026-01-01T00:00:00Z", updatedAt = "2026-01-01T00:00:00Z"
    )
    File(itemsDir, "i1.json").writeText(Gson().toJson(stored))

    SupplyRepository(basePath).updateItemBarcodes("i1", listOf("ABC-123", "QR-456"))

    val persisted = Gson().fromJson(File(itemsDir, "i1.json").readText(), StoredSupplyItem::class.java)
    assertEquals(listOf("ABC-123", "QR-456"), persisted.barcodes)
    assertTrue(itemsDir.listFiles().orEmpty().none { it.name.contains(".tmp-") })
}
```

**Step 2: Run to verify it fails**

```powershell
.\gradlew.bat test --tests "com.kkc.sheettracker.data.SupplyRepositoryTest.updateItemBarcodesWritesBarcodesFieldAtomically"
```

Expected: FAIL — method doesn't exist.

**Step 3: Add method to `SupplyRepository.kt`**

Add after `addAttachment(...)`:

```kotlin
fun updateItemBarcodes(itemId: String, barcodes: List<String>): SupplyItem? {
    val file = File(itemsDir, "$itemId.json")
    val existing = readJson<StoredSupplyItem>(file) ?: return null
    val updated = existing.copy(
        barcodes = barcodes,
        updatedAt = java.time.Instant.now().toString()
    )
    atomicWriteFile(file, gson.toJson(updated))
    return updated.resolve()
}
```

**Step 4: Run tests**

```powershell
.\gradlew.bat test --tests "com.kkc.sheettracker.data.SupplyRepositoryTest.*"
```

Expected: all PASS.

**Step 5: Commit**

```powershell
git add app/src/main/java/com/kkc/sheettracker/data/SupplyRepository.kt `
        app/src/test/java/com/kkc/sheettracker/data/SupplyRepositoryTest.kt
git commit -m "feat(supply): add updateItemBarcodes to SupplyRepository"
```

---

### Task 4: Create `SupplyBarcodeStore`

**Files:**
- Create: `app/src/main/java/com/kkc/sheettracker/data/SupplyBarcodeStore.kt`
- Create: `app/src/test/java/com/kkc/sheettracker/data/SupplyBarcodeStoreTest.kt`

**Step 1: Write the failing tests**

Create `SupplyBarcodeStoreTest.kt`:

```kotlin
package com.kkc.sheettracker.data

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SupplyBarcodeStoreTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun makeStore(): SupplyBarcodeStore {
        return SupplyBarcodeStore(tmp.root.absolutePath, SupplyRepository(tmp.root.absolutePath))
    }

    @Test
    fun lookupReturnsNullWhenBarcodesJsonMissing() {
        val store = makeStore()
        assertNull(store.lookup("any-barcode"))
    }

    @Test
    fun linkWritesBarcodesJsonAndItemMirror() {
        val basePath = tmp.root.absolutePath
        val itemsDir = File(basePath, ".supply/items").also { it.mkdirs() }
        val stored = com.kkc.sheettracker.data.models.StoredSupplyItem(
            id = "i1", categoryId = "c1", name = "Bolts", notes = null,
            fields = emptyMap(), customFields = emptyMap(), attachmentIds = emptyList(),
            barcodes = emptyList(), createdAt = "", updatedAt = ""
        )
        File(itemsDir, "i1.json").writeText(Gson().toJson(stored))

        val store = makeStore()
        store.linkSync("barcode-abc", "i1")

        assertEquals("i1", store.lookup("barcode-abc"))
        val index = Gson().fromJson(
            File(basePath, ".supply/barcodes.json").readText(),
            Map::class.java
        )
        assertEquals("i1", index["barcode-abc"])
        val item = Gson().fromJson(File(itemsDir, "i1.json").readText(),
            com.kkc.sheettracker.data.models.StoredSupplyItem::class.java)
        assertTrue(item.barcodes.contains("barcode-abc"))
    }

    @Test
    fun unlinkRemovesBarcodeFromIndexAndItem() {
        val basePath = tmp.root.absolutePath
        val supplyDir = File(basePath, ".supply").also { it.mkdirs() }
        val itemsDir = File(basePath, ".supply/items").also { it.mkdirs() }
        File(supplyDir, "barcodes.json").writeText("""{"barcode-abc":"i1"}""")
        val stored = com.kkc.sheettracker.data.models.StoredSupplyItem(
            id = "i1", categoryId = "c1", name = "Bolts", notes = null,
            fields = emptyMap(), customFields = emptyMap(), attachmentIds = emptyList(),
            barcodes = listOf("barcode-abc"), createdAt = "", updatedAt = ""
        )
        File(itemsDir, "i1.json").writeText(Gson().toJson(stored))

        val store = makeStore()
        store.unlinkSync("barcode-abc")

        assertNull(store.lookup("barcode-abc"))
        val index = Gson().fromJson(
            File(basePath, ".supply/barcodes.json").readText(),
            Map::class.java
        )
        assertFalse(index.containsKey("barcode-abc"))
    }

    @Test
    fun syncConflictFileIsIgnoredInIndex() {
        val basePath = tmp.root.absolutePath
        val supplyDir = File(basePath, ".supply").also { it.mkdirs() }
        File(supplyDir, "barcodes.json").writeText("""{"barcode-real":"i1"}""")
        // Sync conflict file must not be read as the index
        File(supplyDir, "barcodes.sync-conflict-20260101.json").writeText("""{"barcode-stale":"i2"}""")

        val store = makeStore()
        assertEquals("i1", store.lookup("barcode-real"))
        assertNull(store.lookup("barcode-stale"))
    }
}
```

**Step 2: Run to verify it fails**

```powershell
.\gradlew.bat test --tests "com.kkc.sheettracker.data.SupplyBarcodeStoreTest.*"
```

Expected: compilation failure — class doesn't exist yet.

**Step 3: Create `SupplyBarcodeStore.kt`**

```kotlin
package com.kkc.sheettracker.data

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

sealed interface ScanMode {
    object Idle : ScanMode
    object Global : ScanMode
    data class Item(val itemId: String) : ScanMode
}

class SupplyBarcodeStore(
    private val basePath: String,
    private val repository: SupplyRepository
) {
    private val gson = Gson()
    private val supplyDir get() = File(basePath, ".supply")
    private val barcodesFile get() = File(supplyDir, "barcodes.json")

    private val _scanMode = MutableStateFlow<ScanMode>(ScanMode.Idle)
    val scanMode: StateFlow<ScanMode> = _scanMode.asStateFlow()

    private val _pickPendingBarcode = MutableStateFlow<String?>(null)
    val pickPendingBarcode: StateFlow<String?> = _pickPendingBarcode.asStateFlow()

    fun setScanMode(mode: ScanMode) { _scanMode.value = mode }
    fun setPickPendingBarcode(barcode: String?) { _pickPendingBarcode.value = barcode }
    fun clearPickMode() {
        _pickPendingBarcode.value = null
        _scanMode.value = ScanMode.Idle
    }

    private fun readIndex(): Map<String, String> {
        if (!barcodesFile.exists()) return emptyMap()
        return runCatching {
            gson.fromJson<Map<String, String>>(
                barcodesFile.readText(),
                object : TypeToken<Map<String, String>>() {}.type
            )
        }.getOrDefault(emptyMap())
    }

    fun lookup(barcode: String): String? = readIndex()[barcode]

    fun resolveItemSync(barcode: String): com.kkc.sheettracker.data.models.SupplyItem? {
        val itemId = lookup(barcode) ?: return null
        return repository.getItem(itemId)
    }

    fun linkSync(barcode: String, itemId: String) {
        supplyDir.mkdirs()
        val current = readIndex().toMutableMap()
        current[barcode] = itemId
        atomicWriteFile(barcodesFile, gson.toJson(current))
        val item = repository.getItem(itemId) ?: return
        val updated = (item.barcodes + barcode).distinct()
        repository.updateItemBarcodes(itemId, updated)
    }

    fun unlinkSync(barcode: String) {
        val current = readIndex().toMutableMap()
        val itemId = current.remove(barcode) ?: return
        atomicWriteFile(barcodesFile, gson.toJson(current))
        val item = repository.getItem(itemId) ?: return
        repository.updateItemBarcodes(itemId, item.barcodes.filter { it != barcode })
    }

    suspend fun link(barcode: String, itemId: String) =
        withContext(Dispatchers.IO) { linkSync(barcode, itemId) }

    suspend fun unlink(barcode: String) =
        withContext(Dispatchers.IO) { unlinkSync(barcode) }

    suspend fun resolveItem(barcode: String): com.kkc.sheettracker.data.models.SupplyItem? =
        withContext(Dispatchers.IO) { resolveItemSync(barcode) }
}
```

**Step 4: Run tests**

```powershell
.\gradlew.bat test --tests "com.kkc.sheettracker.data.SupplyBarcodeStoreTest.*"
```

Expected: all 4 tests PASS.

**Step 5: Full test suite**

```powershell
.\gradlew.bat test
```

Expected: all PASS.

**Step 6: Commit**

```powershell
git add app/src/main/java/com/kkc/sheettracker/data/SupplyBarcodeStore.kt `
        app/src/test/java/com/kkc/sheettracker/data/SupplyBarcodeStoreTest.kt
git commit -m "feat(supply): add SupplyBarcodeStore with barcodes.json index and scan session state"
```

---

## Phase 2 — Scanner Composable (Android)

### Task 5: `ScannerReticle` composable

**Files:**
- Create: `app/src/main/java/com/kkc/sheettracker/ui/supply/ScannerReticle.kt`

> Pure Canvas/animation composable — no unit tests. Verified visually at integration time.

**Step 1: Create `ScannerReticle.kt`**

```kotlin
package com.kkc.sheettracker.ui.supply

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun ScannerReticle(
    modifier: Modifier = Modifier,
    reticleSize: Dp = 260.dp,
    cornerLength: Dp = 36.dp,
    strokeWidth: Dp = 4.dp,
    cornerColor: Color = Color.White
) {
    val infiniteTransition = rememberInfiniteTransition(label = "reticle_pulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.6f, targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "corner_alpha"
    )

    Canvas(modifier = modifier.fillMaxSize()) {
        val reticlePx = reticleSize.toPx()
        val left = (size.width - reticlePx) / 2f
        val top = (size.height - reticlePx) / 2f
        val right = left + reticlePx
        val bottom = top + reticlePx

        // Dark scrim outside reticle box
        drawRect(color = Color.Black.copy(alpha = 0.55f))
        // Clear the reticle area (no actual Compose API for "erase", so overlay pattern is:
        // draw scrim, then draw a transparent rect — alpha blending handles the visual)
        drawRect(color = Color.Transparent, topLeft = Offset(left, top), size = Size(reticlePx, reticlePx))

        val cLen = cornerLength.toPx()
        val sw = strokeWidth.toPx()
        val col = cornerColor.copy(alpha = alpha)
        drawCornerBrackets(left, top, right, bottom, cLen, sw, col)
    }
}

private fun DrawScope.drawCornerBrackets(
    left: Float, top: Float, right: Float, bottom: Float,
    len: Float, sw: Float, color: Color
) {
    val cap = StrokeCap.Round
    drawLine(color, Offset(left, top + len), Offset(left, top), sw, cap)
    drawLine(color, Offset(left, top), Offset(left + len, top), sw, cap)
    drawLine(color, Offset(right - len, top), Offset(right, top), sw, cap)
    drawLine(color, Offset(right, top), Offset(right, top + len), sw, cap)
    drawLine(color, Offset(left, bottom - len), Offset(left, bottom), sw, cap)
    drawLine(color, Offset(left, bottom), Offset(left + len, bottom), sw, cap)
    drawLine(color, Offset(right - len, bottom), Offset(right, bottom), sw, cap)
    drawLine(color, Offset(right, bottom), Offset(right, bottom - len), sw, cap)
}
```

**Step 2: Build**

```powershell
.\gradlew.bat assembleDebug
```

Expected: BUILD SUCCESSFUL.

**Step 3: Commit**

```powershell
git add app/src/main/java/com/kkc/sheettracker/ui/supply/ScannerReticle.kt
git commit -m "feat(supply): add ScannerReticle canvas composable with pulsing corner brackets"
```

---

### Task 6: `SupplyScannerOverlay` composable

**Files:**
- Create: `app/src/main/java/com/kkc/sheettracker/ui/supply/SupplyScannerOverlay.kt`

**Step 1: Create `SupplyScannerOverlay.kt`**

```kotlin
package com.kkc.sheettracker.ui.supply

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.kkc.sheettracker.data.SupplyBarcodeStore
import com.kkc.sheettracker.data.models.SupplyItem

private const val SCANNER_TAG = "SupplyScannerOverlay"

/**
 * Full-screen barcode scanner overlay using CameraX + ML Kit.
 *
 * Lifecycle: camera is bound to [LocalLifecycleOwner] and released via [DisposableEffect]
 * on composable disposal — no leaks.
 *
 * First successful barcode decode pauses analysis (freeze-frame) and calls the appropriate
 * callback. Caller must call [onDismiss] to close the overlay and reset scan state.
 */
@Composable
fun SupplyScannerOverlay(
    barcodeStore: SupplyBarcodeStore,
    onDismiss: () -> Unit,
    onKnownBarcode: (item: SupplyItem, barcode: String) -> Unit,
    onUnknownBarcode: (barcode: String) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasCameraPermission by remember { mutableStateOf(false) }
    var showPermissionRationale by remember { mutableStateOf(false) }
    var scanPaused by remember { mutableStateOf(false) }
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
        if (!granted) showPermissionRationale = true
    }

    LaunchedEffect(Unit) {
        val perm = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
        hasCameraPermission = perm == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!hasCameraPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    DisposableEffect(Unit) {
        onDispose { cameraProvider?.unbindAll() }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        when {
            hasCameraPermission -> {
                AndroidView(
                    factory = { ctx ->
                        val previewView = PreviewView(ctx)
                        val future = ProcessCameraProvider.getInstance(ctx)
                        future.addListener({
                            val provider = future.get()
                            cameraProvider = provider

                            val preview = Preview.Builder().build().also {
                                it.surfaceProvider = previewView.surfaceProvider
                            }

                            val barcodeScanner = BarcodeScanning.getClient()
                            val imageAnalysis = ImageAnalysis.Builder()
                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                .build()

                            imageAnalysis.setAnalyzer(ContextCompat.getMainExecutor(ctx)) { imageProxy ->
                                if (scanPaused) { imageProxy.close(); return@setAnalyzer }
                                val mediaImage = imageProxy.image
                                    ?: run { imageProxy.close(); return@setAnalyzer }
                                val image = InputImage.fromMediaImage(
                                    mediaImage, imageProxy.imageInfo.rotationDegrees
                                )
                                barcodeScanner.process(image)
                                    .addOnSuccessListener { barcodes ->
                                        val raw = barcodes.firstOrNull()?.rawValue
                                            ?.takeIf { it.isNotBlank() }
                                        if (raw != null && !scanPaused) {
                                            scanPaused = true
                                            val item = barcodeStore.resolveItemSync(raw)
                                            if (item != null) onKnownBarcode(item, raw)
                                            else onUnknownBarcode(raw)
                                        }
                                    }
                                    .addOnFailureListener { Log.w(SCANNER_TAG, "Scan failed", it) }
                                    .addOnCompleteListener { imageProxy.close() }
                            }

                            runCatching {
                                provider.unbindAll()
                                provider.bindToLifecycle(
                                    lifecycleOwner,
                                    CameraSelector.DEFAULT_BACK_CAMERA,
                                    preview, imageAnalysis
                                )
                            }.onFailure { Log.e(SCANNER_TAG, "Camera bind failed", it) }

                        }, ContextCompat.getMainExecutor(ctx))
                        previewView
                    },
                    modifier = Modifier.fillMaxSize()
                )

                ScannerReticle(modifier = Modifier.fillMaxSize())

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
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, "Close scanner", tint = Color.White)
                    }
                }
            }

            showPermissionRationale -> {
                Column(
                    Modifier.fillMaxSize().background(Color.Black),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Camera permission is required to scan barcodes.", color = Color.White)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.fromParts("package", context.packageName, null)
                            }
                        )
                    }) { Text("Open Settings") }
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = onDismiss) { Text("Cancel", color = Color.White) }
                }
            }
        }
    }
}
```

**Step 2: Build**

```powershell
.\gradlew.bat assembleDebug
```

Expected: BUILD SUCCESSFUL.

**Step 3: Commit**

```powershell
git add app/src/main/java/com/kkc/sheettracker/ui/supply/SupplyScannerOverlay.kt
git commit -m "feat(supply): add SupplyScannerOverlay with CameraX + ML Kit + permission handling"
```

---

### Task 7: Known + Unknown result bottom sheets

**Files:**
- Create: `app/src/main/java/com/kkc/sheettracker/ui/supply/SupplyBarcodeResultSheets.kt`

**Step 1: Create `SupplyBarcodeResultSheets.kt`**

```kotlin
package com.kkc.sheettracker.ui.supply

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.data.models.ALL_SUPPLY_STATUSES
import com.kkc.sheettracker.data.models.SupplyItem
import com.kkc.sheettracker.ui.components.StatusChip

/**
 * Shown when a known barcode is scanned from the header button.
 * Item name, category, current status, status chips, and View Item button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KnownBarcodeSheet(
    item: SupplyItem,
    categoryName: String,
    sheetState: SheetState,
    onStatusPick: (String) -> Unit,
    onViewItem: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(item.name, style = MaterialTheme.typography.headlineSmall,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(categoryName, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                StatusChip(status = item.status)
            }
            HorizontalDivider()
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ALL_SUPPLY_STATUSES.forEach { status ->
                    FilterChip(
                        selected = item.status == status,
                        onClick = { onStatusPick(status) },
                        label = { Text(status, style = MaterialTheme.typography.labelSmall) }
                    )
                }
            }
            HorizontalDivider()
            Button(onClick = onViewItem, modifier = Modifier.fillMaxWidth()) {
                Text("View Item")
            }
        }
    }
}

/**
 * Shown when an unknown barcode is scanned from the header button.
 * Offers Link to Existing or Add as New Item.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UnknownBarcodeSheet(
    barcode: String,
    sheetState: SheetState,
    onLinkToExisting: () -> Unit,
    onAddNewItem: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Unknown barcode", style = MaterialTheme.typography.titleMedium)
            Text(
                barcode,
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                maxLines = 2, overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            HorizontalDivider()
            Button(onClick = onLinkToExisting, modifier = Modifier.fillMaxWidth()) {
                Text("Link to Existing Item")
            }
            OutlinedButton(onClick = onAddNewItem, modifier = Modifier.fillMaxWidth()) {
                Text("Add as New Item")
            }
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    }
}
```

**Step 2: Build**

```powershell
.\gradlew.bat assembleDebug
```

Expected: BUILD SUCCESSFUL.

**Step 3: Commit**

```powershell
git add app/src/main/java/com/kkc/sheettracker/ui/supply/SupplyBarcodeResultSheets.kt
git commit -m "feat(supply): add KnownBarcodeSheet and UnknownBarcodeSheet composables"
```

---

## Phase 3 — Android UI Integration

### Task 8: Header scan button + pick mode banner + scanner wiring

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/supply/SupplyDashboardScreen.kt`

**Step 1: Add state vars near top of `SupplyDashboardScreen` body (after line ~120)**

After `var editChromeState by remember { ... }`:

```kotlin
val barcodeStore = remember(basePath) { SupplyBarcodeStore(basePath, repository) }
val scanMode by barcodeStore.scanMode.collectAsState()
val pickPendingBarcode by barcodeStore.pickPendingBarcode.collectAsState()
var knownBarcodeResult by remember { mutableStateOf<Pair<SupplyItem, String>?>(null) }
var unknownBarcodeResult by remember { mutableStateOf<String?>(null) }
var itemToConfirmLink by remember { mutableStateOf<Pair<SupplyItem, String>?>(null) }
var pendingNewItemBarcode by remember { mutableStateOf<String?>(null) }
```

**Step 2: Add scan icon to `topBarActions` (before the overflow `Box`, around line 236)**

```kotlin
topBarActions = {
    IconButton(onClick = { barcodeStore.setScanMode(ScanMode.Global) }) {
        Icon(Icons.Filled.QrCodeScanner, contentDescription = "Scan barcode")
    }
    // ... existing overflow Box unchanged ...
},
```

**Step 3: Add pick mode banner — insert before the search `OutlinedTextField` (around line 282)**

```kotlin
// Pick mode banner
AnimatedVisibility(visible = pickPendingBarcode != null, enter = expandVertically(), exit = shrinkVertically()) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Tap an item to link barcode", style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSecondaryContainer)
            IconButton(onClick = { barcodeStore.clearPickMode() }) {
                Icon(Icons.Filled.Close, "Cancel link", tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
    }
}
```

**Step 4: Intercept item taps for pick mode**

Find every place an item row's `onClick` opens the detail modal. Wrap each:

```kotlin
onClick = {
    val pending = pickPendingBarcode
    if (pending != null) {
        itemToConfirmLink = Pair(item, pending)
    } else {
        activeModal = SupplyDashboardModal.ItemDetail(item.id)
    }
}
```

**Step 5: Add scanner overlay + result sheets + dialogs at the end of the root `Box`**

After the `DashboardShell { ... }` closing brace, still inside the root `Box`:

```kotlin
// Scanner overlay (Global mode)
if (scanMode != ScanMode.Idle && scanMode == ScanMode.Global) {
    val knownSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val unknownSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    SupplyScannerOverlay(
        barcodeStore = barcodeStore,
        onDismiss = { barcodeStore.setScanMode(ScanMode.Idle) },
        onKnownBarcode = { item, barcode -> knownBarcodeResult = Pair(item, barcode) },
        onUnknownBarcode = { barcode -> unknownBarcodeResult = barcode }
    )

    knownBarcodeResult?.let { (item, barcode) ->
        KnownBarcodeSheet(
            item = item,
            categoryName = categoryMap[item.categoryId]?.name ?: "",
            sheetState = knownSheetState,
            onStatusPick = { newStatus ->
                scope.launch {
                    withContext(Dispatchers.IO) { repository.setStatus(item.id, newStatus, employeeName, tabletId) }
                    barcodeStore.setScanMode(ScanMode.Idle)
                    knownBarcodeResult = null
                    loadData()
                }
            },
            onViewItem = {
                barcodeStore.setScanMode(ScanMode.Idle)
                knownBarcodeResult = null
                activeModal = SupplyDashboardModal.ItemDetail(item.id)
            },
            onDismiss = { knownBarcodeResult = null; barcodeStore.setScanMode(ScanMode.Idle) }
        )
    }

    unknownBarcodeResult?.let { barcode ->
        UnknownBarcodeSheet(
            barcode = barcode,
            sheetState = unknownSheetState,
            onLinkToExisting = {
                unknownBarcodeResult = null
                barcodeStore.setScanMode(ScanMode.Idle)
                barcodeStore.setPickPendingBarcode(barcode)
            },
            onAddNewItem = {
                unknownBarcodeResult = null
                barcodeStore.setScanMode(ScanMode.Idle)
                pendingNewItemBarcode = barcode
                showAddCategoryDialog = true
            },
            onDismiss = { unknownBarcodeResult = null; barcodeStore.setScanMode(ScanMode.Idle) }
        )
    }
}

// Pick mode link confirmation dialog
itemToConfirmLink?.let { (item, barcode) ->
    AlertDialog(
        onDismissRequest = { itemToConfirmLink = null },
        title = { Text("Link barcode?") },
        text = { Text("Link \"${barcode.take(20)}\" to ${item.name}?") },
        confirmButton = {
            TextButton(onClick = {
                itemToConfirmLink = null
                scope.launch { barcodeStore.link(barcode, item.id); barcodeStore.clearPickMode(); loadData() }
            }) { Text("Link") }
        },
        dismissButton = { TextButton(onClick = { itemToConfirmLink = null }) { Text("Cancel") } }
    )
}
```

> Also wire `pendingNewItemBarcode` into your existing `createItem` / `openNewItemModal` call so the barcode is pre-linked immediately after item creation: call `barcodeStore.link(pendingNewItemBarcode!!, newItem.id)` then `pendingNewItemBarcode = null`.

**Step 6: Build + install**

```powershell
.\gradlew.bat assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

Manual verification:
- QR scan button visible in supply header
- Tap → camera overlay + reticle
- Scan unknown → Link/Add sheet
- "Link to Existing" → banner, tap item → confirm dialog → barcode linked

**Step 7: Full test suite**

```powershell
.\gradlew.bat test
```

Expected: all PASS.

**Step 8: Commit**

```powershell
git add app/src/main/java/com/kkc/sheettracker/ui/supply/SupplyDashboardScreen.kt
git commit -m "feat(supply): wire barcode scanner header button, pick mode banner, and result sheets into supply dashboard"
```

---

### Task 9: Barcode section in item detail modal

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/supply/SupplyItemDetailScreen.kt`

**Step 1: Add `barcodeStore` parameter to `SupplyItemDetailScreen`**

If `barcodeStore` is not already passed down, add it as a parameter. Pass the same `remember { SupplyBarcodeStore(basePath, repository) }` instance that the dashboard holds — the store should be a **single instance shared** between dashboard and detail screen (pass it from the call site in the dashboard, don't re-create it).

**Step 2: Add private `ItemBarcodeSection` composable at the bottom of the file**

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ItemBarcodeSection(
    item: SupplyItem,
    barcodeStore: SupplyBarcodeStore,
    scope: kotlinx.coroutines.CoroutineScope,
    onRefresh: () -> Unit
) {
    var confirmRemoveBarcode by remember { mutableStateOf<String?>(null) }
    val scanMode by barcodeStore.scanMode.collectAsState()
    var itemScanResult by remember { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("BARCODES", style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)

        if (item.barcodes.isEmpty()) {
            Text("No barcodes linked.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            item.barcodes.forEach { barcode ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        barcode,
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    IconButton(onClick = { confirmRemoveBarcode = barcode }) {
                        Icon(Icons.Filled.Delete, "Remove barcode",
                            tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }

        OutlinedButton(
            onClick = { barcodeStore.setScanMode(ScanMode.Item(item.id)) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Filled.QrCodeScanner, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Add Barcode")
        }
    }

    // Remove confirmation dialog
    confirmRemoveBarcode?.let { barcode ->
        AlertDialog(
            onDismissRequest = { confirmRemoveBarcode = null },
            title = { Text("Remove barcode?") },
            text = { Text("Remove \"${barcode.take(24)}\" from ${item.name}?") },
            confirmButton = {
                TextButton(
                    onClick = { confirmRemoveBarcode = null; scope.launch { barcodeStore.unlink(barcode); onRefresh() } },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { confirmRemoveBarcode = null }) { Text("Cancel") } }
        )
    }

    // Per-item scanner overlay
    if (scanMode is ScanMode.Item && (scanMode as ScanMode.Item).itemId == item.id) {
        val itemSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

        SupplyScannerOverlay(
            barcodeStore = barcodeStore,
            onDismiss = { barcodeStore.setScanMode(ScanMode.Idle) },
            onKnownBarcode = { foundItem, barcode ->
                if (foundItem.id == item.id) {
                    barcodeStore.setScanMode(ScanMode.Idle)
                    // Already linked — could show toast here
                } else {
                    itemScanResult = barcode
                }
            },
            onUnknownBarcode = { barcode -> itemScanResult = barcode }
        )

        itemScanResult?.let { barcode ->
            val onOtherItem = barcodeStore.lookup(barcode)?.let { it != item.id } ?: false
            AlertDialog(
                onDismissRequest = { itemScanResult = null; barcodeStore.setScanMode(ScanMode.Idle) },
                title = { Text(if (onOtherItem) "Move barcode?" else "Link barcode?") },
                text = {
                    if (onOtherItem)
                        Text("This barcode is linked to another item. Move it to ${item.name}?")
                    else
                        Text("Link \"${barcode.take(24)}\" to ${item.name}?")
                },
                confirmButton = {
                    TextButton(onClick = {
                        itemScanResult = null
                        barcodeStore.setScanMode(ScanMode.Idle)
                        scope.launch { barcodeStore.link(barcode, item.id); onRefresh() }
                    }) { Text(if (onOtherItem) "Move" else "Link") }
                },
                dismissButton = {
                    TextButton(onClick = { itemScanResult = null; barcodeStore.setScanMode(ScanMode.Idle) }) { Text("Cancel") }
                }
            )
        }
    }
}
```

**Step 3: Insert `ItemBarcodeSection` call in item detail body**

Find the section before comments in the item detail. Add:

```kotlin
HorizontalDivider()
ItemBarcodeSection(
    item = item,
    barcodeStore = barcodeStore,
    scope = scope,
    onRefresh = { /* call your existing item reload lambda */ }
)
```

**Step 4: Build + install + manual verify**

```powershell
.\gradlew.bat assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

Check:
- Item modal shows BARCODES section and Add Barcode button
- Scan a new barcode → confirm dialog → barcode row appears with trash icon
- Tap trash → confirm → barcode removed

**Step 5: Full test suite**

```powershell
.\gradlew.bat test
```

**Step 6: Commit**

```powershell
git add app/src/main/java/com/kkc/sheettracker/ui/supply/SupplyItemDetailScreen.kt
git commit -m "feat(supply): add barcode section to item detail modal with add/remove + per-item scanning"
```

---

## Phase 4 — Hours Tracker Backend

### Task 10: Barcode store functions in `supply_store.py`

**Files:**
- Modify: `c:\Scripts\Hours Tracker\backend\routes\supply_store.py`
- Create: `c:\Scripts\Hours Tracker\backend\tests\test_supply_barcodes.py`

**Step 1: Write failing tests — create `test_supply_barcodes.py`**

```python
import json, pytest
from pathlib import Path
import sys
sys.path.insert(0, str(Path(__file__).parent.parent))
import routes.supply_store as store
import routes.utils as utils

@pytest.fixture(autouse=True)
def use_tmp(tmp_path, monkeypatch):
    monkeypatch.setattr(utils, "get_base_path", lambda: tmp_path)
    store.ensure_dirs()
    yield

def _item(tmp_path, item_id, barcodes=None):
    obj = {"id": item_id, "categoryId": "c1", "name": "Bolts", "notes": None,
           "fields": {}, "customFields": {}, "attachmentIds": [],
           "barcodes": barcodes or [], "createdAt": "", "updatedAt": ""}
    p = tmp_path / ".supply" / "items" / f"{item_id}.json"
    p.write_text(json.dumps(obj))

def test_get_barcodes_empty(tmp_path):
    assert store.get_barcodes() == {}

def test_link_creates_index(tmp_path):
    _item(tmp_path, "i1")
    store.link_barcode("BC-1", "i1")
    assert json.loads((tmp_path / ".supply" / "barcodes.json").read_text())["BC-1"] == "i1"

def test_link_patches_item_mirror(tmp_path):
    _item(tmp_path, "i1")
    store.link_barcode("BC-1", "i1")
    item = json.loads((tmp_path / ".supply" / "items" / "i1.json").read_text())
    assert "BC-1" in item["barcodes"]

def test_unlink_removes_from_index_and_item(tmp_path):
    _item(tmp_path, "i1", ["BC-1"])
    (tmp_path / ".supply" / "barcodes.json").write_text(json.dumps({"BC-1": "i1"}))
    store.unlink_barcode("BC-1")
    assert "BC-1" not in json.loads((tmp_path / ".supply" / "barcodes.json").read_text())
    item = json.loads((tmp_path / ".supply" / "items" / "i1.json").read_text())
    assert "BC-1" not in item["barcodes"]

def test_link_conflict_raises(tmp_path):
    _item(tmp_path, "i1"); _item(tmp_path, "i2")
    store.link_barcode("BC-1", "i1")
    with pytest.raises(ValueError, match="already linked"):
        store.link_barcode("BC-1", "i2")
```

**Step 2: Run to verify failures**

```powershell
cd "c:\Scripts\Hours Tracker\backend"
.\venv\Scripts\python.exe -m pytest tests/test_supply_barcodes.py -v
```

Expected: errors — functions missing.

**Step 3: Add to `supply_store.py` (after `attachments_dir()`)**

```python
def barcodes_path() -> Path:
    return supply_data_dir() / "barcodes.json"

def get_barcodes() -> dict:
    ensure_dirs()
    return read_json(barcodes_path(), {})

def link_barcode(barcode: str, item_id: str) -> None:
    ensure_dirs()
    with json_file_lock(barcodes_path()):
        current = read_json(barcodes_path(), {})
        existing = current.get(barcode)
        if existing and existing != item_id:
            raise ValueError(f"Barcode '{barcode}' already linked to item '{existing}'")
        current[barcode] = item_id
        write_json(barcodes_path(), current)
    item_file = items_dir() / f"{item_id}.json"
    with json_file_lock(item_file):
        item = read_json(item_file, None)
        if item is not None:
            barcodes = item.get("barcodes", [])
            if barcode not in barcodes:
                item["barcodes"] = barcodes + [barcode]
                write_json(item_file, item)

def unlink_barcode(barcode: str) -> dict:
    ensure_dirs()
    with json_file_lock(barcodes_path()):
        current = read_json(barcodes_path(), {})
        item_id = current.pop(barcode, None)
        write_json(barcodes_path(), current)
    if item_id:
        item_file = items_dir() / f"{item_id}.json"
        with json_file_lock(item_file):
            item = read_json(item_file, None)
            if item is not None:
                item["barcodes"] = [b for b in item.get("barcodes", []) if b != barcode]
                write_json(item_file, item)
    return get_barcodes()
```

Also update `ensure_dirs()` to init `barcodes.json` if missing (after schema init block):

```python
barcodes = barcodes_path()
with json_file_lock(barcodes):
    if not barcodes.exists():
        write_json(barcodes, {})
```

**Step 4: Run tests**

```powershell
.\venv\Scripts\python.exe -m pytest tests/test_supply_barcodes.py -v
```

Expected: all 5 PASS.

**Step 5: Commit**

```powershell
cd "c:\Scripts\Hours Tracker"
git add backend/routes/supply_store.py backend/tests/test_supply_barcodes.py
git commit -m "feat(supply): add barcode CRUD to supply_store (get/link/unlink with item JSON mirror)"
```

---

### Task 11: Barcode REST endpoints in `supply.py`

**Files:**
- Modify: `c:\Scripts\Hours Tracker\backend\routes\supply.py`

**Step 1: Add after the schema endpoints**

```python
from pydantic import BaseModel as PydanticBase
from fastapi import HTTPException

class BarcodeLinkRequest(PydanticBase):
    barcode: str
    itemId: str

@router.get("/barcodes")
def get_barcodes_endpoint():
    return supply_store.get_barcodes()

@router.post("/barcodes")
def link_barcode_endpoint(req: BarcodeLinkRequest):
    try:
        supply_store.link_barcode(req.barcode, req.itemId)
        return {"ok": True}
    except ValueError as e:
        raise HTTPException(status_code=409, detail=str(e))

@router.delete("/barcodes/{barcode}")
def unlink_barcode_endpoint(barcode: str):
    supply_store.unlink_barcode(barcode)
    return {"ok": True}
```

**Step 2: Smoke test (backend running)**

```powershell
curl http://localhost:8000/api/supply/barcodes
curl -X POST http://localhost:8000/api/supply/barcodes -H "Content-Type: application/json" -d '{"barcode":"TEST-001","itemId":"<real-uuid>"}'
curl http://localhost:8000/api/supply/barcodes
curl -X DELETE "http://localhost:8000/api/supply/barcodes/TEST-001"
```

**Step 3: Commit**

```powershell
git add backend/routes/supply.py
git commit -m "feat(supply): add barcode REST endpoints GET/POST/DELETE /api/supply/barcodes"
```

---

### Task 12: QR PNG endpoint

**Files:**
- Modify: `c:\Scripts\Hours Tracker\backend\routes\supply.py`
- Modify: `c:\Scripts\Hours Tracker\backend\requirements.txt`

**Step 1: Install qrcode**

```powershell
cd "c:\Scripts\Hours Tracker\backend"
.\venv\Scripts\pip.exe install "qrcode[pil]"
```

Add to `requirements.txt`:
```
qrcode[pil]
```

**Step 2: Add endpoint to `supply.py`**

```python
from fastapi.responses import Response
from functools import lru_cache
import qrcode, io

@lru_cache(maxsize=256)
def _qr_png_bytes(barcode: str) -> bytes:
    qr = qrcode.QRCode(error_correction=qrcode.constants.ERROR_CORRECT_M, box_size=8, border=2)
    qr.add_data(barcode)
    qr.make(fit=True)
    img = qr.make_image(fill_color="black", back_color="white")
    buf = io.BytesIO()
    img.save(buf, format="PNG")
    return buf.getvalue()

@router.get("/barcodes/{barcode}/qr.png")
def get_barcode_qr_png(barcode: str):
    return Response(
        content=_qr_png_bytes(barcode),
        media_type="image/png",
        headers={"Cache-Control": "max-age=3600"}
    )
```

**Step 3: Verify**

```powershell
curl "http://localhost:8000/api/supply/barcodes/TEST-001/qr.png" --output test.png
# Open test.png — should be a valid QR code for "TEST-001"
```

**Step 4: Commit**

```powershell
git add backend/routes/supply.py backend/requirements.txt
git commit -m "feat(supply): add QR PNG generation endpoint with LRU memory cache"
```

---

### Task 13: Label PDF via pdfme

> **Prerequisite:** The pdfme worker (`handoff_pdfme_adapter.py`) must be operational.
> If not yet ready, add a `# TODO: pdfme label endpoint — pending pdfme worker` comment in `supply.py` and skip this task. Tasks 10-12 are independent.

**Files:**
- Modify: `c:\Scripts\Hours Tracker\backend\routes\supply.py`

**Step 1: Add label endpoint**

```python
from fastapi.responses import StreamingResponse
import base64

@router.post("/barcodes/{barcode}/label")
async def generate_barcode_label(barcode: str):
    index = supply_store.get_barcodes()
    item_id = index.get(barcode)
    item = supply_store.get_item(item_id) if item_id else None
    item_name = item.get("name", "Unknown Item") if item else "Unknown Item"
    cat_id = item.get("categoryId", "") if item else ""
    cats = supply_store.get_categories()
    cat_name = next((c["name"] for c in cats if c["id"] == cat_id), "")

    qr_b64 = base64.b64encode(_qr_png_bytes(barcode)).decode()

    template = {
        "schemas": [[
            {"name": "qr", "type": "image",
             "position": {"x": 10, "y": 10}, "width": 60, "height": 60},
            {"name": "itemName", "type": "text",
             "position": {"x": 5, "y": 72}, "width": 80, "height": 10,
             "fontSize": 9, "alignment": "center"},
            {"name": "catBarcode", "type": "text",
             "position": {"x": 5, "y": 83}, "width": 80, "height": 8,
             "fontSize": 7, "fontColor": "#555555", "alignment": "center"},
        ]],
        "basePdf": {"width": 90, "height": 95, "padding": [0, 0, 0, 0]}
    }
    inputs = [{
        "qr": f"data:image/png;base64,{qr_b64}",
        "itemName": item_name,
        "catBarcode": f"{cat_name} · {barcode}"
    }]

    from routes.handoff_pdfme_adapter import render_pdfme
    pdf_bytes = await render_pdfme(template, inputs)
    return StreamingResponse(
        io.BytesIO(pdf_bytes),
        media_type="application/pdf",
        headers={"Content-Disposition": f'inline; filename="label.pdf"'}
    )
```

**Step 2: Test**

```powershell
curl -X POST "http://localhost:8000/api/supply/barcodes/TEST-001/label" --output label.pdf
# Open label.pdf — should contain QR, item name, category, barcode value
```

**Step 3: Commit**

```powershell
git add backend/routes/supply.py
git commit -m "feat(supply): add QR label PDF endpoint via pdfme worker"
```

---

## Phase 5 — Hours Tracker Frontend

### Task 14: Barcode section on supply item detail page

**Files:**
- Find the supply item detail template:

```powershell
Get-ChildItem -Recurse "c:\Scripts\Hours Tracker" -Include "*.html","*.jinja2","*.j2" |
  Select-String "supply" | Select-Object Filename, LineNumber, Line | head -20
```

**Step 1: Add CSS to the page stylesheet**

```css
.supply-barcodes { margin-top: 1.5rem; }
.supply-barcodes h3 {
    font-size: 0.7rem; text-transform: uppercase;
    letter-spacing: 0.08em; color: #888; margin-bottom: 0.75rem;
}
.barcode-row {
    display: flex; align-items: center; gap: 12px;
    padding: 8px 0; border-bottom: 1px solid #eee;
}
.barcode-row:last-child { border-bottom: none; }
.qr-thumb { width: 48px; height: 48px; border: 1px solid #ddd; border-radius: 4px; }
.barcode-value {
    flex: 1; font-family: monospace; font-size: 0.875rem;
    color: #333; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.btn-print-label {
    padding: 4px 12px; font-size: 0.8rem;
    background: #1a73e8; color: white; border: none;
    border-radius: 4px; cursor: pointer; white-space: nowrap;
}
.btn-print-label:hover { background: #1557b0; }
```

**Step 2: Add HTML section in item detail template**

Insert after the existing item fields section:

```html
<section class="supply-barcodes">
  <h3>Barcodes</h3>
  {% if item.barcodes %}
    {% for barcode in item.barcodes %}
    <div class="barcode-row">
      <img src="/api/supply/barcodes/{{ barcode | urlencode }}/qr.png"
           class="qr-thumb" alt="QR" loading="lazy" />
      <span class="barcode-value" title="{{ barcode }}">{{ barcode }}</span>
      <button class="btn-print-label"
              onclick="printLabel({{ barcode | tojson }})">🖨 Print Label</button>
    </div>
    {% endfor %}
  {% else %}
    <p style="color:#999;font-size:0.85rem;">
      No barcodes linked. Use the tablet app to add barcodes.
    </p>
  {% endif %}
</section>
```

**Step 3: Add `printLabel` JavaScript**

```html
<script>
function printLabel(barcode) {
  fetch('/api/supply/barcodes/' + encodeURIComponent(barcode) + '/label', { method: 'POST' })
    .then(r => { if (!r.ok) throw new Error('Label error: ' + r.status); return r.blob(); })
    .then(blob => {
      const url = URL.createObjectURL(blob);
      const w = window.open(url);
      w.addEventListener('load', () => { w.print(); URL.revokeObjectURL(url); });
    })
    .catch(e => alert(e.message));
}
</script>
```

**Step 4: Manual verification**

1. Link a barcode via Android app (or `POST /api/supply/barcodes`)
2. Open supply item detail in Hours Tracker browser
3. Verify "Barcodes" section appears with QR thumbnail and Print Label button
4. Click Print Label → PDF opens → browser print dialog appears

**Step 5: Commit**

```powershell
cd "c:\Scripts\Hours Tracker"
git add .
git commit -m "feat(supply): add barcodes section with QR thumbnails and Print Label to item detail page"
```

---

## Final Verification Pass

### Android — all unit tests

```powershell
cd c:\Scripts\KKCSheetTracker
.\gradlew.bat test
```

### Hours Tracker — barcode tests

```powershell
cd "c:\Scripts\Hours Tracker\backend"
.\venv\Scripts\python.exe -m pytest tests/test_supply_barcodes.py -v
```

### Device smoke test matrix

| Scenario | Pass? |
|---|---|
| Camera permission denied → explanation dialog |   |
| Scan known barcode (header) → quick action sheet |   |
| Apply status via scan → item updates |   |
| Scan unknown → link/add sheet |   |
| Link to existing → pick mode banner → confirm → linked |   |
| Add as new item → category picker → new item + barcode pre-linked |   |
| Per-item "Add Barcode" → scan → confirm → row appears |   |
| Trash barcode → confirm → removed |   |
| Barcode on different item (per-item modal) → "Move here?" |   |
| Hours Tracker detail → barcodes section visible |   |
| Hours Tracker Print Label → PDF opens |   |

---

*Spec: [supply_barcode_spec.md](file:///C:/Users/chadc/.gemini/antigravity/brain/28cb9fa6-f76f-45eb-8039-93674991c84e/supply_barcode_spec.md)*
