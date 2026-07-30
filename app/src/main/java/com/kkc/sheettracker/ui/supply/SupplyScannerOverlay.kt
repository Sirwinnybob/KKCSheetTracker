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
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
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
import com.kkc.sheettracker.data.ScannerSettingsStore
import com.kkc.sheettracker.data.SupplyBarcodeStore
import com.kkc.sheettracker.data.models.SupplyItem
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.geometry.Size
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

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
@android.annotation.SuppressLint("UnsafeOptInUsageError")
@Composable
fun SupplyScannerOverlay(
    barcodeStore: SupplyBarcodeStore,
    isModalActive: Boolean,
    onDismiss: () -> Unit,
    onKnownBarcode: (item: SupplyItem, barcode: String) -> Unit,
    onUnknownBarcode: (barcode: String) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val density = androidx.compose.ui.platform.LocalDensity.current

    var hasCameraPermission by remember { mutableStateOf(false) }
    var showPermissionRationale by remember { mutableStateOf(false) }
    var lockedBarcodeValue by remember { mutableStateOf<String?>(null) }
    var candidateBarcodeValue by remember { mutableStateOf<String?>(null) }
    var consecutiveHits by remember { mutableIntStateOf(0) }
    var candidateFrameMisses by remember { mutableIntStateOf(0) }
    var isCooldownActive by remember { mutableStateOf(false) }
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }

    var camera by remember { mutableStateOf<Camera?>(null) }
    var isTorchOn by remember { mutableStateOf(true) }
    val scannerSettingsStore = remember { ScannerSettingsStore(context) }

    var detectedBox by remember { mutableStateOf<android.graphics.Rect?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(isModalActive) {
        if (!isModalActive && lockedBarcodeValue != null) {
            lockedBarcodeValue = null
            candidateBarcodeValue = null
            consecutiveHits = 0
            candidateFrameMisses = 0
            detectedBox = null
            camera?.cameraControl?.enableTorch(false)
            isCooldownActive = true
            delay(2000)
            isCooldownActive = false
        }
    }
    var parentSize by remember { mutableStateOf(Size.Zero) }

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

    LaunchedEffect(Unit) {
        isTorchOn = scannerSettingsStore.torchOnFlow.first()
    }

    LaunchedEffect(camera) {
        if (camera != null && isTorchOn) {
            camera?.cameraControl?.enableTorch(true)
        }
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
                                try {
                                    if (isCooldownActive) return@setAnalyzer
                                    val mediaImage = imageProxy.image ?: return@setAnalyzer
                                    val image = InputImage.fromMediaImage(
                                        mediaImage, imageProxy.imageInfo.rotationDegrees
                                    )
                                    barcodeScanner.process(image)
                                        .addOnSuccessListener { barcodes ->
                                            val rotation = imageProxy.imageInfo.rotationDegrees
                                            val lockedVal = lockedBarcodeValue

                                            if (lockedVal != null) {
                                                // Keep tracking/updating coordinates for the locked barcode
                                                val matchingBarcode = barcodes.firstOrNull { it.rawValue == lockedVal }
                                                val rect = matchingBarcode?.boundingBox
                                                if (rect != null && parentSize.width > 0 && parentSize.height > 0) {
                                                    val mapped = mapBoundingBox(
                                                        rect = rect,
                                                        imageWidth = imageProxy.width,
                                                        imageHeight = imageProxy.height,
                                                        rotation = rotation,
                                                        parentWidth = parentSize.width,
                                                        parentHeight = parentSize.height
                                                    )
                                                    detectedBox = smoothRect(mapped, detectedBox, alpha = 0.12f)
                                                }
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

                                                    val reticlePx = with(density) { 300.dp.toPx() }
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
                                                    candidateFrameMisses = 0
                                                    consecutiveHits++

                                                    val rect = barcode.boundingBox
                                                    if (rect != null && parentSize.width > 0 && parentSize.height > 0) {
                                                        detectedBox = smoothRect(
                                                            mapBoundingBox(
                                                                rect = rect,
                                                                imageWidth = imageProxy.width,
                                                                imageHeight = imageProxy.height,
                                                                rotation = rotation,
                                                                parentWidth = parentSize.width,
                                                                parentHeight = parentSize.height
                                                            ),
                                                            detectedBox, alpha = 0.12f
                                                        )
                                                    }

                                                    if (consecutiveHits >= 3) {
                                                        lockedBarcodeValue = raw
                                                        scope.launch {
                                                            delay(450)
                                                            val item = barcodeStore.resolveItem(raw)
                                                            if (item != null) onKnownBarcode(item, raw)
                                                            else onUnknownBarcode(raw)
                                                        }
                                                    }
                                                } else if (candidateBarcodeValue != null && raw == null) {
                                                    candidateFrameMisses++
                                                    if (candidateFrameMisses >= 10) {
                                                        candidateBarcodeValue = null
                                                        consecutiveHits = 0
                                                        candidateFrameMisses = 0
                                                    }
                                                } else {
                                                    candidateBarcodeValue = raw
                                                    consecutiveHits = if (raw != null) 1 else 0
                                                    candidateFrameMisses = 0
                                                    if (raw != null) {
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
                                                    }
                                                }
                                            }
                                        }
                                        .addOnFailureListener { Log.w(SCANNER_TAG, "Scan failed", it) }
                                } catch (e: Exception) {
                                    Log.w(SCANNER_TAG, "Analyzer error", e)
                                } finally {
                                    imageProxy.close()
                                }
                            }

                            runCatching {
                                provider.unbindAll()
                                camera = provider.bindToLifecycle(
                                    lifecycleOwner,
                                    CameraSelector.DEFAULT_BACK_CAMERA,
                                    preview, imageAnalysis
                                )
                            }.onFailure { Log.e(SCANNER_TAG, "Camera bind failed", it) }

                        }, ContextCompat.getMainExecutor(ctx))
                        previewView
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .onGloballyPositioned {
                            parentSize = Size(it.size.width.toFloat(), it.size.height.toFloat())
                        }
                )

                ScannerReticle(
                    modifier = Modifier.fillMaxSize(),
                    detectedBox = detectedBox
                )

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
                            scope.launch { scannerSettingsStore.setTorchOn(isTorchOn) }
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

private fun mapBoundingBox(
    rect: android.graphics.Rect,
    imageWidth: Int,
    imageHeight: Int,
    rotation: Int,
    parentWidth: Float,
    parentHeight: Float
): android.graphics.Rect {
    val isRotated = rotation == 90 || rotation == 270
    val displayWidth = if (isRotated) imageHeight.toFloat() else imageWidth.toFloat()
    val displayHeight = if (isRotated) imageWidth.toFloat() else imageHeight.toFloat()

    // PreviewView scales uniformly using FILL_CENTER, matching the larger scale factor
    val scale = maxOf(parentWidth / displayWidth, parentHeight / displayHeight)

    // Center offsets inside the cropped viewport
    val offsetX = (displayWidth * scale - parentWidth) / 2f
    val offsetY = (displayHeight * scale - parentHeight) / 2f

    // Since ML Kit already rotates coordinates to match the input rotationDegrees,
    // the bounding box is already aligned with the display orientation.
    // We only need to apply the uniform scaling and centering offsets.
    val left = rect.left * scale - offsetX
    val right = rect.right * scale - offsetX
    val top = rect.top * scale - offsetY
    val bottom = rect.bottom * scale - offsetY

    return android.graphics.Rect(
        left.coerceAtLeast(0f).toInt(),
        top.coerceAtLeast(0f).toInt(),
        right.coerceAtMost(parentWidth).toInt(),
        bottom.coerceAtMost(parentHeight).toInt()
    )
}

private fun smoothRect(
    target: android.graphics.Rect,
    previous: android.graphics.Rect?,
    alpha: Float
): android.graphics.Rect {
    if (previous == null) return target
    return android.graphics.Rect(
        (previous.left + alpha * (target.left - previous.left)).toInt(),
        (previous.top + alpha * (target.top - previous.top)).toInt(),
        (previous.right + alpha * (target.right - previous.right)).toInt(),
        (previous.bottom + alpha * (target.bottom - previous.bottom)).toInt()
    )
}
