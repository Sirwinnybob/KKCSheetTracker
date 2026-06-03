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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.data.SpecialtyStateStore
import com.kkc.sheettracker.data.models.ScanStatus
import com.kkc.sheettracker.data.models.SpecialtyResolvedItem
import com.kkc.sheettracker.data.models.SpecialtyStation
import kotlinx.coroutines.launch

enum class SpecialtySurfaceMode {
    CNC,
    HARDWOODS,
    ASSEMBLY
}

internal data class SpecialtySectionRowModel(
    val resolved: SpecialtyResolvedItem,
    val isRelevantToMode: Boolean
)

internal fun specialtyPriorityStationsForMode(mode: SpecialtySurfaceMode): Set<SpecialtyStation> {
    return when (mode) {
        SpecialtySurfaceMode.CNC -> setOf(SpecialtyStation.CNC)
        SpecialtySurfaceMode.HARDWOODS -> setOf(SpecialtyStation.SAW, SpecialtyStation.EDGE_BANDER)
        SpecialtySurfaceMode.ASSEMBLY -> setOf(SpecialtyStation.ASSEMBLY)
    }
}

internal fun buildSpecialtySectionRows(
    resolvedItems: List<SpecialtyResolvedItem>,
    mode: SpecialtySurfaceMode
): List<SpecialtySectionRowModel> {
    if (resolvedItems.isEmpty()) return emptyList()
    val priorityStations = specialtyPriorityStationsForMode(mode)
    val (relevant, nonRelevant) = resolvedItems.partition { resolved ->
        resolved.item.stations.contains(SpecialtyStation.DELIVERY) ||
        resolved.item.stations.any { station -> station in priorityStations }
    }
    return relevant.map { SpecialtySectionRowModel(it, true) } +
        nonRelevant.map { SpecialtySectionRowModel(it, false) }
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
        tonalElevation = 1.dp,
        shape = MaterialTheme.shapes.large
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
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
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(rowModels, key = { rowModel -> rowModel.resolved.item.id }) { rowModel ->
                    val item = rowModel.resolved.item
                    val itemId = item.id
                    val checked = completionOverrides[itemId] ?: rowModel.resolved.isComplete
                    val itemEnabled = inFlight[itemId] != true
                    val stationText = item.stations.joinToString(" • ") { station ->
                        station.name.replace('_', ' ')
                    }
                    Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .alpha(if (rowModel.isRelevantToMode) 1f else 0.62f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Checkbox(
                            checked = checked,
                            enabled = itemEnabled,
                            onCheckedChange = { next ->
                                val previous = completionOverrides[itemId] ?: checked
                                completionOverrides[itemId] = next
                                inFlight[itemId] = true
                                coroutineScope.launch {
                                    try {
                                        specialtyStateStore.setItemCompletion(
                                            jobFolderName = jobFolderName,
                                            itemId = itemId,
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
                            verticalArrangement = Arrangement.spacedBy(1.dp)
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
                        }
                        if (item.cabinetNumbers.isNotEmpty() && onJumpToCabinet != null) {
                            Button(
                                onClick = { onJumpToCabinet(item.cabinetNumbers.first()) },
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 0.dp),
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
