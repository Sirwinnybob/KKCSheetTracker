package com.kkc.sheettracker.ui.supply

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.data.models.SpecialtyItem
import com.kkc.sheettracker.ui.components.StatusChip
import com.kkc.sheettracker.ui.components.icons.SupplyOrderedOffIcon
import com.kkc.sheettracker.ui.components.icons.SupplyOrderedOnIcon
import com.kkc.sheettracker.ui.dashboard.getSoftStatusColors

/** Values the To Order editor hands back on Save. Blank text fields come back as null. */
internal data class ToOrderEdit(
    val name: String,
    val cabinetNumbers: List<String>,
    val supplier: String?,
    val model: String?,
    val orderDate: String?,
    val tracking: String?,
    val orderUrl: String?,
    val notes: String?,
    val quantity: Double?,
    val material: String?,
    val dimensions: String?,
    val ordered: Boolean
)

private val ToOrderFieldShape = RoundedCornerShape(4.dp)

/**
 * Editor for a cross-job To Order item, laid out like the supply board's "Edit Item" modal:
 * status-tinted header with Save in the top bar, a read-only Job row (the supply editor's
 * Category row), a tappable Status row, then grouped Details / Order fields.
 */
@Composable
internal fun ToOrderEditModal(
    item: SpecialtyItem,
    isOrdered: Boolean,
    jobLabel: String,
    onDismiss: () -> Unit,
    onSave: (ToOrderEdit) -> Unit
) {
    var name by remember { mutableStateOf(item.name) }
    var cabinetNumbersStr by remember { mutableStateOf(item.cabinetNumbers.joinToString(", ")) }
    var supplier by remember { mutableStateOf(item.supplier.orEmpty()) }
    var model by remember { mutableStateOf(item.model.orEmpty()) }
    var orderDate by remember { mutableStateOf(item.orderDate.orEmpty()) }
    var tracking by remember { mutableStateOf(item.tracking.orEmpty()) }
    var orderUrl by remember { mutableStateOf(item.orderUrl.orEmpty()) }
    var notes by remember { mutableStateOf(item.notes.orEmpty()) }
    var quantityStr by remember {
        mutableStateOf(item.quantity?.let { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() }.orEmpty())
    }
    var material by remember { mutableStateOf(item.material.orEmpty()) }
    var dimensions by remember { mutableStateOf(item.dimensions.orEmpty()) }
    var ordered by remember { mutableStateOf(isOrdered) }
    var isSaving by remember { mutableStateOf(false) }

    val statusLabel = toOrderStatusLabel(ordered)
    val statusColor = supplyStatusColor(if (ordered) 7 else 6)
    val canSave = name.isNotBlank() && !isSaving

    fun save() {
        if (!canSave) return
        isSaving = true
        onSave(
            ToOrderEdit(
                name = name.trim(),
                cabinetNumbers = cabinetNumbersStr.split(",").map { it.trim() }.filter { it.isNotBlank() },
                supplier = supplier.trim().ifBlank { null },
                model = model.trim().ifBlank { null },
                orderDate = orderDate.trim().ifBlank { null },
                tracking = tracking.trim().ifBlank { null },
                orderUrl = orderUrl.trim().ifBlank { null },
                notes = notes.trim().ifBlank { null },
                quantity = quantityStr.trim().toDoubleOrNull(),
                material = material.trim().ifBlank { null },
                dimensions = dimensions.trim().ifBlank { null },
                ordered = ordered
            )
        )
    }

    SupplyModalFrame(
        title = "Edit Item",
        onDismiss = onDismiss,
        headerTint = statusColor,
        wrapContentHeight = true,
        actions = {
            TextButton(onClick = ::save, enabled = canSave) {
                if (isSaving) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Text("Save")
                }
            }
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(9.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Job",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        jobLabel,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = androidx.compose.ui.text.style.TextAlign.End,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Item Name *") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                isError = name.isBlank(),
                shape = ToOrderFieldShape
            )

            OutlinedCard(
                onClick = {
                    ordered = !ordered
                    // Same rule as checking an item off on the page: first time ordered stamps today.
                    if (ordered && orderDate.isBlank()) {
                        orderDate = defaultToOrderDate(java.time.LocalDate.now())
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(9.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Status",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val (chipBgColor, chipTextColor) = getSoftStatusColors(statusLabel, statusColor)
                        StatusChip(
                            text = statusLabel,
                            backgroundColor = chipBgColor,
                            contentColor = chipTextColor,
                            modifier = Modifier.border(
                                BorderStroke(0.5.dp, chipTextColor.copy(alpha = 0.25f)),
                                shape = CircleShape
                            )
                        )
                        Icon(
                            imageVector = if (ordered) SupplyOrderedOnIcon else SupplyOrderedOffIcon,
                            contentDescription = if (ordered) "Mark not ordered" else "Mark ordered",
                            tint = statusColor
                        )
                    }
                }
            }

            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                label = { Text("Notes") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                maxLines = 8,
                shape = ToOrderFieldShape
            )

            ToOrderSectionHeader("Details")

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ToOrderField(
                    value = cabinetNumbersStr,
                    onValueChange = { cabinetNumbersStr = it },
                    label = "Cabinet Numbers",
                    placeholder = "e.g. 1, 2, 3",
                    modifier = Modifier.weight(1f)
                )
                ToOrderField(
                    value = quantityStr,
                    onValueChange = { quantityStr = it },
                    label = "Quantity",
                    keyboardType = KeyboardType.Decimal,
                    modifier = Modifier.weight(1f)
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ToOrderField(
                    value = material,
                    onValueChange = { material = it },
                    label = "Material",
                    modifier = Modifier.weight(1f)
                )
                ToOrderField(
                    value = dimensions,
                    onValueChange = { dimensions = it },
                    label = "Dimensions",
                    modifier = Modifier.weight(1f)
                )
            }

            ToOrderSectionHeader("Order")

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ToOrderField(
                    value = supplier,
                    onValueChange = { supplier = it },
                    label = "Supplier",
                    modifier = Modifier.weight(1f)
                )
                ToOrderField(
                    value = model,
                    onValueChange = { model = it },
                    label = "Model Number",
                    modifier = Modifier.weight(1f)
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ToOrderField(
                    value = orderDate,
                    onValueChange = { orderDate = it },
                    label = "Order Date",
                    placeholder = "MM-DD",
                    modifier = Modifier.weight(1f)
                )
                ToOrderField(
                    value = tracking,
                    onValueChange = { tracking = it },
                    label = "Tracking Number",
                    modifier = Modifier.weight(1f)
                )
            }
            ToOrderField(
                value = orderUrl,
                onValueChange = { orderUrl = it },
                label = "Order URL",
                keyboardType = KeyboardType.Uri,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ToOrderSectionHeader(title: String) {
    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun ToOrderField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        modifier = modifier,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        shape = ToOrderFieldShape
    )
}
