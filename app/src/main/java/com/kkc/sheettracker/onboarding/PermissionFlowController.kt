package com.kkc.sheettracker.onboarding

import android.os.Build

enum class OnboardingStep {
    NOTIFICATIONS,
    STORAGE_ACCESS,
    INSTALL_UNKNOWN_APPS
}

data class PermissionSnapshot(
    val sdkInt: Int,
    val notificationsGranted: Boolean,
    val storageGranted: Boolean,
    val installUnknownAppsGranted: Boolean
)

object PermissionFlowController {
    fun nextStep(snapshot: PermissionSnapshot): OnboardingStep? {
        if (snapshot.sdkInt >= Build.VERSION_CODES.TIRAMISU && !snapshot.notificationsGranted) {
            return OnboardingStep.NOTIFICATIONS
        }
        if (snapshot.sdkInt >= Build.VERSION_CODES.Q && !snapshot.storageGranted) {
            return OnboardingStep.STORAGE_ACCESS
        }
        if (!snapshot.installUnknownAppsGranted) {
            return OnboardingStep.INSTALL_UNKNOWN_APPS
        }
        return null
    }
}
