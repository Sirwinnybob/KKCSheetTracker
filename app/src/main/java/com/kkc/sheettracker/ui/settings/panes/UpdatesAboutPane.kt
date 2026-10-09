package com.kkc.sheettracker.ui.settings.panes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.BuildConfig
import com.kkc.sheettracker.ui.settings.CardBody
import com.kkc.sheettracker.ui.settings.GroupCard
import com.kkc.sheettracker.ui.settings.GroupDivider
import com.kkc.sheettracker.update.ExternalAppUpdate
import java.io.File

@Composable
internal fun UpdatesAboutPane(
    pendingSelfUpdate: File?,
    /** Updates for apps already installed on this tablet. */
    pendingExternalUpdates: List<ExternalAppUpdate>,
    /** Companion apps offered on the feed but not installed here; shown separately, not as updates. */
    availableApps: List<ExternalAppUpdate>,
    onInstallSelfUpdate: () -> Unit,
    onInstallExternalUpdate: (ExternalAppUpdate) -> Unit,
    onInstallAll: () -> Unit,
    onCheckForUpdates: () -> Unit,
    isDebugBuild: Boolean,
    onReinstallLatest: () -> Unit,
) {
    val hasSelfUpdate = pendingSelfUpdate != null
    val hasExternalUpdates = pendingExternalUpdates.isNotEmpty()

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (hasSelfUpdate || hasExternalUpdates) {
            GroupCard(caption = "Pending updates") {
                if (hasSelfUpdate && hasExternalUpdates) {
                    CardBody {
                        Button(onClick = onInstallAll, modifier = Modifier.fillMaxWidth()) { Text("Update All") }
                    }
                    GroupDivider()
                }
                if (hasSelfUpdate) {
                    UpdateRow("KKC Sheet Tracker update available", "Update", onClick = onInstallSelfUpdate)
                }
                pendingExternalUpdates.forEachIndexed { index, update ->
                    if (hasSelfUpdate || index > 0) GroupDivider()
                    UpdateRow("${update.appName} ${update.versionName} available", "Update") {
                        onInstallExternalUpdate(update)
                    }
                }
            }
        }

        if (availableApps.isNotEmpty()) {
            GroupCard(caption = "Available apps") {
                availableApps.forEachIndexed { index, app ->
                    if (index > 0) GroupDivider()
                    UpdateRow(
                        label = "${app.appName} ${app.versionName}",
                        subtitle = "Not installed on this tablet",
                        action = "Install"
                    ) { onInstallExternalUpdate(app) }
                }
            }
        }

        GroupCard(caption = "About") {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "KKC Sheet Tracker v${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(onClick = onCheckForUpdates, shape = RoundedCornerShape(8.dp)) {
                    Text("Check for updates")
                }
            }
            if (isDebugBuild) {
                GroupDivider()
                TextButton(onClick = onReinstallLatest, modifier = Modifier.padding(horizontal = 8.dp)) {
                    Text("Reinstall Latest Debug APK")
                }
            }
        }
    }
}

@Composable
private fun UpdateRow(label: String, action: String, subtitle: String? = null, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Button(onClick = onClick, shape = RoundedCornerShape(8.dp)) { Text(action) }
    }
}
