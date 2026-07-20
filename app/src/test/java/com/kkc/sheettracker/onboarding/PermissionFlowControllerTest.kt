package com.kkc.sheettracker.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PermissionFlowControllerTest {

    @Test
    fun `fresh device on Android 13 needs notifications first`() {
        val snapshot = PermissionSnapshot(
            sdkInt = 33,
            notificationsGranted = false,
            storageGranted = false,
            installUnknownAppsGranted = false
        )
        assertEquals(OnboardingStep.NOTIFICATIONS, PermissionFlowController.nextStep(snapshot))
    }

    @Test
    fun `notifications granted then storage access is next`() {
        val snapshot = PermissionSnapshot(
            sdkInt = 33,
            notificationsGranted = true,
            storageGranted = false,
            installUnknownAppsGranted = false
        )
        assertEquals(OnboardingStep.STORAGE_ACCESS, PermissionFlowController.nextStep(snapshot))
    }

    @Test
    fun `notifications and storage granted then install permission is next`() {
        val snapshot = PermissionSnapshot(
            sdkInt = 33,
            notificationsGranted = true,
            storageGranted = true,
            installUnknownAppsGranted = false
        )
        assertEquals(OnboardingStep.INSTALL_UNKNOWN_APPS, PermissionFlowController.nextStep(snapshot))
    }

    @Test
    fun `all three granted means onboarding is complete`() {
        val snapshot = PermissionSnapshot(
            sdkInt = 33,
            notificationsGranted = true,
            storageGranted = true,
            installUnknownAppsGranted = true
        )
        assertNull(PermissionFlowController.nextStep(snapshot))
    }

    @Test
    fun `pre-Android 13 devices skip the notifications step`() {
        val snapshot = PermissionSnapshot(
            sdkInt = 29,
            notificationsGranted = false,
            storageGranted = false,
            installUnknownAppsGranted = false
        )
        assertEquals(OnboardingStep.STORAGE_ACCESS, PermissionFlowController.nextStep(snapshot))
    }

    @Test
    fun `pre-Android 11 devices skip both notifications and storage steps`() {
        val snapshot = PermissionSnapshot(
            sdkInt = 28,
            notificationsGranted = false,
            storageGranted = false,
            installUnknownAppsGranted = false
        )
        assertEquals(OnboardingStep.INSTALL_UNKNOWN_APPS, PermissionFlowController.nextStep(snapshot))
    }
}
