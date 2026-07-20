package com.kkc.sheettracker.ui.onboarding

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.kkc.sheettracker.onboarding.OnboardingStep

@Composable
fun OnboardingGate(
    step: OnboardingStep,
    notificationsBlocked: Boolean,
    onRequestNotifications: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onConfirmStorageAccess: () -> Unit,
    onConfirmInstallPermission: () -> Unit
) {
    when (step) {
        OnboardingStep.NOTIFICATIONS -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Notifications Needed") },
            text = {
                Text(
                    if (notificationsBlocked) {
                        "Sheet Tracker uses notifications for clock-in/out reminders. " +
                            "Android is blocking the prompt — tap Open Settings to allow it there."
                    } else {
                        "Sheet Tracker uses notifications for clock-in/out reminders. Tap OK to allow."
                    }
                )
            },
            confirmButton = {
                Button(
                    onClick = if (notificationsBlocked) onOpenNotificationSettings else onRequestNotifications
                ) {
                    Text(if (notificationsBlocked) "Open Settings" else "OK")
                }
            }
        )
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
