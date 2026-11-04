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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.data.AdminModeController
import com.kkc.sheettracker.data.mixorder.ConsolidationResult
import com.kkc.sheettracker.ui.settings.CardBody
import com.kkc.sheettracker.ui.settings.GroupCard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Status line under the consolidate button; null means the Ready Jobs share was not reachable. */
internal fun consolidationMessage(result: ConsolidationResult?): String {
    if (result == null) return "Ready Jobs share unavailable"
    val merged = "Merged ${result.records} records from ${result.filesAbsorbed} files"
    return if (result.badMoved > 0) "$merged, ${result.badMoved} unreadable moved to bad" else merged
}

@Composable
internal fun AdminPane(
    adminMode: Boolean,
    onUnlockRequested: () -> Unit,
    /** Packs the shared mix-order learning files into one bundle; null when the Ready Jobs share is unavailable. */
    onConsolidateMixLearning: suspend () -> ConsolidationResult?
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val scope = rememberCoroutineScope()
    var consolidating by remember { mutableStateOf(false) }
    var consolidateMessage by remember { mutableStateOf<String?>(null) }
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
        if (adminMode) {
            GroupCard(caption = "Mix ordering") {
                CardBody {
                    Text(
                        "Packs every tablet's saved mix-order records on the Ready Jobs share into one file, so loading stays fast.",
                        style = MaterialTheme.typography.bodySmall,
                        color = muted
                    )
                    OutlinedButton(
                        onClick = {
                            consolidating = true
                            consolidateMessage = null
                            scope.launch {
                                consolidateMessage = try {
                                    consolidationMessage(withContext(Dispatchers.IO) { onConsolidateMixLearning() })
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    "Consolidation failed: ${e.message ?: e.javaClass.simpleName}"
                                } finally {
                                    consolidating = false
                                }
                            }
                        },
                        enabled = !consolidating,
                        shape = RoundedCornerShape(8.dp)
                    ) { Text("Consolidate mix learning data") }
                    consolidateMessage?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = muted)
                    }
                }
            }
        }
    }
}
