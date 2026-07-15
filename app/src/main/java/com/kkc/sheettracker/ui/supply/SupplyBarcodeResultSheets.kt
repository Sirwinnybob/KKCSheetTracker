package com.kkc.sheettracker.ui.supply

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.data.models.ALL_SUPPLY_STATUSES
import com.kkc.sheettracker.data.models.SUPPLY_STATUS_PRIORITY
import com.kkc.sheettracker.data.models.SupplyItem
import com.kkc.sheettracker.ui.components.StatusChip
import com.kkc.sheettracker.ui.dashboard.getSoftStatusColors

/**
 * Shown when a known barcode is scanned from the header button.
 * Item name, category, current status, status chips, and View Item button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KnownBarcodeSheet(
    item: SupplyItem,
    categoryName: String,
    sheetState: SheetState,
    onStatusPick: (String) -> Unit,
    onViewItem: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(item.name, style = MaterialTheme.typography.headlineSmall,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(categoryName, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                val tier = SUPPLY_STATUS_PRIORITY[item.status.uppercase()] ?: 99
                val baseColor = supplyStatusColor(tier)
                val (chipBgColor, chipTextColor) = getSoftStatusColors(item.status, baseColor)
                StatusChip(
                    text = item.status,
                    backgroundColor = chipBgColor,
                    contentColor = chipTextColor
                )
            }
            HorizontalDivider()
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ALL_SUPPLY_STATUSES.forEach { status ->
                    val tier = SUPPLY_STATUS_PRIORITY[status.uppercase()] ?: 99
                    val baseColor = supplyStatusColor(tier)
                    val (chipBgColor, chipTextColor) = getSoftStatusColors(status, baseColor)
                    val isSelected = item.status == status

                    FilterChip(
                        selected = isSelected,
                        onClick = { onStatusPick(status) },
                        label = { Text(status, style = MaterialTheme.typography.labelSmall) },
                        border = null,
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = chipBgColor.copy(alpha = 0.5f),
                            labelColor = chipTextColor.copy(alpha = 0.9f),
                            selectedContainerColor = baseColor,
                            selectedLabelColor = Color.White
                        )
                    )
                }
            }
            HorizontalDivider()
            Button(onClick = onViewItem, modifier = Modifier.fillMaxWidth()) {
                Text("View Item")
            }
        }
    }
}

/**
 * Shown when an unknown barcode is scanned from the header button.
 * Offers Link to Existing or Add as New Item.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UnknownBarcodeSheet(
    barcode: String,
    sheetState: SheetState,
    onLinkToExisting: () -> Unit,
    onAddNewItem: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Unknown barcode", style = MaterialTheme.typography.titleMedium)
            Text(
                barcode,
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                maxLines = 2, overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            HorizontalDivider()
            Button(onClick = onLinkToExisting, modifier = Modifier.fillMaxWidth()) {
                Text("Link to Existing Item")
            }
            OutlinedButton(onClick = onAddNewItem, modifier = Modifier.fillMaxWidth()) {
                Text("Add as New Item")
            }
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    }
}
