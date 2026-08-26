package com.kkc.sheettracker.ui.managecode

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.data.mixservice.ManageCodeRow
import com.kkc.sheettracker.data.mixservice.ManageCodeRowSelection
import com.kkc.sheettracker.data.mixservice.toggleSecondPass
import com.kkc.sheettracker.data.mixservice.toggleSuperPass
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

data class ManageCodeMaterialState(
    val materialName: String,
    val hasPgmsOnThisCnc: Boolean,
    val rows: List<ManageCodeRow>,
    val locked: Set<String>,
    val selections: Map<String, ManageCodeRowSelection>
)

@Composable
fun ManageCodeMaterialCard(
    state: ManageCodeMaterialState,
    expanded: Boolean,
    onExpandToggle: () -> Unit,
    onRowsReordered: (List<ManageCodeRow>) -> Unit,
    onSelectionChanged: (editablePgm: String, ManageCodeRowSelection) -> Unit,
    onSelectAll: (field: String, checked: Boolean) -> Unit,
    thumbnailFor: (pageNumber: Int) -> androidx.compose.ui.graphics.ImageBitmap?
) {
    val rowsState = remember(state.rows) { mutableStateOf(state.rows) }
    val listState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        val current = rowsState.value.toMutableList()
        if (from.index in current.indices && to.index in current.indices) {
            current.add(to.index, current.removeAt(from.index))
            rowsState.value = current
        }
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        tonalElevation = if (state.hasPgmsOnThisCnc) 1.dp else 0.dp
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconButton(onClick = onExpandToggle, enabled = state.hasPgmsOnThisCnc) {
                        Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null)
                    }
                    Text(
                        text = state.materialName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium
                    )
                }
                if (!state.hasPgmsOnThisCnc) {
                    Text(
                        text = "No PGMs on this CNC",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("MIX", "PUNLOAD", "2ND").forEach { label ->
                            AssistChip(onClick = { onSelectAll(label, true) }, label = { Text(label, style = MaterialTheme.typography.labelSmall) })
                        }
                    }
                }
            }

            if (expanded && state.hasPgmsOnThisCnc) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = (rowsState.value.size.coerceAtMost(6) * 72).dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    itemsIndexed(rowsState.value, key = { _, row -> row.editablePgm }) { _, row ->
                        val locked = row.editablePgm in state.locked
                        val selection = state.selections[row.editablePgm] ?: ManageCodeRowSelection()
                        ReorderableItem(reorderState, key = row.editablePgm) {
                            ManageCodeRowView(
                                row = row,
                                locked = locked,
                                selection = selection,
                                onSelectionChanged = { onSelectionChanged(row.editablePgm, it) },
                                thumbnail = thumbnailFor(row.pageNumber),
                                dragModifier = if (locked) Modifier else Modifier.draggableHandle(
                                    onDragStopped = { onRowsReordered(rowsState.value) }
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ManageCodeRowView(
    row: ManageCodeRow,
    locked: Boolean,
    selection: ManageCodeRowSelection,
    onSelectionChanged: (ManageCodeRowSelection) -> Unit,
    thumbnail: androidx.compose.ui.graphics.ImageBitmap?,
    dragModifier: Modifier
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (locked) {
            Icon(Icons.Filled.Lock, contentDescription = "Locked", modifier = Modifier.size(16.dp))
        } else {
            Icon(Icons.Filled.DragHandle, contentDescription = "Drag to reorder", modifier = Modifier.size(20.dp).then(dragModifier))
        }
        Box(modifier = Modifier.size(34.dp)) {
            if (thumbnail != null) {
                androidx.compose.foundation.Image(bitmap = thumbnail, contentDescription = null)
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(row.pgmFiles.joinToString(" + "), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
        }
        if (!locked) {
            Checkbox(checked = selection.mix, onCheckedChange = { onSelectionChanged(selection.copy(mix = it)) }, modifier = Modifier.size(24.dp))
            Checkbox(checked = selection.removePUnload, onCheckedChange = { onSelectionChanged(selection.copy(removePUnload = it)) }, modifier = Modifier.size(24.dp))
            Checkbox(checked = selection.secondPass, onCheckedChange = { onSelectionChanged(toggleSecondPass(selection, it)) }, modifier = Modifier.size(24.dp))
            if (selection.secondPass) {
                Checkbox(checked = selection.superPass, onCheckedChange = { onSelectionChanged(toggleSuperPass(selection, it)) }, modifier = Modifier.size(24.dp))
            }
        }
    }
}
