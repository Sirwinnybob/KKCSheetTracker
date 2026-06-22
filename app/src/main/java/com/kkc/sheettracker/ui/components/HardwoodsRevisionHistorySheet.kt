package com.kkc.sheettracker.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.data.models.HardwoodRevisionEntry
import com.kkc.sheettracker.data.models.HardwoodRevisionHistory
import com.kkc.sheettracker.data.models.HardwoodRevisionModifiedEntry
import com.kkc.sheettracker.data.models.HardwoodRevisionRowSnapshot
import com.kkc.sheettracker.data.models.HardwoodRowRevisionState

@Composable
fun RevisionBadge(
    state: HardwoodRowRevisionState,
    isChangedPendingRecut: Boolean,
    modifier: Modifier = Modifier
) {
    val badgeColor = if (isChangedPendingRecut) {
        MaterialTheme.colorScheme.tertiary
    } else {
        MaterialTheme.colorScheme.secondary
    }
    Surface(
        color = badgeColor.copy(alpha = 0.14f),
        shape = RoundedCornerShape(999.dp),
        modifier = modifier
    ) {
        Text(
            text = "R${state.latestRevision}",
            style = MaterialTheme.typography.labelSmall,
            color = badgeColor,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

@Composable
fun ChangedBadge(modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.16f),
        shape = RoundedCornerShape(999.dp),
        modifier = modifier
    ) {
        Text(
            text = "CHANGED",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.tertiary,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun HardwoodsRevisionHistorySheet(
    jobFolderName: String,
    history: HardwoodRevisionHistory?,
    onOpenRow: ((docType: String, rowId: String) -> Unit)? = null,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        ImmersiveDialogDecor()
        val revisions = history?.revisions.orEmpty().sortedByDescending { it.revision }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Text(
                text = "$jobFolderName History",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "Current revision: R${history?.currentRevision ?: 0}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            if (revisions.isEmpty()) {
                Text(
                    text = "No revision history yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(420.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(revisions, key = { it.revision }) { entry ->
                        RevisionEntryCard(
                            entry = entry,
                            onOpenRow = onOpenRow
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.align(androidx.compose.ui.Alignment.End)
            ) {
                Text("Close")
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun RevisionEntryCard(
    entry: HardwoodRevisionEntry,
    onOpenRow: ((docType: String, rowId: String) -> Unit)?
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "R${entry.revision} • ${entry.kind}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = entry.timestamp.orEmpty(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 12.dp)
                )
            }
            if (entry.added.isEmpty() && entry.removed.isEmpty() && entry.modified.isEmpty()) {
                Text(
                    text = "Snapshot baseline.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                entry.added.forEach { row ->
                    Text(
                        "NEW • ${rowSummary(row)}",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.then(
                            if (onOpenRow != null && row.docType.isNotBlank() && row.rowId.isNotBlank()) {
                                Modifier.clickable { onOpenRow(row.docType, row.rowId) }
                            } else {
                                Modifier
                            }
                        )
                    )
                }
                entry.removed.forEach { row ->
                    Text(
                        "REMOVED • ${rowSummary(row)}",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.then(
                            if (onOpenRow != null && row.docType.isNotBlank() && row.rowId.isNotBlank()) {
                                Modifier.clickable { onOpenRow(row.docType, row.rowId) }
                            } else {
                                Modifier
                            }
                        )
                    )
                }
                entry.modified.forEach { mod ->
                    val target = mod.after.takeIf { it.docType.isNotBlank() && it.rowId.isNotBlank() } ?: mod.before
                    Text(
                        "MODIFIED • ${modifiedSummary(mod)}",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.then(
                            if (onOpenRow != null && target.docType.isNotBlank() && target.rowId.isNotBlank()) {
                                Modifier.clickable { onOpenRow(target.docType, target.rowId) }
                            } else {
                                Modifier
                            }
                        )
                    )
                }
            }
        }
    }
}

private fun rowSummary(row: HardwoodRevisionRowSnapshot): String {
    val dims = "${row.qty}x ${row.width} x ${row.length}"
    val desc = row.description.ifBlank { "Part" }
    val doc = row.docType.replace("_", " ")
    return "$doc • $desc • $dims"
}

private fun modifiedSummary(mod: HardwoodRevisionModifiedEntry): String {
    val desc = mod.after.description.ifBlank { mod.before.description.ifBlank { "Part" } }
    val beforeDims = "${mod.before.qty}x ${mod.before.width} x ${mod.before.length}"
    val afterDims = "${mod.after.qty}x ${mod.after.width} x ${mod.after.length}"
    val changed = mod.changedFields.joinToString(", ").ifBlank { "qty/width/length" }
    return "$desc • $beforeDims -> $afterDims ($changed)"
}
