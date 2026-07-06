package com.kkc.sheettracker.ui.admin

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.data.models.JobLabel
import com.kkc.sheettracker.ui.components.parseJobLabelColor

/**
 * Admin-only controls for toggling a job's labels and its Active/Pending Delivery placement.
 * Hosted as [AppBottomNavBar]'s `extendedControls` slot (see AppScaffold.kt) so it reuses the
 * nav bar's own frosted `Surface` + `hazeEffect` — already proven to render correctly — instead
 * of standing up a second, independent haze layer. The bar expands upward to show it the same
 * way it expands for the Hardwoods cut list or pen markup controls.
 */
@Composable
fun RowScope.JobLabelEditorNavBarControls(
    jobTitle: String,
    allLabels: List<JobLabel>,
    currentLabelIds: Set<Int>,
    isPendingDelivery: Boolean,
    onToggleLabel: (JobLabel) -> Unit,
    onSetPendingDelivery: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    IconButton(onClick = onDismiss) {
        Icon(Icons.Filled.Close, contentDescription = "Close label editor")
    }
    Text(
        text = jobTitle,
        style = MaterialTheme.typography.labelLarge,
        maxLines = 1,
        modifier = Modifier.widthIn(max = 150.dp)
    )
    if (allLabels.isEmpty()) {
        Text(
            "No labels defined yet.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    } else {
        allLabels.forEach { label ->
            val selected = label.id in currentLabelIds
            val labelColor = parseJobLabelColor(label.colorHex)
            FilterChip(
                selected = selected,
                onClick = { onToggleLabel(label) },
                label = { Text(label.name) },
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = labelColor.copy(alpha = 0.18f),
                    labelColor = labelColor,
                    selectedContainerColor = labelColor,
                    selectedLabelColor = Color.White
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = selected,
                    borderColor = labelColor,
                    selectedBorderColor = labelColor
                )
            )
        }
    }
    FilterChip(
        selected = isPendingDelivery,
        onClick = { onSetPendingDelivery(!isPendingDelivery) },
        label = { Text("Pending Delivery") },
        leadingIcon = if (isPendingDelivery) {
            { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
        } else null
    )
}
