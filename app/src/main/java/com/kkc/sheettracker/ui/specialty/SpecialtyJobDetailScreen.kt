package com.kkc.sheettracker.ui.specialty

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.TextButton
import androidx.compose.material3.Checkbox
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import com.kkc.sheettracker.data.SpecialtyStateStore
import com.kkc.sheettracker.data.completionKeysForItem
import com.kkc.sheettracker.data.models.ReferenceDocType
import com.kkc.sheettracker.data.models.SpecialtyResolvedItem
import com.kkc.sheettracker.data.models.SpecialtyStation
import com.kkc.sheettracker.data.models.StatusCounts
import com.kkc.sheettracker.ui.components.ProgressCard
import com.kkc.sheettracker.ui.components.StatusChip
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpecialtyJobDetailScreen(
    jobFolderName: String,
    specialtyStateStore: SpecialtyStateStore,
    hasAssemblySheet: Boolean,
    hasPlansElevations: Boolean,
    hasDeliverySheet: Boolean,
    hasThreeDAssets: Boolean,
    onOpenReferenceDocument: (ReferenceDocType, Int) -> Unit,
    onOpenThreeD: () -> Unit,
    onOpenDoorPanels: () -> Unit,
    onOpenSplitView: () -> Unit,
    onJumpToCabinet: ((String) -> Unit)? = null,
    onBack: () -> Unit
) {
    val scanState by specialtyStateStore.scanState.collectAsState()
    val progressVersion by specialtyStateStore.progressVersion.collectAsState()
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val completionOverrides = remember(jobFolderName) { mutableStateMapOf<String, Boolean>() }
    val inFlightUpdates = remember(jobFolderName) { mutableStateMapOf<String, Boolean>() }
    var toggleErrorMessage by remember(jobFolderName) { mutableStateOf<String?>(null) }

    val resolvedItems = remember(scanState.snapshot.generation, progressVersion, jobFolderName) {
        specialtyStateStore.getResolvedItems(jobFolderName)
    }
    val completedItems = resolvedItems.count { resolved ->
        isChecklistItemComplete(resolved, completionOverrides)
    }
    val totalItems = resolvedItems.size

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(jobFolderName) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            state = listState,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(key = "summary") {
                Text(
                    text = if (totalItems == 0 && scanState.status != com.kkc.sheettracker.data.models.ScanStatus.READY) {
                        "Specialty checklist details are loading."
                    } else {
                        "$completedItems / $totalItems items complete"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            item(key = "actions-reference") {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (hasAssemblySheet) {
                        Button(onClick = { onOpenReferenceDocument(ReferenceDocType.ASSEMBLY, 1) }) {
                            Text("View Assembly")
                        }
                    }
                    if (hasPlansElevations) {
                        Button(onClick = { onOpenReferenceDocument(ReferenceDocType.PLANS_ELEVATIONS, 1) }) {
                            Text("View Plans & Elevations")
                        }
                    }
                    if (hasDeliverySheet) {
                        Button(onClick = { onOpenReferenceDocument(ReferenceDocType.DELIVERY_SHEETS, 1) }) {
                            Text("View Cover Sheet")
                        }
                    }
                    if (hasThreeDAssets) {
                        Button(onClick = onOpenThreeD) {
                            Text("View 3D")
                        }
                    }
                }
            }

            item(key = "actions-specialty") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SpecialtyActionWidget(
                        modifier = Modifier.weight(1f),
                        title = "Door Panels",
                        subtitle = "View filtered panel cut lists",
                        onClick = onOpenDoorPanels
                    )
                    SpecialtyActionWidget(
                        modifier = Modifier.weight(1f),
                        title = "Split View",
                        subtitle = "Open assembly + plans workspace",
                        onClick = onOpenSplitView
                    )
                }
            }

            if (resolvedItems.isEmpty()) {
                item(key = "empty") {
                    Text(
                        "No specialty checklist items found.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                items(
                    items = resolvedItems,
                    key = { resolved -> resolved.item.id }
                ) { resolved ->
                    val itemToggles = checklistTogglesForItem(resolved, completionOverrides)
                    SpecialtyChecklistRow(
                        resolved = resolved,
                        toggles = itemToggles,
                        inFlightUpdates = inFlightUpdates,
                        onJumpToCabinet = onJumpToCabinet,
                        onPatchDims = { dims, qty, mat ->
                            coroutineScope.launch {
                                try {
                                    specialtyStateStore.patchSpecialtyItemFields(jobFolderName, resolved.item.id, dims, qty, mat)
                                } catch (_: Exception) {
                                    snackbarHostState.showSnackbar("Failed to save dimensions.")
                                }
                            }
                        },
                        onCheckedChange = { toggle, next ->
                            val itemId = resolved.item.id
                            val controlId = toggle.controlId
                            val previous = completionOverrides[controlId] ?: toggle.checked
                            completionOverrides[controlId] = next
                            startInFlightUpdate(inFlightUpdates, controlId)
                            coroutineScope.launch {
                                try {
                                    specialtyStateStore.setItemCompletionKey(
                                        jobFolderName = jobFolderName,
                                        itemId = itemId,
                                        completionKey = toggle.completionKey,
                                        completed = next
                                    )
                                    completionOverrides.remove(controlId)
                                    toggleErrorMessage = null
                                } catch (_: Exception) {
                                    completionOverrides[controlId] = previous
                                    val message = "Failed to update checklist item. Please retry."
                                    toggleErrorMessage = message
                                    snackbarHostState.showSnackbar(message)
                                } finally {
                                    finishInFlightUpdate(inFlightUpdates, controlId)
                                }
                            }
                        }
                    )
                }
            }

            if (!toggleErrorMessage.isNullOrBlank()) {
                item(key = "error") {
                    Text(
                        text = toggleErrorMessage.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
private fun SpecialtyActionWidget(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        tonalElevation = 2.dp,
        shape = MaterialTheme.shapes.large,
        modifier = modifier
            .heightIn(min = 92.dp)
            .clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
internal fun SpecialtyChecklistRow(
    resolved: SpecialtyResolvedItem,
    toggles: List<SpecialtyChecklistToggle>,
    inFlightUpdates: Map<String, Boolean>,
    onCheckedChange: (SpecialtyChecklistToggle, Boolean) -> Unit,
    onJumpToCabinet: ((String) -> Unit)? = null,
    onPatchDims: ((String?, Int?, String?) -> Unit)? = null
) {
    val item = resolved.item
    val title = specialtyItemTitle(item.cabinetNumbers, item.name)
    val completionKeys = completionKeysForItem(item)
    val totalSteps = completionKeys.size.coerceAtLeast(1)
    val completedSteps = toggles.count { it.checked }.coerceAtMost(totalSteps)
    val fraction = if (totalSteps <= 0) 0f else completedSteps.toFloat() / totalSteps.toFloat()
    val statusCounts = StatusCounts(
        total = totalSteps,
        complete = completedSteps,
        bad = 0,
        skipped = 0,
        notStarted = (totalSteps - completedSteps).coerceAtLeast(0)
    )
    val cabinets = item.cabinetNumbers.joinToString(", ").ifBlank { "None" }
    val notes = item.notes?.trim().orEmpty()
    val attachmentCount = item.attachments.size
    val supplier = item.supplier?.trim().orEmpty()
    val model = item.model?.trim().orEmpty()
    val tracking = item.tracking?.trim().orEmpty()
    val orderDate = item.orderDate?.trim().orEmpty()
    val orderUrl = item.orderUrl?.trim().orEmpty()
    var attachmentsExpanded by remember(item.id) { mutableStateOf(false) }

    ProgressCard(
        title = title,
        subtitle = "$completedSteps/$totalSteps steps complete",
        fraction = fraction,
        expanded = false,
        onToggleExpanded = {},
        onClick = {},
        showBottomProgressBar = true,
        segmentedStatusCounts = statusCounts,
        showExpandToggle = false,
        headerLeading = {
            toggles.forEach { toggle ->
                val enabled = isToggleEnabled(toggle.controlId, inFlightUpdates)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Checkbox(
                        checked = toggle.checked,
                        onCheckedChange = { next -> onCheckedChange(toggle, next) },
                        enabled = enabled
                    )
                    if (toggles.size > 1) {
                        Text(
                            text = shortDivisionLabel(toggle.label ?: toggle.completionKey),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        headerActions = {
            if (item.cabinetNumbers.isNotEmpty() && onJumpToCabinet != null) {
                TextButton(
                    onClick = { onJumpToCabinet(item.cabinetNumbers.first()) }
                ) {
                    Text("Jump")
                }
            }
            item.stations.forEach { station ->
                val chip = stationChipSpec(station)
                StatusChip(
                    text = chip.label,
                    backgroundColor = chip.background,
                    contentColor = chip.content
                )
            }
        },
        inlineContent = {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                if (notes.isNotBlank()) SpecialtyMetaRow(label = "Notes", value = notes)
                if (supplier.isNotBlank()) SpecialtyMetaRow(label = "Supplier", value = supplier)
                if (model.isNotBlank()) SpecialtyMetaRow(label = "Model", value = model)
                if (tracking.isNotBlank()) SpecialtyMetaRow(label = "Tracking", value = tracking)
                if (orderDate.isNotBlank()) SpecialtyMetaRow(label = "Order Date", value = orderDate)
                if (orderUrl.isNotBlank()) SpecialtyMetaRow(label = "Order URL", value = orderUrl)
                val isSawStation = SpecialtyStation.SAW in item.stations
                if (isSawStation || item.dimensions != null || item.quantity != null || !item.material.isNullOrBlank()) {
                    SpecialtyDimsSection(
                        item = item,
                        isSawStation = isSawStation,
                        onPatchDims = { d, q, m -> onPatchDims?.invoke(d, q, m) }
                    )
                }
                if (attachmentCount > 0) {
                    Box {
                        Surface(
                            tonalElevation = 0.dp,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.clickable { attachmentsExpanded = !attachmentsExpanded }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = "Attachments ($attachmentCount)",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Icon(
                                    imageVector = if (attachmentsExpanded) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        DropdownMenu(
                            expanded = attachmentsExpanded,
                            onDismissRequest = { attachmentsExpanded = false }
                        ) {
                            item.attachments.forEach { attachment ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = attachment,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    },
                                    onClick = { attachmentsExpanded = false }
                                )
                            }
                        }
                    }
                }
                if (toggles.any { toggle -> !isToggleEnabled(toggle.controlId, inFlightUpdates) }) {
                    Text(
                        text = "Saving...",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    )
}

private fun specialtyItemTitle(cabinetNumbers: List<String>, name: String): String {
    val cleaned = cabinetNumbers
        .map { it.trim() }
        .filter { it.isNotBlank() }
    if (cleaned.isEmpty()) return name
    val cabinetLabel = cleaned.joinToString(", ") { cab ->
        if (cab.startsWith("#")) cab else "#$cab"
    }
    return "$cabinetLabel - $name"
}

private fun shortDivisionLabel(label: String): String {
    val key = label.trim().uppercase()
    return when {
        "HARDWOODS" in key -> "HW"
        "ASSEMBLY" in key -> "ASM"
        "SPECIALTY" in key -> "SPEC"
        "EDGE" in key -> "EDGE"
        else -> key.take(4)
    }
}

private data class StationChipSpec(
    val label: String,
    val background: androidx.compose.ui.graphics.Color,
    val content: androidx.compose.ui.graphics.Color
)

@Composable
private fun stationChipSpec(station: SpecialtyStation): StationChipSpec {
    val scheme = MaterialTheme.colorScheme
    return when (station) {
        SpecialtyStation.CNC -> StationChipSpec("CNC", scheme.primaryContainer, scheme.onPrimaryContainer)
        SpecialtyStation.HARDWOODS -> StationChipSpec("HW", scheme.secondaryContainer, scheme.onSecondaryContainer)
        SpecialtyStation.SAW -> StationChipSpec("SAW", scheme.tertiaryContainer, scheme.onTertiaryContainer)
        SpecialtyStation.EDGE_BANDER -> StationChipSpec("EDGE", scheme.tertiaryContainer, scheme.onTertiaryContainer)
        SpecialtyStation.ASSEMBLY -> StationChipSpec("ASM", scheme.secondaryContainer, scheme.onSecondaryContainer)
        SpecialtyStation.SPECIALTY -> StationChipSpec("SPEC", scheme.surfaceVariant, scheme.onSurfaceVariant)
    }
}

@Composable
private fun SpecialtyMetaRow(
    label: String,
    value: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = "$label:",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

internal data class SpecialtyChecklistToggle(
    val controlId: String,
    val completionKey: String,
    val label: String?,
    val checked: Boolean
)

internal fun checklistTogglesForItem(
    resolved: SpecialtyResolvedItem,
    completionOverrides: Map<String, Boolean>
): List<SpecialtyChecklistToggle> {
    val completionKeys = completionKeysForItem(resolved.item)
    return completionKeys.map { completionKey ->
        val controlId = checklistControlId(resolved.item.id, completionKey)
        val checked = completionOverrides[controlId] ?: (resolved.completionByKey[completionKey]?.completed == true)
        SpecialtyChecklistToggle(
            controlId = controlId,
            completionKey = completionKey,
            label = if (completionKeys.size >= 2) completionKey.replace('_', ' ') else null,
            checked = checked
        )
    }
}

internal fun isChecklistItemComplete(
    resolved: SpecialtyResolvedItem,
    completionOverrides: Map<String, Boolean>
): Boolean {
    return checklistTogglesForItem(resolved, completionOverrides).all { toggle -> toggle.checked }
}

private fun checklistControlId(itemId: String, completionKey: String): String {
    return "$itemId::$completionKey"
}

internal fun startInFlightUpdate(inFlightUpdates: MutableMap<String, Boolean>, controlId: String) {
    inFlightUpdates[controlId] = true
}

internal fun finishInFlightUpdate(inFlightUpdates: MutableMap<String, Boolean>, controlId: String) {
    inFlightUpdates.remove(controlId)
}

internal fun isToggleEnabled(controlId: String, inFlightUpdates: Map<String, Boolean>): Boolean {
    return inFlightUpdates[controlId] != true
}

@Composable
private fun SpecialtyDimsSection(
    item: com.kkc.sheettracker.data.models.SpecialtyItem,
    isSawStation: Boolean,
    onPatchDims: (String?, Int?, String?) -> Unit
) {
    var editing by remember(item.id) { mutableStateOf(false) }
    var editDims by remember(item.id) { mutableStateOf(item.dimensions ?: "") }
    var editQty by remember(item.id) { mutableStateOf(item.quantity?.toString() ?: "") }
    var editMat by remember(item.id) { mutableStateOf(item.material ?: "") }
    val hasData = !item.dimensions.isNullOrBlank() || item.quantity != null || !item.material.isNullOrBlank()

    if (isSawStation) {
        if (editing) {
            Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(value = editDims, onValueChange = { editDims = it }, label = { Text("Dims") }, modifier = Modifier.weight(2f), singleLine = true)
                    OutlinedTextField(value = editQty, onValueChange = { editQty = it }, label = { Text("Qty") }, modifier = Modifier.weight(1f), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    OutlinedTextField(value = editMat, onValueChange = { editMat = it }, label = { Text("Material") }, modifier = Modifier.weight(2f), singleLine = true)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        onPatchDims(editDims.trim().takeIf { it.isNotBlank() }, editQty.trim().toIntOrNull(), editMat.trim().takeIf { it.isNotBlank() })
                        editing = false
                    }) { Text("Save") }
                    TextButton(onClick = {
                        editDims = item.dimensions ?: ""
                        editQty = item.quantity?.toString() ?: ""
                        editMat = item.material ?: ""
                        editing = false
                    }) { Text("Cancel") }
                }
            }
        } else {
            Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (!hasData) {
                    SuggestionChip(onClick = { editing = true }, label = { Text("Add dims...", style = MaterialTheme.typography.labelSmall) })
                } else {
                    if (!item.dimensions.isNullOrBlank()) SuggestionChip(onClick = { editing = true }, label = { Text(item.dimensions, style = MaterialTheme.typography.labelSmall) })
                    if (item.quantity != null) SuggestionChip(onClick = { editing = true }, label = { Text("Qty: ${item.quantity}", style = MaterialTheme.typography.labelSmall) })
                    if (!item.material.isNullOrBlank()) SuggestionChip(onClick = { editing = true }, label = { Text(item.material, style = MaterialTheme.typography.labelSmall) })
                }
            }
        }
    } else if (hasData) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (!item.dimensions.isNullOrBlank()) Row { Text("Dims: ", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold); Text(item.dimensions, style = MaterialTheme.typography.bodySmall) }
            if (item.quantity != null) Row { Text("Qty: ", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold); Text(item.quantity.toString(), style = MaterialTheme.typography.bodySmall) }
            if (!item.material.isNullOrBlank()) Row { Text("Material: ", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold); Text(item.material, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
