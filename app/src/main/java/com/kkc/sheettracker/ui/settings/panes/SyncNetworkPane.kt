package com.kkc.sheettracker.ui.settings.panes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.data.AdminSyncConfig
import com.kkc.sheettracker.data.TimecardServerConfig
import com.kkc.sheettracker.sync.SyncthingServiceStatus
import com.kkc.sheettracker.sync.SyncthingStatusUiState
import com.kkc.sheettracker.ui.settings.CardBody
import com.kkc.sheettracker.ui.settings.GroupCard
import com.kkc.sheettracker.ui.settings.SaveableField
import com.kkc.sheettracker.ui.settings.storedIpOrNull
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Composable
internal fun SyncNetworkPane(
    syncthingApiKey: String,
    syncthingStatus: SyncthingStatusUiState,
    onSyncthingApiKeySave: (String) -> Unit,
    onSyncthingCheckNow: () -> Unit,
    onSyncthingStartNow: () -> Unit,
    timecardConfig: TimecardServerConfig,
    adminSyncConfig: AdminSyncConfig,
) {
    val scope = rememberCoroutineScope()
    val currentServerIp by timecardConfig.serverIpFlow.collectAsState(initial = null)
    val currentAdminSyncIp by adminSyncConfig.serverIpFlow.collectAsState(initial = null)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        GroupCard(caption = "Syncthing") {
            CardBody {
                SyncStatusBadge(syncthingStatus.status)
                syncthingStatus.lastCheckedAtMs?.let {
                    Text("Last check: ${clockTime(it)}", style = MaterialTheme.typography.bodySmall, color = muted)
                }
                syncthingStatus.lastStartAttemptAtMs?.let {
                    Text("Last restart attempt: ${clockTime(it)}", style = MaterialTheme.typography.bodySmall, color = muted)
                }
                SaveableField(
                    label = "Syncthing API Key",
                    savedValue = syncthingApiKey,
                    onSave = onSyncthingApiKeySave,
                    supportingText = "Used for localhost API checks at 127.0.0.1:8384.",
                    saveLabel = "Save API Key",
                    password = true
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onSyncthingCheckNow,
                        enabled = syncthingApiKey.isNotBlank(),
                        shape = RoundedCornerShape(8.dp)
                    ) { Text("Check Now") }
                    Button(
                        onClick = onSyncthingStartNow,
                        enabled = syncthingApiKey.isNotBlank(),
                        shape = RoundedCornerShape(8.dp)
                    ) { Text("Start Now") }
                }
            }
        }
        GroupCard(caption = "Timeclock hub") {
            CardBody {
                SaveableField(
                    label = "Server IP address",
                    savedValue = currentServerIp ?: "",
                    onSave = { text -> scope.launch { timecardConfig.setManualIp(storedIpOrNull(text)) } },
                    placeholder = "Auto (mDNS discovery)",
                    supportingText = "Leave blank to use automatic discovery. Enter an IP to skip mDNS.",
                    allowBlank = true,
                    keyboardType = KeyboardType.Uri
                )
            }
        }
        GroupCard(caption = "Hours Tracker admin sync") {
            CardBody {
                SaveableField(
                    label = "Hours Tracker server IP address",
                    savedValue = currentAdminSyncIp ?: "",
                    onSave = { text -> scope.launch { adminSyncConfig.setManualIp(storedIpOrNull(text)) } },
                    placeholder = "Not configured (fast path disabled)",
                    supportingText = "Enables instant job order / job board / delivery schedule sync. Leave blank to always use the existing (slower) sync mechanism.",
                    allowBlank = true,
                    keyboardType = KeyboardType.Uri
                )
            }
        }
    }
}

@Composable
private fun SyncStatusBadge(status: SyncthingServiceStatus) {
    val scheme = MaterialTheme.colorScheme
    val (bg, fg, text) = when (status) {
        SyncthingServiceStatus.CHECKING -> Triple(scheme.tertiaryContainer, scheme.onTertiaryContainer, "Checking")
        SyncthingServiceStatus.RUNNING -> Triple(scheme.primaryContainer, scheme.onPrimaryContainer, "Running")
        SyncthingServiceStatus.PAUSED -> Triple(scheme.secondaryContainer, scheme.onSecondaryContainer, "Paused")
        SyncthingServiceStatus.NOT_RUNNING -> Triple(scheme.errorContainer, scheme.onErrorContainer, "Not running")
        SyncthingServiceStatus.START_FAILED -> Triple(scheme.errorContainer, scheme.onErrorContainer, "Start failed")
        SyncthingServiceStatus.API_KEY_REQUIRED -> Triple(scheme.tertiaryContainer, scheme.onTertiaryContainer, "API key required")
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(bg)
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Text("Status: $text", style = MaterialTheme.typography.labelMedium, color = fg, fontWeight = FontWeight.SemiBold)
    }
}

private fun clockTime(timestampMs: Long): String =
    DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(timestampMs))
