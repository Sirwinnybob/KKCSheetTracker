package com.kkc.sheettracker.ui.supply

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.data.SupplyRepository
import com.kkc.sheettracker.ui.components.headerGradientBrush
import com.kkc.sheettracker.data.models.ALL_SUPPLY_STATUSES
import com.kkc.sheettracker.data.models.SUPPLY_STATUS_PRIORITY
import com.kkc.sheettracker.data.models.SupplyCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SupplyItemEditScreen(
    itemId: String?,
    initialCategoryId: String?,
    basePath: String,
    tabletId: String,
    employeeName: String,
    onBack: () -> Unit,
    onSaved: (newItemId: String) -> Unit
) {
    val repository = remember(basePath) { SupplyRepository(basePath) }
    val coroutineScope = rememberCoroutineScope()

    var isLoading by remember { mutableStateOf(itemId != null) }
    var isSaving by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    var name by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var sku by remember { mutableStateOf("") }
    var quantity by remember { mutableStateOf("") }
    var vendorLink by remember { mutableStateOf("") }
    var trackingNumber by remember { mutableStateOf("") }
    var selectedCategoryId by remember { mutableStateOf(initialCategoryId ?: "") }
    var selectedStatus by remember { mutableStateOf("IN STOCK") }

    var categories by remember { mutableStateOf<List<SupplyCategory>>(emptyList()) }
    var showCategorySheet by remember { mutableStateOf(false) }
    var showStatusSheet by remember { mutableStateOf(false) }
    val categorySheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val statusSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LaunchedEffect(itemId) {
        val cats = withContext(Dispatchers.IO) { repository.getCategories() }
        categories = cats.sortedBy { it.position }
        if (itemId != null) {
            isLoading = true
            val item = withContext(Dispatchers.IO) { repository.getItem(itemId) }
            if (item != null) {
                name = item.name
                notes = item.notes ?: ""
                sku = item.fields["sku"] ?: ""
                quantity = item.fields["quantity"] ?: ""
                vendorLink = item.fields["vendorLink"] ?: ""
                trackingNumber = item.fields["trackingNumber"] ?: ""
                selectedCategoryId = item.categoryId
                selectedStatus = item.status
            }
            isLoading = false
        }
    }

    val selectedCategory = categories.find { it.id == selectedCategoryId }
    val statusTier = SUPPLY_STATUS_PRIORITY[selectedStatus] ?: 99
    val statusColor = supplyStatusColor(statusTier)

    fun save() {
        if (name.isBlank() || selectedCategoryId.isBlank()) return
        coroutineScope.launch {
            isSaving = true
            errorMessage = null
            try {
                val fields = buildMap {
                    if (sku.isNotBlank()) put("sku", sku.trim())
                    if (quantity.isNotBlank()) put("quantity", quantity.trim())
                    if (vendorLink.isNotBlank()) put("vendorLink", vendorLink.trim())
                    if (trackingNumber.isNotBlank()) put("trackingNumber", trackingNumber.trim())
                }
                val author = employeeName.ifBlank { "Floor" }
                val savedId = withContext(Dispatchers.IO) {
                    if (itemId == null) {
                        repository.createItem(
                            categoryId = selectedCategoryId,
                            name = name.trim(),
                            notes = notes.takeIf { it.isNotBlank() },
                            fields = fields,
                            status = selectedStatus,
                            tabletId = tabletId
                        ).id
                    } else {
                        repository.updateItem(itemId, name.trim(), selectedCategoryId, notes.takeIf { it.isNotBlank() }, fields)
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

    Scaffold(
        topBar = {
            TopAppBar(
                modifier = Modifier.background(headerGradientBrush()),
                title = {
                    Text(
                        if (itemId == null) "New Item" else "Edit Item",
                        style = MaterialTheme.typography.titleMedium
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(
                        onClick = { save() },
                        enabled = name.isNotBlank() && selectedCategoryId.isNotBlank() && !isSaving && !isLoading
                    ) {
                        if (isSaving) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Text("Save")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                ),
                windowInsets = WindowInsets.statusBars
            )
        }
    ) { padding ->
        if (isLoading) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator() }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Category selector
                OutlinedCard(
                    onClick = { showCategorySheet = true },
                    modifier = Modifier.fillMaxWidth()
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
                            selectedCategory?.name ?: "Select…",
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
                    shape = MaterialTheme.shapes.medium
                )

                // Status selector
                OutlinedCard(
                    onClick = { showStatusSheet = true },
                    modifier = Modifier.fillMaxWidth()
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
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .background(statusColor, CircleShape)
                            )
                            Text(
                                selectedStatus,
                                style = MaterialTheme.typography.bodyMedium
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
                    shape = MaterialTheme.shapes.medium
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                Text(
                    "Details",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = sku,
                    onValueChange = { sku = it },
                    label = { Text("SKU") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium
                )
                OutlinedTextField(
                    value = quantity,
                    onValueChange = { quantity = it },
                    label = { Text("Quantity") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium
                )
                OutlinedTextField(
                    value = vendorLink,
                    onValueChange = { vendorLink = it },
                    label = { Text("Vendor Link") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium
                )
                OutlinedTextField(
                    value = trackingNumber,
                    onValueChange = { trackingNumber = it },
                    label = { Text("Tracking #") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium
                )

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
    }

    // Category picker sheet
    if (showCategorySheet) {
        ModalBottomSheet(
            onDismissRequest = { showCategorySheet = false },
            sheetState = categorySheetState
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(bottom = 32.dp)) {
                Text(
                    "Select Category",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
                HorizontalDivider()
                categories.forEach { cat ->
                    NavigationDrawerItem(
                        label = { Text(cat.name) },
                        selected = cat.id == selectedCategoryId,
                        onClick = {
                            selectedCategoryId = cat.id
                            showCategorySheet = false
                        },
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                }
            }
        }
    }

    // Status picker sheet
    if (showStatusSheet) {
        ModalBottomSheet(
            onDismissRequest = { showStatusSheet = false },
            sheetState = statusSheetState
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(bottom = 32.dp)) {
                Text(
                    "Select Status",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
                HorizontalDivider()
                ALL_SUPPLY_STATUSES.forEach { status ->
                    val tier = SUPPLY_STATUS_PRIORITY[status] ?: 99
                    val color = supplyStatusColor(tier)
                    NavigationDrawerItem(
                        label = { Text(status) },
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
                        },
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                }
            }
        }
    }
}
