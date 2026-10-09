package com.kkc.sheettracker.ui.settings.panes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.data.AdminModeController
import com.kkc.sheettracker.ui.settings.CardBody
import com.kkc.sheettracker.ui.settings.GroupCard

@Composable
internal fun AdminPane(adminMode: Boolean, onUnlockRequested: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        GroupCard(caption = "Admin mode") {
            if (!adminMode) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Admin", style = MaterialTheme.typography.bodyLarge)
                        Text("Unlock advanced controls", style = MaterialTheme.typography.bodySmall, color = muted)
                    }
                    OutlinedButton(onClick = onUnlockRequested, shape = RoundedCornerShape(8.dp)) { Text("Unlock") }
                }
            } else {
                CardBody {
                    Text("Admin mode is ON", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.tertiary)
                    Text(
                        "The supply \"To Order\" tab is visible, and the Jobs tab shows a reorder control.",
                        style = MaterialTheme.typography.bodySmall,
                        color = muted
                    )
                    TextButton(onClick = { AdminModeController.setEnabled(false) }) { Text("Lock admin") }
                }
            }
        }
    }
}
