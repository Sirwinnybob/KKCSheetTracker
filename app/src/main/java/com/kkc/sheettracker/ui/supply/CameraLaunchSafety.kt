package com.kkc.sheettracker.ui.supply

enum class CameraLaunchResult {
    LAUNCHED,
    PERMISSION_REQUESTED,
    LAUNCH_FAILED
}

/** Keeps a revoked camera permission or a rejected camera intent inside the attachment flow. */
fun launchCameraSafely(
    cameraPermissionGranted: Boolean,
    requestPermission: () -> Unit,
    launch: () -> Unit
): CameraLaunchResult {
    if (!cameraPermissionGranted) {
        requestPermission()
        return CameraLaunchResult.PERMISSION_REQUESTED
    }
    return try {
        launch()
        CameraLaunchResult.LAUNCHED
    } catch (_: SecurityException) {
        CameraLaunchResult.LAUNCH_FAILED
    } catch (_: android.content.ActivityNotFoundException) {
        CameraLaunchResult.LAUNCH_FAILED
    }
}
