package com.kkc.sheettracker.ui.onboarding

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.kkc.sheettracker.onboarding.OnboardingStep

@Composable
fun OnboardingGate(
    step: OnboardingStep,
    onRequestNotifications: () -> Unit,
    onConfirmStorageAccess: () -> Unit,
    onConfirmInstallPermission: () -> Unit
) {
    LaunchedEffect(step) {
        if (step == OnboardingStep.NOTIFICATIONS) {
            onRequestNotifications()
        }
    }
    when (step) {
        OnboardingStep.NOTIFICATIONS -> Unit
        OnboardingStep.STORAGE_ACCESS -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Storage Access Needed") },
            text = {
                Text(
                    "Sheet Tracker needs full storage access to read job files and sync data. " +
                        "Tap OK to grant it in the next screen."
                )
            },
            confirmButton = {
                Button(onClick = onConfirmStorageAccess) { Text("OK") }
            }
        )
        OnboardingStep.INSTALL_UNKNOWN_APPS -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Install Permission Needed") },
            text = { Text("Needed to install app updates when they're released.") },
            confirmButton = {
                Button(onClick = onConfirmInstallPermission) { Text("OK") }
            }
        )
    }
}
