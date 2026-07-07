package com.kkc.sheettracker.ui.specialty

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.ui.theme.KKCSpacing
import com.kkc.sheettracker.data.SpecialtyProgressStore
import com.kkc.sheettracker.data.SpecialtyStateStore
import com.kkc.sheettracker.data.models.ScanStatus
import com.kkc.sheettracker.data.models.SpecialtyItem
import com.kkc.sheettracker.data.models.SpecialtyItemCategory
import com.kkc.sheettracker.data.models.SpecialtyResolvedItem
import com.kkc.sheettracker.data.models.SpecialtyStation
import com.kkc.sheettracker.data.requiresStationSplit
import kotlinx.coroutines.launch

enum class SpecialtySurfaceMode {
    CNC,
    HARDWOODS,
    ASSEMBLY,
    SPECIALTY
}

internal data class SpecialtySectionRowModel(
    val resolved: SpecialtyResolvedItem,
    val isRelevantToMode: Boolean = true
)

internal fun isItemRelevantToMode(
    resolved: SpecialtyResolvedItem,
    mode: SpecialtySurfaceMode
): Boolean {
    val stations = resolved.item.stations
    val category = resolved.item.category
    return when (mode) {
        SpecialtySurfaceMode.CNC ->
            stations.contains(SpecialtyStation.CNC)
        SpecialtySurfaceMode.HARDWOODS ->
            stations.contains(SpecialtyStation.HARDWOODS) ||
            stations.contains(SpecialtyStation.DELIVERY)
        SpecialtySurfaceMode.ASSEMBLY ->
            stations.contains(SpecialtyStation.ASSEMBLY) ||
            stations.contains(SpecialtyStation.DELIVERY) ||
            category == SpecialtyItemCategory.TO_ORDER
        SpecialtySurfaceMode.SPECIALTY ->
            !stations.contains(SpecialtyStation.DELIVERY)
    }
}

internal fun buildSpecialtySectionRows(
    resolvedItems: List<SpecialtyResolvedItem>,
    mode: SpecialtySurfaceMode
): List<SpecialtySectionRowModel> {
    return resolvedItems
        .filter { isItemRelevantToMode(it, mode) }
        .map { SpecialtySectionRowModel(it, true) }
}

/**
 * Whether [station] is the one this compact surface cares about for [mode]. Mirrors the
 * item-level relevance used by [isItemRelevantToMode], but at per-station granularity so a
 * multi-station CUSTOM item can be matched to exactly one of its completion keys.
 */
internal fun isStationRelevantToMode(
    station: SpecialtyStation,
    mode: SpecialtySurfaceMode
): Boolean = when (mode) {
    SpecialtySurfaceMode.CNC -> station == SpecialtyStation.CNC
    SpecialtySurfaceMode.HARDWOODS -> station == SpecialtyStation.HARDWOODS || station == SpecialtyStation.DELIVERY
    SpecialtySurfaceMode.ASSEMBLY -> station == SpecialtyStation.ASSEMBLY || station == SpecialtyStation.DELIVERY
    SpecialtySurfaceMode.SPECIALTY -> station != SpecialtyStation.DELIVERY
}

/**
 * The single completion key the compact checkbox is allowed to write for [item] in [mode].
 *
 * Multi-station CUSTOM items store one completion key per station (see
 * [com.kkc.sheettracker.data.completionKeysForItem]); a single checkbox must never write all of
 * them at once (that would silently mark stations complete that the user never touched). Returns
 * null when the item doesn't resolve to exactly one relevant key for this mode, so the caller can
 * disable the checkbox instead of guessing.
 */
internal fun compactCompletionKeyForMode(
    item: SpecialtyItem,
    mode: SpecialtySurfaceMode
): String? {
    if (!requiresStationSplit(item)) return SpecialtyProgressStore.ITEM_COMPLETION_KEY
    return item.stations
        .filter { station -> isStationRelevantToMode(station, mode) }
        .singleOrNull()
        ?.name
}

@Composable
fun CompactSpecialtySection(
    jobFolderName: String,
    specialtyStateStore: SpecialtyStateStore,
    mode: SpecialtySurfaceMode,
    modifier: Modifier = Modifier,
    onJumpToCabinet: ((String) -> Unit)? = null
) {
    val scanState by specialtyStateStore.scanState.collectAsState()
    val progressVersion by specialtyStateStore.progressVersion.collectAsState()
    val resolvedItems = remember(scanState.snapshot.generation, progressVersion, jobFolderName) {
        specialtyStateStore.getResolvedItems(jobFolderName)
    }
    val rowModels = remember(resolvedItems, mode) {
        buildSpecialtySectionRows(resolvedItems, mode)
    }

    val completionOverrides = remember(jobFolderName) { mutableStateMapOf<String, Boolean>() }
    val inFlight = remember(jobFolderName) { mutableStateMapOf<String, Boolean>() }
    var errorMessage by remember(jobFolderName) { mutableStateOf<String?>(null) }
    val completedCount = rowModels.count { row ->
        completionOverrides[row.resolved.item.id] ?: row.resolved.isComplete
    }
    val coroutineScope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    Surface(
        modifier = modifier.fillMaxWidth(),
        tonalElevation = 3.dp,
        shape = MaterialTheme.shapes.large
    ) {
        Column(
            modifier = Modifier.padding(horizontal = KKCSpacing.cardPaddingSmall, vertical = KKCSpacing.m),
            verticalArrangement = Arrangement.spacedBy(KKCSpacing.tightSpacing)
        ) {
            Text(
                text = if (rowModels.isEmpty() && scanState.status == ScanStatus.LOADING) {
                    "Specialty loading..."
                } else {
                    "Specialty $completedCount/${rowModels.size}"
                },
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )

            if (rowModels.isEmpty() && scanState.status != ScanStatus.LOADING) {
                Text(
                    text = "No specialty items",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 220.dp),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(KKCSpacing.xxs)
            ) {
                items(rowModels, key = { rowModel -> rowModel.resolved.item.id }) { rowModel ->
                    val item = rowModel.resolved.item
                    val itemId = item.id
                    val completionKey = compactCompletionKeyForMode(item, mode)
                    val checked = completionOverrides[itemId] ?: if (completionKey != null) {
                        rowModel.resolved.completionByKey[completionKey]?.completed == true
                    } else {
                        rowModel.resolved.isComplete
                    }
                    // Multi-station CUSTOM items with more than one key relevant to this mode
                    // have no single unambiguous key to toggle from a compact checkbox — disable
                    // it rather than writing (and silently completing) every station's key.
                    val itemEnabled = completionKey != null && inFlight[itemId] != true
                    val stationText = item.stations.joinToString(" • ") { station ->
                        station.name.replace('_', ' ')
                    }
                    Row(
                    modifier = Modifier
                        .fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(KKCSpacing.tightSpacing)
                    ) {
                        Checkbox(
                            checked = checked,
                            enabled = itemEnabled,
                            onCheckedChange = onChange@{ next ->
                                val key = completionKey ?: return@onChange
                                val previous = completionOverrides[itemId] ?: checked
                                completionOverrides[itemId] = next
                                inFlight[itemId] = true
                                coroutineScope.launch {
                                    try {
                                        specialtyStateStore.setItemCompletionKey(
                                            jobFolderName = jobFolderName,
                                            itemId = itemId,
                                            completionKey = key,
                                            completed = next
                                        )
                                        completionOverrides.remove(itemId)
                                        errorMessage = null
                                    } catch (_: Exception) {
                                        completionOverrides[itemId] = previous
                                        errorMessage = "Specialty update failed. Retry."
                                    } finally {
                                        inFlight.remove(itemId)
                                    }
                                }
                            }
                        )
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(KKCSpacing.textLineGap)
                        ) {
                            Text(
                                text = item.name,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (stationText.isNotBlank()) {
                                Text(
                                    text = stationText,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (item.quantity != null) {
                                Text(
                                    text = "Qty: ${item.quantity}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1
                                )
                            }
                            if (!item.dimensions.isNullOrBlank()) {
                                Text(
                                    text = "Dims: ${item.dimensions}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (!item.material.isNullOrBlank()) {
                                Text(
                                    text = "Material: ${item.material}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (!item.supplier.isNullOrBlank()) {
                                Text(
                                    text = "Supplier: ${item.supplier}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (!item.model.isNullOrBlank()) {
                                Text(
                                    text = "Model#: ${item.model}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (!item.orderDate.isNullOrBlank()) {
                                Text(
                                    text = "Order: ${item.orderDate}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1
                                )
                            }
                            if (!item.tracking.isNullOrBlank()) {
                                Text(
                                    text = "Track: ${item.tracking}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (!item.notes.isNullOrBlank()) {
                                Text(
                                    text = item.notes,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontStyle = FontStyle.Italic,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        if (item.cabinetNumbers.isNotEmpty() && onJumpToCabinet != null) {
                            Button(
                                onClick = { onJumpToCabinet(item.cabinetNumbers.first()) },
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = KKCSpacing.tightSpacing, vertical = 0.dp),
                                modifier = Modifier.heightIn(min = 32.dp)
                            ) {
                                Text("View", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }

            if (!errorMessage.isNullOrBlank()) {
                Text(
                    text = errorMessage.orEmpty(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
