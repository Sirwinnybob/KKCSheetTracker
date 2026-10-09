package com.kkc.sheettracker.ui.settings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.data.SpecialtyViewerDefaults
import com.kkc.sheettracker.data.SpecialtyViewerDefaultsStore
import com.kkc.sheettracker.data.specialtyViewerSectionOptions
import com.kkc.sheettracker.data.specialtyViewerStationLabel
import com.kkc.sheettracker.data.models.SpecialtyStation
import com.kkc.sheettracker.ui.components.KKCTopAppBar
import com.kkc.sheettracker.ui.components.icons.DragGripIcon
import com.kkc.sheettracker.ui.components.kkcCardDepth
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SpecialtyViewerDefaultsScreen(
    store: SpecialtyViewerDefaultsStore,
    onBack: () -> Unit,
) {
    val defaults by store.defaults.collectAsState(initial = SpecialtyViewerDefaults())
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current

    // Local copy the drag edits live; synced from the store except mid-drag so a store emission
    // can't yank the row out from under the finger.
    val order = remember { mutableStateListOf<SpecialtyStation>().apply { addAll(defaults.stationOrder) } }
    val storedOrder by rememberUpdatedState(defaults.stationOrder)
    var dragging by remember { mutableStateOf(false) }
    val saveOrder: () -> Unit = {
        val next = order.toList()
        if (next != storedOrder) scope.launch { store.setStationOrder(next) }
    }
    LaunchedEffect(defaults.stationOrder) {
        if (!dragging) {
            order.clear()
            order.addAll(defaults.stationOrder)
        }
    }

    val listState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        // Only station rows reorder; headers and section toggles share this list but have other keys.
        val moving = stationFromKey(from.key) ?: return@rememberReorderableLazyListState
        val target = stationFromKey(to.key) ?: return@rememberReorderableLazyListState
        val next = moveStation(order.toList(), moving, target)
        if (next != order.toList()) {
            order.clear()
            order.addAll(next)
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }

    Scaffold(
        topBar = {
            KKCTopAppBar(
                title = {
                    Text(
                        "Specialty Viewer Defaults",
                        style = MaterialTheme.typography.titleMedium
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            // Margins as content padding (not outer padding) so row card shadows aren't clipped.
            contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SectionLabel("Station Order")
            }
            item {
                Text(
                    "Drag a row by its grip to reorder. Controls the checklist section order in specialty job view.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            items(order, key = { stationKey(it) }) { station ->
                ReorderableItem(reorderState, key = stationKey(station)) { isDragging ->
                    // Arrow-free reorder for TalkBack / switch access.
                    val nudge: (Int) -> Unit = { delta ->
                        val index = order.indexOf(station)
                        val target = order.getOrNull(index + delta)
                        if (target != null) {
                            val next = moveStation(order.toList(), station, target)
                            order.clear()
                            order.addAll(next)
                            saveOrder()
                        }
                    }
                    StationOrderRow(
                        station = station,
                        isDragging = isDragging,
                        onNudge = nudge,
                        gripModifier = Modifier.draggableHandle(
                            onDragStarted = {
                                dragging = true
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            },
                            onDragStopped = {
                                dragging = false
                                saveOrder()
                            }
                        )
                    )
                }
            }

            item {
                SectionLabel("Expanded By Default", Modifier.padding(top = 8.dp))
            }
            item {
                Text(
                    "Choose which sections start expanded when the specialty viewer opens.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            itemsIndexed(
                items = specialtyViewerSectionOptions(defaults.stationOrder),
                key = { _, section -> "section-${section.id}" }
            ) { _, section ->
                Row(
                    modifier = Modifier.fillMaxWidth().animateItem(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(section.label, style = MaterialTheme.typography.bodyLarge)
                    Switch(
                        checked = section.id in defaults.expandedSectionIds,
                        onCheckedChange = { expanded ->
                            scope.launch { store.setSectionExpanded(section.id, expanded) }
                        },
                    )
                }
            }
            item {
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = modifier)
}

private val StationRowShape = RoundedCornerShape(9.dp)

@Composable
private fun StationOrderRow(
    station: SpecialtyStation,
    isDragging: Boolean,
    onNudge: (Int) -> Unit,
    gripModifier: Modifier,
) {
    val label = specialtyViewerStationLabel(station)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .kkcCardDepth(StationRowShape, lifted = isDragging, elevation = 1.dp)
            .background(
                if (isDragging) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.surface
            )
            .semantics {
                customActions = listOf(
                    CustomAccessibilityAction("Move $label up") { onNudge(-1); true },
                    CustomAccessibilityAction("Move $label down") { onNudge(1); true },
                )
            }
            .padding(end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 48dp grip: the only drag handle, sized for a gloved thumb.
        Box(
            modifier = Modifier.size(48.dp).then(gripModifier),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                DragGripIcon,
                contentDescription = "Reorder $label",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )
        }
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
        )
    }
}

internal fun stationKey(station: SpecialtyStation): String = "station-${station.name}"

internal fun stationFromKey(key: Any?): SpecialtyStation? {
    val name = (key as? String)?.removePrefix("station-")?.takeIf { it != key } ?: return null
    return SpecialtyStation.entries.firstOrNull { it.name == name }
}

/** [moving] takes [target]'s slot; everything between shifts one place toward where [moving] was. */
internal fun moveStation(
    order: List<SpecialtyStation>,
    moving: SpecialtyStation,
    target: SpecialtyStation,
): List<SpecialtyStation> {
    val from = order.indexOf(moving)
    val to = order.indexOf(target)
    if (from < 0 || to < 0 || from == to) return order
    return order.toMutableList().apply { add(to, removeAt(from)) }
}
