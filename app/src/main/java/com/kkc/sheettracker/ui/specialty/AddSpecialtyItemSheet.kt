package com.kkc.sheettracker.ui.specialty

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.data.models.SpecialtyItem
import com.kkc.sheettracker.data.models.SpecialtyItemCategory
import com.kkc.sheettracker.data.models.SpecialtyStation
import com.kkc.sheettracker.data.models.TabletSpecialtyItem
import com.kkc.sheettracker.ui.components.ImmersiveDialogDecor
import com.kkc.sheettracker.ui.theme.KKCSpacing
import java.time.Instant
import java.util.UUID

private val SPECIALTY_STATIONS = listOf(
    SpecialtyStation.SAW,
    SpecialtyStation.EDGE_BANDER,
    SpecialtyStation.ASSEMBLY,
    SpecialtyStation.CNC,
    SpecialtyStation.HARDWOODS,
    SpecialtyStation.SPECIALTY,
    SpecialtyStation.DELIVERY
)

private fun stationLabel(station: SpecialtyStation) = when (station) {
    SpecialtyStation.SAW -> "Saw"
    SpecialtyStation.EDGE_BANDER -> "Edge Bander"
    SpecialtyStation.ASSEMBLY -> "Assembly"
    SpecialtyStation.CNC -> "CNC"
    SpecialtyStation.HARDWOODS -> "Hardwoods"
    SpecialtyStation.SPECIALTY -> "Specialty"
    SpecialtyStation.DELIVERY -> "Delivery"
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AddSpecialtyItemSheet(
    existingItem: SpecialtyItem? = null,
    tabletId: String,
    onDismiss: () -> Unit,
    onSave: (TabletSpecialtyItem) -> Unit,
    onDelete: ((String) -> Unit)? = null
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var name by remember(existingItem?.id) { mutableStateOf(existingItem?.name ?: "") }
    var category by remember(existingItem?.id) { mutableStateOf(existingItem?.category ?: SpecialtyItemCategory.CUSTOM) }
    var cabinetNumbersText by remember(existingItem?.id) { mutableStateOf(existingItem?.cabinetNumbers?.joinToString(", ") ?: "") }
    var selectedStations by remember(existingItem?.id) { mutableStateOf(existingItem?.stations?.toSet() ?: emptySet()) }
    var notes by remember(existingItem?.id) { mutableStateOf(existingItem?.notes ?: "") }

    // CUSTOM fields
    var dimensions by remember(existingItem?.id) { mutableStateOf(existingItem?.dimensions ?: "") }
    var quantityText by remember(existingItem?.id) { mutableStateOf(existingItem?.quantity?.let { if (it % 1.0 == 0.0) it.toLong().toString() else it.toString() } ?: "") }
    var material by remember(existingItem?.id) { mutableStateOf(existingItem?.material ?: "") }

    // TO_ORDER fields
    var supplier by remember(existingItem?.id) { mutableStateOf(existingItem?.supplier ?: "") }
    var modelNumber by remember(existingItem?.id) { mutableStateOf(existingItem?.model ?: "") }
    var orderDate by remember(existingItem?.id) { mutableStateOf(existingItem?.orderDate ?: "") }
    var trackingNumber by remember(existingItem?.id) { mutableStateOf(existingItem?.tracking ?: "") }
    var orderUrl by remember(existingItem?.id) { mutableStateOf(existingItem?.orderUrl ?: "") }

    var showDeleteConfirm by remember { mutableStateOf(false) }

    fun buildItem(): TabletSpecialtyItem {
        val rawId = existingItem?.id ?: UUID.randomUUID().toString()
        val cleanId = rawId.removePrefix("checklist:")
            .removePrefix("admin:")
            .removePrefix("tablet:")
        val cabs = cabinetNumbersText.split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
        return TabletSpecialtyItem(
            id = cleanId,
            name = name.trim(),
            category = category,
            cabinetNumbers = cabs,
            stations = selectedStations.toList(),
            notes = notes.trim().takeIf { it.isNotBlank() },
            dimensions = dimensions.trim().takeIf { it.isNotBlank() },
            quantity = quantityText.trim().toDoubleOrNull(),
            material = material.trim().takeIf { it.isNotBlank() },
            supplier = supplier.trim().takeIf { it.isNotBlank() },
            modelNumber = modelNumber.trim().takeIf { it.isNotBlank() },
            orderDate = orderDate.trim().takeIf { it.isNotBlank() },
            trackingNumber = trackingNumber.trim().takeIf { it.isNotBlank() },
            orderUrl = orderUrl.trim().takeIf { it.isNotBlank() },
            createdAt = existingItem?.createdAt ?: Instant.now().toString(),
            createdByDevice = existingItem?.createdBy ?: tabletId
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        ImmersiveDialogDecor()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = KKCSpacing.sheetHorizontal)
                .padding(bottom = KKCSpacing.sheetBottomSafe)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(KKCSpacing.sheetItemSpacing)
        ) {
            // Header
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = if (existingItem == null) "Add Specialty Item" else "Edit Specialty Item",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "ID: ${existingItem?.id ?: "New"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Card 1: Basic Details
            Surface(
                shape = MaterialTheme.shapes.medium,
                tonalElevation = 1.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Basic Details",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Item Name *") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        SegmentedButton(
                            selected = category == SpecialtyItemCategory.CUSTOM,
                            onClick = { category = SpecialtyItemCategory.CUSTOM },
                            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                            label = { Text("CUSTOM") }
                        )
                        SegmentedButton(
                            selected = category == SpecialtyItemCategory.TO_ORDER,
                            onClick = { category = SpecialtyItemCategory.TO_ORDER },
                            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                            label = { Text("TO ORDER") }
                        )
                    }
                    OutlinedTextField(
                        value = cabinetNumbersText,
                        onValueChange = { cabinetNumbersText = it },
                        label = { Text("Cabinet Numbers (comma-separated)") },
                        placeholder = { Text("e.g. 12, 13, 14") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
            }

            // Card 2: Station Routing
            Surface(
                shape = MaterialTheme.shapes.medium,
                tonalElevation = 1.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Station Routing",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        SPECIALTY_STATIONS.forEach { station ->
                            val selected = station in selectedStations
                            FilterChip(
                                selected = selected,
                                onClick = {
                                    selectedStations = if (selected) selectedStations - station else selectedStations + station
                                },
                                label = { Text(stationLabel(station)) },
                                leadingIcon = if (selected) {
                                    {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = null,
                                            modifier = Modifier.size(FilterChipDefaults.IconSize)
                                        )
                                    }
                                } else null
                            )
                        }
                    }
                }
            }

            // Card 3: Specifications (for CUSTOM)
            if (category == SpecialtyItemCategory.CUSTOM) {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    tonalElevation = 1.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "Specifications",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        OutlinedTextField(
                            value = dimensions,
                            onValueChange = { dimensions = it },
                            label = { Text("Dimensions") },
                            placeholder = { Text("e.g. 36 x 12 x 0.75") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = quantityText,
                            onValueChange = { quantityText = it },
                            label = { Text("Quantity") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                        OutlinedTextField(
                            value = material,
                            onValueChange = { material = it },
                            label = { Text("Material") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    }
                }
            }

            // Card 4: Order Details (for TO_ORDER)
            if (category == SpecialtyItemCategory.TO_ORDER) {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    tonalElevation = 1.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "Order Details",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        OutlinedTextField(
                            value = supplier,
                            onValueChange = { supplier = it },
                            label = { Text("Supplier") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = modelNumber,
                            onValueChange = { modelNumber = it },
                            label = { Text("Model Number") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = orderDate,
                            onValueChange = { orderDate = it },
                            label = { Text("Order Date") },
                            placeholder = { Text("e.g. MM-DD-YYYY") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = trackingNumber,
                            onValueChange = { trackingNumber = it },
                            label = { Text("Tracking Number") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = orderUrl,
                            onValueChange = { orderUrl = it },
                            label = { Text("Order URL") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    }
                }
            }

            // Card 5: Notes
            Surface(
                shape = MaterialTheme.shapes.medium,
                tonalElevation = 1.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Notes",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    OutlinedTextField(
                        value = notes,
                        onValueChange = { notes = it },
                        label = { Text("Notes") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        maxLines = 5
                    )
                }
            }

            // Action Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(KKCSpacing.inCardSpacing)
            ) {
                if (existingItem != null && onDelete != null) {
                    OutlinedButton(
                        onClick = { showDeleteConfirm = true },
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.size(6.dp))
                        Text("Delete Item")
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }

                Button(
                    onClick = { onSave(buildItem()) },
                    enabled = name.isNotBlank()
                ) {
                    Text(if (existingItem == null) "Save Item" else "Save Changes")
                }
            }
        }
    }

    if (showDeleteConfirm && existingItem != null && onDelete != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete Specialty Item") },
            text = { Text("Are you sure you want to delete this item? This action cannot be undone.") },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirm = false
                        onDelete(existingItem.id)
                        onDismiss()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

