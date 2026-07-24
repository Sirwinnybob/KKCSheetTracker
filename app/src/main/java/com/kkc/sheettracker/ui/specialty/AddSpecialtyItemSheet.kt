package com.kkc.sheettracker.ui.specialty

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.data.models.SpecialtyItem
import com.kkc.sheettracker.data.models.SpecialtyItemCategory
import com.kkc.sheettracker.data.models.SpecialtyStation
import com.kkc.sheettracker.data.models.TabletSpecialtyItem
import com.kkc.sheettracker.ui.components.ImmersiveDialogDecor
import com.kkc.sheettracker.ui.theme.KKCSpacing
import com.kkc.sheettracker.ui.theme.LocalKKCIsDarkTheme
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
    val isDark = LocalKKCIsDarkTheme.current

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

    val sheetBgColor = if (isDark) MaterialTheme.colorScheme.surfaceContainer else Color.White

    val inputFieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = MaterialTheme.colorScheme.primary,
        unfocusedBorderColor = if (isDark) MaterialTheme.colorScheme.outline.copy(alpha = 0.4f) else Color(0xFFCBD5E1),
        focusedContainerColor = if (isDark) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f) else Color.White,
        unfocusedContainerColor = if (isDark) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f) else Color.White
    )
    val fieldShape = RoundedCornerShape(10.dp)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = sheetBgColor
    ) {
        ImmersiveDialogDecor()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = KKCSpacing.sheetHorizontal)
                .padding(bottom = KKCSpacing.sheetBottomSafe)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Header with Icon & Subtitle Chip
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shadowElevation = 3.dp,
                    modifier = Modifier.size(44.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (existingItem == null) "Add Specialty Item" else "Edit Specialty Item",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (isDark) MaterialTheme.colorScheme.surfaceVariant else Color(0xFFF1F5F9),
                        modifier = Modifier.padding(top = 2.dp)
                    ) {
                        Text(
                            text = "Item ID: ${existingItem?.id ?: "New"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            // Card 1: Basic Details
            SectionCard(title = "Basic Information", icon = Icons.Default.Info) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Item Name *") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = fieldShape,
                    colors = inputFieldColors
                )

                Text(
                    text = "Category",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )

                // Custom Pill Selector for Category
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CategoryPill(
                        label = "CUSTOM",
                        selected = category == SpecialtyItemCategory.CUSTOM,
                        onClick = { category = SpecialtyItemCategory.CUSTOM },
                        modifier = Modifier.weight(1f)
                    )
                    CategoryPill(
                        label = "TO ORDER",
                        selected = category == SpecialtyItemCategory.TO_ORDER,
                        onClick = { category = SpecialtyItemCategory.TO_ORDER },
                        modifier = Modifier.weight(1f)
                    )
                }

                OutlinedTextField(
                    value = cabinetNumbersText,
                    onValueChange = { cabinetNumbersText = it },
                    label = { Text("Cabinet Numbers (comma-separated)") },
                    placeholder = { Text("e.g. 12, 13, 14") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = fieldShape,
                    colors = inputFieldColors
                )
            }

            // Card 2: Station Routing
            SectionCard(title = "Station Routing", icon = Icons.Default.Place) {
                Text(
                    text = "Select all manufacturing stations required for this line item",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                ) {
                    SPECIALTY_STATIONS.forEach { station ->
                        val selected = station in selectedStations
                        FilterChip(
                            selected = selected,
                            onClick = {
                                selectedStations = if (selected) selectedStations - station else selectedStations + station
                            },
                            label = {
                                Text(
                                    stationLabel(station),
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                                )
                            },
                            shape = RoundedCornerShape(20.dp),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                containerColor = if (isDark) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f) else Color.White
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = true,
                                selected = selected,
                                borderColor = if (isDark) MaterialTheme.colorScheme.outline.copy(alpha = 0.4f) else Color(0xFFCBD5E1),
                                selectedBorderColor = MaterialTheme.colorScheme.primary
                            ),
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

            // Card 3: Specifications (for CUSTOM)
            if (category == SpecialtyItemCategory.CUSTOM) {
                SectionCard(title = "Specifications", icon = Icons.Default.Straighten) {
                    OutlinedTextField(
                        value = dimensions,
                        onValueChange = { dimensions = it },
                        label = { Text("Dimensions") },
                        placeholder = { Text("e.g. 36 x 12 x 0.75") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = fieldShape,
                        colors = inputFieldColors
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedTextField(
                            value = quantityText,
                            onValueChange = { quantityText = it },
                            label = { Text("Quantity") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            shape = fieldShape,
                            colors = inputFieldColors
                        )
                        OutlinedTextField(
                            value = material,
                            onValueChange = { material = it },
                            label = { Text("Material") },
                            modifier = Modifier.weight(1.5f),
                            singleLine = true,
                            shape = fieldShape,
                            colors = inputFieldColors
                        )
                    }
                }
            }

            // Card 4: Order Details (for TO_ORDER)
            if (category == SpecialtyItemCategory.TO_ORDER) {
                SectionCard(title = "Order & Supply Details", icon = Icons.Default.ShoppingCart) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedTextField(
                            value = supplier,
                            onValueChange = { supplier = it },
                            label = { Text("Supplier") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            shape = fieldShape,
                            colors = inputFieldColors
                        )
                        OutlinedTextField(
                            value = modelNumber,
                            onValueChange = { modelNumber = it },
                            label = { Text("Model Number") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            shape = fieldShape,
                            colors = inputFieldColors
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedTextField(
                            value = orderDate,
                            onValueChange = { orderDate = it },
                            label = { Text("Order Date") },
                            placeholder = { Text("MM-DD-YYYY") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            shape = fieldShape,
                            colors = inputFieldColors
                        )
                        OutlinedTextField(
                            value = trackingNumber,
                            onValueChange = { trackingNumber = it },
                            label = { Text("Tracking Number") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            shape = fieldShape,
                            colors = inputFieldColors
                        )
                    }
                    OutlinedTextField(
                        value = orderUrl,
                        onValueChange = { orderUrl = it },
                        label = { Text("Order URL") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = fieldShape,
                        colors = inputFieldColors
                    )
                }
            }

            // Card 5: Notes
            SectionCard(title = "Notes & Instructions", icon = Icons.AutoMirrored.Filled.Notes) {
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Notes") },
                    placeholder = { Text("Add shop floor notes, special instructions, or details...") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 6,
                    shape = fieldShape,
                    colors = inputFieldColors
                )
            }

            // Action Bar with Elevated Surface & Shadow
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = if (isDark) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f) else Color(0xFFF1F5F9),
                border = BorderStroke(1.dp, if (isDark) MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f) else Color(0xFFE2E8F0)),
                shadowElevation = 6.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (existingItem != null && onDelete != null) {
                        OutlinedButton(
                            onClick = { showDeleteConfirm = true },
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            ),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f))
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Delete")
                        }
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    OutlinedButton(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Cancel")
                    }

                    Button(
                        onClick = { onSave(buildItem()) },
                        enabled = name.isNotBlank(),
                        shape = RoundedCornerShape(10.dp),
                        elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp),
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (existingItem == null) "Save Item" else "Save Changes")
                    }
                }
            }
        }
    }

    if (showDeleteConfirm && existingItem != null && onDelete != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete Specialty Item") },
            text = { Text("Are you sure you want to delete \"${existingItem.name}\"? This action will remove the item for all tablets.") },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirm = false
                        onDelete(existingItem.id)
                        onDismiss()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    shape = RoundedCornerShape(8.dp)
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

@Composable
private fun SectionCard(
    title: String,
    icon: ImageVector,
    content: @Composable ColumnScope.() -> Unit
) {
    val isDark = LocalKKCIsDarkTheme.current
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.outlinedCardColors(
            containerColor = if (isDark) MaterialTheme.colorScheme.surface else Color(0xFFF8FAFC)
        ),
        elevation = CardDefaults.outlinedCardElevation(
            defaultElevation = 4.dp
        ),
        border = BorderStroke(1.dp, if (isDark) MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f) else Color(0xFFE2E8F0))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            content()
        }
    }
}

@Composable
private fun CategoryPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isDark = LocalKKCIsDarkTheme.current
    val bgColor = if (selected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        if (isDark) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f) else Color.White
    }
    val contentColor = if (selected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val borderColor = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        if (isDark) MaterialTheme.colorScheme.outline.copy(alpha = 0.3f) else Color(0xFFCBD5E1)
    }

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = bgColor,
        border = BorderStroke(1.dp, borderColor),
        shadowElevation = if (selected) 2.dp else 1.dp,
        modifier = modifier.height(44.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (selected) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = contentColor,
                        modifier = Modifier.size(16.dp)
                    )
                }
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    color = contentColor
                )
            }
        }
    }
}

