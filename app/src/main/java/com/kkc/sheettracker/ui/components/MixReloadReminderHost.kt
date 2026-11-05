package com.kkc.sheettracker.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.window.DialogProperties
import com.kkc.sheettracker.data.cnc.MixReloadReminder
import com.kkc.sheettracker.data.cnc.MixReloadReminders

const val MIX_RELOAD_TITLE = "Reload this mix in the CNC Controller"

internal fun mixReloadMessage(reminder: MixReloadReminder): String =
    "${reminder.machine} still has the old ${reminder.material} files loaded (${reminder.job}). " +
        "Changes won't cut until the mix is reloaded."

/** Must be acknowledged: the CNC controller keeps cutting its cached copies until the mix is reloaded. */
@Composable
fun MixReloadReminderHost(reminders: MixReloadReminders) {
    val pending by reminders.reminders.collectAsState()
    val reminder = pending.firstOrNull() ?: return
    MixReloadReminderDialog(reminder) { reminders.dismiss(reminder.id) }
}

@Composable
internal fun MixReloadReminderDialog(reminder: MixReloadReminder, onOk: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        modifier = Modifier.testTag("mix-reload-reminder"),
        title = { Text(MIX_RELOAD_TITLE) },
        text = { Text(mixReloadMessage(reminder)) },
        confirmButton = { TextButton(onClick = onOk) { Text("OK") } },
    )
}
