package com.kkc.sheettracker.ui.specialty

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.data.models.SpecialtyItemCategory
import com.kkc.sheettracker.data.models.SpecialtyStation
import com.kkc.sheettracker.data.models.TabletSpecialtyItem
import java.time.Instant
import java.util.UUID

private val SPECIALTY_STATIONS = SpecialtyStation.entries

private fun stationLabel(station: SpecialtyStation) = when (station) {
    SpecialtyStation.CNC -> "CNC"
    SpecialtyStation.SAW -> "SAW"
    SpecialtyStation.EDGE_BANDER -> "Edge Bander"
    SpecialtyStation.ASSEMBLY -> "Assembly"
    SpecialtyStation.HARDWOODS -> "Hardwoods"
    SpecialtyStation.SPECIALTY -> "Specialty"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddSpecialtyItemSheet(
    existingItem: TabletSpecialtyItem?,   // null = create new
    tabletId: String,
    onDismiss: () -> Unit,
    onSave: (TabletSpecialtyItem) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var name by remember(existingItem?.id) { mutableStateOf(existingItem?.name ?: "") }
    var category by remember(existingItem?.id) { mutableStateOf(existingItem?.category ?: SpecialtyItemCategory.CUSTOM) }
    var cabinetNumbersText by remember(existingItem?.id) { mutableStateOf(existingItem?.cabinetNumbers?.joinToString(", ") ?: "") }
    var selectedStations by remember(existingItem?.id) { mutableStateOf(existingItem?.stations?.toSet() ?: emptySet<SpecialtyStation>()) }
    var notes by remember(existingItem?.id) { mutableStateOf(existingItem?.notes ?: "") }
    // CUSTOM fields
    var dimensions by remember(existingItem?.id) { mutableStateOf(existingItem?.dimensions ?: "") }
    var quantityText by remember(existingItem?.id) { mutableStateOf(existingItem?.quantity?.toString() ?: "") }
    var material by remember(existingItem?.id) { mutableStateOf(existingItem?.material ?: "") }
    // TO_ORDER fields
    var supplier by remember(existingItem?.id) { mutableStateOf(existingItem?.supplier ?: "") }
    var modelNumber by remember(existingItem?.id) { mutableStateOf(existingItem?.modelNumber ?: "") }
    var orderDate by remember(existingItem?.id) { mutableStateOf(existingItem?.orderDate ?: "") }
    var trackingNumber by remember(existingItem?.id) { mutableStateOf(existingItem?.trackingNumber ?: "") }
    var orderUrl by remember(existingItem?.id) { mutableStateOf(existingItem?.orderUrl ?: "") }

    fun buildItem(): TabletSpecialtyItem {
        val rawId = existingItem?.id ?: UUID.randomUUID().toString()
        val cabs = cabinetNumbersText.split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
        return TabletSpecialtyItem(
            id = rawId,
            name = name.trim(),
            category = category,
            cabinetNumbers = cabs,
            stations = selectedStations.toList(),
            notes = notes.trim().takeIf { it.isNotBlank() },
            dimensions = dimensions.trim().takeIf { it.isNotBlank() },
            quantity = quantityText.trim().toIntOrNull(),
            material = material.trim().takeIf { it.isNotBlank() },
            supplier = supplier.trim().takeIf { it.isNotBlank() },
            modelNumber = modelNumber.trim().takeIf { it.isNotBlank() },
            orderDate = orderDate.trim().takeIf { it.isNotBlank() },
            trackingNumber = trackingNumber.trim().takeIf { it.isNotBlank() },
            orderUrl = orderUrl.trim().takeIf { it.isNotBlank() },
            createdAt = existingItem?.createdAt ?: Instant.now().toString(),
            createdByDevice = tabletId
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = if (existingItem == null) "Add Item" else "Edit Item",
                style = MaterialTheme.typography.titleMedium
            )

            // ── Category ─────────────────────────────────────────────────────────
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

            // ── Common fields ─────────────────────────────────────────────────────
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name *") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            OutlinedTextField(
                value = cabinetNumbersText,
                onValueChange = { cabinetNumbersText = it },
                label = { Text("Cabinet Numbers (comma-separated)") },
                placeholder = { Text("e.g. 12, 13, 14") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            // Stations checkboxes
            Text("Stations", style = MaterialTheme.typography.labelLarge)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                SPECIALTY_STATIONS.forEach { station ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Checkbox(
                            checked = station in selectedStations,
                            onCheckedChange = { checked ->
                                selectedStations = if (checked) selectedStations + station
                                else selectedStations - station
                            }
                        )
                        Text(stationLabel(station), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            // ── CUSTOM-only fields ────────────────────────────────────────────────
            if (category == SpecialtyItemCategory.CUSTOM) {
                OutlinedTextField(
                    value = dimensions,
                    onValueChange = { dimensions = it },
                    label = { Text("Dimensions") },
                    placeholder = { Text("e.g. 36x12x0.75") },
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

            // ── TO_ORDER-only fields ──────────────────────────────────────────────
            if (category == SpecialtyItemCategory.TO_ORDER) {
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
                    label = { Text("Order Date (YYYY-MM-DD)") },
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

            // ── Notes ─────────────────────────────────────────────────────────────
            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                label = { Text("Notes") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2
            )

            // ── Action row ────────────────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
            ) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Button(
                    onClick = { onSave(buildItem()) },
                    enabled = name.isNotBlank()
                ) { Text("Save") }
            }
        }
    }
}
