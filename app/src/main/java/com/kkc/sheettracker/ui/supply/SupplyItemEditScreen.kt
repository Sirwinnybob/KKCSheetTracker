package com.kkc.sheettracker.ui.supply

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.ui.components.StatusChip
import com.kkc.sheettracker.ui.dashboard.getSoftStatusColors
import com.kkc.sheettracker.data.SupplyRepository
import com.kkc.sheettracker.data.models.ALL_SUPPLY_STATUSES
import com.kkc.sheettracker.data.models.SUPPLY_STATUS_PRIORITY
import com.kkc.sheettracker.data.models.SupplyCategory
import com.kkc.sheettracker.data.models.SupplySchemaField
import com.kkc.sheettracker.data.routeSupplyFieldValues
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SupplyItemEditorChromeState(
    val canSave: Boolean,
    val isSaving: Boolean,
    val status: String?,
    val onSave: () -> Unit
)

@Composable
fun SupplyItemEditScreen(
    itemId: String?,
    initialCategoryId: String?,
    basePath: String,
    tabletId: String,
    employeeName: String,
    onBack: () -> Unit,
    onSaved: (newItemId: String) -> Unit,
    onChromeStateChanged: (SupplyItemEditorChromeState) -> Unit = {}
) {
    val repository = remember(basePath) { SupplyRepository(basePath) }
    val coroutineScope = rememberCoroutineScope()

    var isLoading by remember { mutableStateOf(itemId != null) }
    var isSaving by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    var name by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var schema by remember { mutableStateOf<List<SupplySchemaField>>(emptyList()) }
    val fieldValues = remember { mutableStateMapOf<String, String>() }
    var existingFields by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var existingCustomFields by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var selectedCategoryId by remember { mutableStateOf(initialCategoryId ?: "") }
    var selectedStatus by remember { mutableStateOf("IN STOCK") }

    var categories by remember { mutableStateOf<List<SupplyCategory>>(emptyList()) }
    var showCategorySheet by remember { mutableStateOf(false) }
    var showStatusSheet by remember { mutableStateOf(false) }

    LaunchedEffect(itemId, initialCategoryId) {
        val cats = withContext(Dispatchers.IO) { repository.getCategories() }
        categories = cats.sortedBy { it.position }
        val loadedSchema = withContext(Dispatchers.IO) { repository.schemaOrDefault() }
        schema = loadedSchema
        if (itemId != null) {
            isLoading = true
            val item = withContext(Dispatchers.IO) { repository.getItem(itemId) }
            if (item != null) {
                name = item.name
                notes = item.notes ?: ""
                existingFields = item.fields
                existingCustomFields = item.customFields
                val seed = item.fields + item.customFields
                fieldValues.clear()
                loadedSchema.forEach { field -> fieldValues[field.key] = seed[field.key] ?: "" }
                selectedCategoryId = item.categoryId
                selectedStatus = item.status
            }
            isLoading = false
        } else {
            selectedCategoryId = initialCategoryId ?: ""
            fieldValues.clear()
            loadedSchema.forEach { field -> fieldValues[field.key] = "" }
        }
    }

    val selectedCategory = categories.find { it.id == selectedCategoryId }
    val statusTier = SUPPLY_STATUS_PRIORITY[selectedStatus] ?: 99
    val statusColor = supplyStatusColor(statusTier)
    val canSave = name.isNotBlank() && selectedCategoryId.isNotBlank() && !isSaving && !isLoading

    fun save() {
        if (name.isBlank() || selectedCategoryId.isBlank()) return
        coroutineScope.launch {
            isSaving = true
            errorMessage = null
            try {
                val routed = routeSupplyFieldValues(
                    schema = schema,
                    editedValues = fieldValues,
                    existingFields = existingFields,
                    existingCustomFields = existingCustomFields
                )
                val author = employeeName.ifBlank { "Floor" }
                val savedId = withContext(Dispatchers.IO) {
                    if (itemId == null) {
                        repository.createItem(
                            categoryId = selectedCategoryId,
                            name = name.trim(),
                            notes = notes.takeIf { it.isNotBlank() },
                            fields = routed.fields,
                            customFields = routed.customFields,
                            status = selectedStatus,
                            tabletId = tabletId
                        ).id
                    } else {
                        repository.updateItem(
                            itemId, name.trim(), selectedCategoryId,
                            notes.takeIf { it.isNotBlank() },
                            routed.fields, routed.customFields
                        )
                        repository.setStatus(itemId, selectedStatus, author, tabletId)
                        itemId
                    }
                }
                onSaved(savedId)
            } catch (e: Exception) {
                errorMessage = e.message ?: "Failed to save"
                isSaving = false
            }
        }
    }

    SideEffect {
        onChromeStateChanged(
            SupplyItemEditorChromeState(
                canSave = canSave,
                isSaving = isSaving,
                status = selectedStatus.takeIf { it.isNotBlank() },
                onSave = ::save
            )
        )
    }

    if (isLoading) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) { CircularProgressIndicator() }
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedCard(
                onClick = { showCategorySheet = true },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(9.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Category",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        selectedCategory?.name ?: "Select...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (selectedCategory != null) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant
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
                shape = RoundedCornerShape(4.dp)
            )

            OutlinedCard(
                onClick = { showStatusSheet = true },
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
                    val (chipBgColor, chipTextColor) = getSoftStatusColors(selectedStatus, statusColor)
                    StatusChip(
                        text = selectedStatus,
                        backgroundColor = chipBgColor,
                        contentColor = chipTextColor,
                        modifier = Modifier.border(
                            BorderStroke(0.5.dp, chipTextColor.copy(alpha = 0.25f)),
                            shape = CircleShape
                        )
                    )
                }
            }

             OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                label = { Text("Notes") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                maxLines = 8,
                shape = RoundedCornerShape(4.dp)
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            Text(
                "Details",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            schema.forEach { field ->
                SupplyFieldInput(
                    field = field,
                    value = fieldValues[field.key] ?: "",
                    onValueChange = { fieldValues[field.key] = it }
                )
            }

            if (errorMessage != null) {
                Text(
                    errorMessage!!,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    if (showCategorySheet) {
        SupplyPickerDialog(
            title = "Select Category",
            options = categories.map { cat ->
                SupplyPickerOption(
                    id = cat.id,
                    label = cat.name,
                    selected = cat.id == selectedCategoryId,
                    onClick = {
                        selectedCategoryId = cat.id
                        showCategorySheet = false
                    }
                )
            },
            onDismiss = { showCategorySheet = false }
        )
    }

    if (showStatusSheet) {
        SupplyPickerDialog(
            title = "Select Status",
            options = ALL_SUPPLY_STATUSES.map { status ->
                val tier = SUPPLY_STATUS_PRIORITY[status] ?: 99
                val color = supplyStatusColor(tier)
                SupplyPickerOption(
                    id = status,
                    label = status,
                    selected = selectedStatus == status,
                    onClick = {
                        selectedStatus = status
                        showStatusSheet = false
                    },
                    icon = {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .background(color, CircleShape)
                        )
                    }
                )
            },
            onDismiss = { showStatusSheet = false },
            headerTint = supplyStatusHeaderTint(selectedStatus)
        )
    }
}
