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
