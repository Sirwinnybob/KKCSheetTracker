package com.kkc.sheettracker.ui.supply

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraLaunchSafetyTest {
    @Test
    fun `launchCameraSafely requests access instead of launching when camera permission is revoked`() {
        var launched = false
        var requested = false

        val result = launchCameraSafely(
            cameraPermissionGranted = false,
            requestPermission = { requested = true },
            launch = { launched = true }
        )

        assertEquals(CameraLaunchResult.PERMISSION_REQUESTED, result)
        assertTrue(requested)
        assertFalse(launched)
    }

    @Test
    fun `launchCameraSafely contains a security denial from the camera app`() {
        val result = launchCameraSafely(
            cameraPermissionGranted = true,
            requestPermission = {},
            launch = { throw SecurityException("Camera permission revoked") }
        )

        assertEquals(CameraLaunchResult.LAUNCH_FAILED, result)
    }

    @Test
    fun `launchCameraSafely launches when permission is granted`() {
        var launched = false

        val result = launchCameraSafely(
            cameraPermissionGranted = true,
            requestPermission = {},
            launch = { launched = true }
        )

        assertEquals(CameraLaunchResult.LAUNCHED, result)
        assertTrue(launched)
    }
}
