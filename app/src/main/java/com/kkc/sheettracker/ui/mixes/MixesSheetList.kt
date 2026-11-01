package com.kkc.sheettracker.ui.mixes

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.data.mixfiles.*
import com.kkc.sheettracker.ui.settings.GroupCaption
import com.kkc.sheettracker.ui.theme.LocalKKCStatusColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import java.io.File

internal enum class MixRowAction { EDIT, REMOVE, SPLIT, HISTORY }

/** Network-free grouped list. Moves are staged on drop, and identity is always the file occurrence ID. */
@Composable
internal fun MixesSheetList(groups: SheetGroups, secondPass: SecondPassDraft?, revertSupported: Boolean, enabled: Boolean,
    pdf: File?, onDragStart: () -> Unit, onDrop: (List<Pair<RowModel, RowModel>>) -> Unit,
    onAdd: (SheetPage) -> Unit, onSecondPass: (List<String>, SecondPassField, Boolean) -> Unit,
    onRowAction: ((RowModel, MixRowAction) -> Unit)?, modifier: Modifier = Modifier,
    supportedRowActions: Set<MixRowAction> = MixRowAction.entries.toSet(), secondPassEnabled: Boolean = enabled,
    splitMode: Boolean = false, splitIds: Set<Long> = emptySet(), onSplitSelection: (RowModel) -> Unit = {}) {
    val listState = rememberLazyListState()
    var rows by remember(groups.remaining, groups.unmatched) { mutableStateOf(groups.remaining + groups.unmatched) }
    val moves = remember { mutableListOf<Pair<RowModel, RowModel>>() }
    val reorder = rememberReorderableLazyListState(listState) { from, to ->
        val a = rows.firstOrNull { it.key == from.key }
        val b = rows.firstOrNull { it.key == to.key }
        if (a != null && b != null && a.draggable && b.draggable) {
            moves += a to b
            rows = rows.toMutableList().apply { add(indexOf(b), removeAt(indexOf(a))) }
        }
    }
    LazyColumn(state = listState, modifier = modifier.testTag("mixes-sheet-list"), contentPadding = PaddingValues(bottom = 160.dp)) {
        fun section(title: String, sectionRows: List<RowModel>) {
            if (sectionRows.isEmpty()) return
            item(key = title) { GroupCaption(title, Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) }
            items(sectionRows, key = { it.key }) { row ->
                ReorderableItem(reorder, key = row.key) {
                    val drag = if (row.draggable && enabled) Modifier.draggableHandle(
                        onDragStarted = { moves.clear(); onDragStart() },
                        onDragStopped = { onDrop(moves.toList()); moves.clear() }) else Modifier
                    Column {
                        if (splitMode && row.canSplit) Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(row.draftEntryIds.all { it in splitIds }, { onSplitSelection(row) }, enabled = enabled)
                            Text("Select ${row.label}")
                        }
                        MixesSheetRow(row, secondPass, revertSupported, secondPassEnabled, pdf, drag, onSecondPass, onRowAction, supportedRowActions, enabled)
                    }
                }
            }
        }
        section("Remaining · drag to reorder", rows.filter { it.item is MixItem.Sheet })
        section("Unmatched", rows.filter { it.item is MixItem.Unmatched })
        if (groups.notInMix.isNotEmpty()) {
            item(key = "not-in") { GroupCaption("Not in this mix", Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) }
            items(groups.notInMix, key = { "add-${it.pageNumber}" }) { page ->
                Row(Modifier.fillMaxWidth().heightIn(min = 76.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    SheetThumbnail(pdf, page)
                    Column(Modifier.weight(1f).padding(12.dp)) {
                        Text(page.pgmFiles.joinToString(" + "), style = MaterialTheme.typography.bodyMedium)
                        Text("Sheet ${page.pageNumber}", style = MaterialTheme.typography.bodySmall)
                        groups.notInMixOtherMixes[page.pageNumber]?.let { Text("In ${it.joinToString()}", style = MaterialTheme.typography.labelSmall) }
                    }
                    OutlinedButton(onClick = { onAdd(page) }, enabled = enabled) { Text("Add") }
                }
            }
        }
        section("Completed · locked", groups.completed)
    }
}

@Composable
private fun MixesSheetRow(row: RowModel, secondPass: SecondPassDraft?, revertSupported: Boolean, enabled: Boolean,
    pdf: File?, drag: Modifier, onSecondPass: (List<String>, SecondPassField, Boolean) -> Unit,
    onAction: ((RowModel, MixRowAction) -> Unit)?, supportedActions: Set<MixRowAction>, mixEnabled: Boolean) {
    val statuses = LocalKKCStatusColors.current
    val page = (row.item as? MixItem.Sheet)?.page
    val pgms = row.entries.map { it.source?.pgm ?: it.newPgm.orEmpty() }
    var menu by remember(row.key) { mutableStateOf(false) }
    val tone = when { row.item is MixItem.Unmatched -> statuses.bad; row.locked -> statuses.complete; else -> MaterialTheme.colorScheme.onSurface }
    Column(Modifier.fillMaxWidth().background(if (row.locked) statuses.complete.copy(alpha = .07f) else MaterialTheme.colorScheme.surface)
        .testTag("mixes-row-${row.key}")) {
        Row(Modifier.fillMaxWidth().heightIn(min = 84.dp).padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(drag.size(48.dp), contentAlignment = Alignment.Center) {
                Icon(if (row.locked) Icons.Default.Lock else Icons.Default.DragHandle,
                    if (row.draggable) "Drag ${row.label}" else if (row.locked) "Completed sheet locked" else null,
                    tint = if (row.draggable || row.locked) tone else MaterialTheme.colorScheme.outline)
            }
            SheetThumbnail(pdf, page)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(pgms.joinToString(" + "), style = MaterialTheme.typography.titleSmall, color = tone)
                Text(row.label, style = MaterialTheme.typography.bodySmall)
                if (row.locked) Text(row.tabletStatus.toString().replace('_', ' '), style = MaterialTheme.typography.labelSmall, color = tone)
                if (row.frzRemaining != null && row.disagreement) Text("FRZ: ${row.frzRemaining} left · tablet disagrees", style = MaterialTheme.typography.labelSmall, color = statuses.skip)
                row.chips.forEach { chip -> Text(when (chip) {
                    RowChip.UNMATCHED -> "Unmatched · ${row.sourceFile.orEmpty()}"
                    RowChip.PGM_MISSING -> "PGM file missing"
                    RowChip.NEEDS_REFRESH -> "Needs refresh"
                    RowChip.HIDDEN_PAGE -> "Hidden / continuation page"
                    RowChip.IN_OTHER_MIX -> "In another mix"
                }, style = MaterialTheme.typography.labelSmall, color = if (chip == RowChip.UNMATCHED || chip == RowChip.PGM_MISSING) statuses.bad else MaterialTheme.colorScheme.onSurfaceVariant) }
                if (row.companionOnly) Text(row.editBlockedReason.orEmpty(), style = MaterialTheme.typography.bodySmall)
            }
            if (page != null) SecondPassControls(secondPass, pgms, enabled && !row.locked, revertSupported, { field, on -> onSecondPass(pgms, field, on) })
            Box {
                IconButton(onClick = { menu = true }, enabled = onAction != null) {
                    Icon(Icons.Default.MoreVert, "Actions for ${row.label}")
                }
                DropdownMenu(menu, onDismissRequest = { menu = false }) {
                    if (!row.locked) DropdownMenuItem(text = { Text("Edit quantity and parameters") }, enabled = mixEnabled && row.canEditCounts && MixRowAction.EDIT in supportedActions,
                        onClick = { menu = false; onAction?.invoke(row, MixRowAction.EDIT) })
                    row.editBlockedReason?.takeIf { !row.locked }?.let { Text(it, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall) }
                    DropdownMenuItem(text = { Text("Remove from mix") }, enabled = mixEnabled && row.canRemove && MixRowAction.REMOVE in supportedActions,
                        onClick = { menu = false; onAction?.invoke(row, MixRowAction.REMOVE) })
                    if (!row.locked) DropdownMenuItem(text = { Text("Move to new mix") }, enabled = mixEnabled && row.canSplit && MixRowAction.SPLIT in supportedActions,
                        onClick = { menu = false; onAction?.invoke(row, MixRowAction.SPLIT) })
                    if (MixRowAction.HISTORY in supportedActions) DropdownMenuItem(text = { Text("2nd-pass history") },
                        onClick = { menu = false; onAction?.invoke(row, MixRowAction.HISTORY) })
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/** Same PDF/sidecar thumbnail loader as ManageCodeRowView, with work off the UI thread. */
@Composable
private fun SheetThumbnail(pdf: File?, page: SheetPage?) {
    val bitmap by produceState<ImageBitmap?>(null, pdf, page?.pageNumber, page?.thumbnailPath) {
        value = if (pdf != null && page != null) withContext(Dispatchers.IO) {
            runCatching { com.kkc.sheettracker.ui.viewer.loadSheetThumbnailForToc(pdf, page.pageNumber - 1, page.thumbnailPath)?.asImageBitmap() }.getOrNull()
        } else null
    }
    Box(Modifier.size(width = 72.dp, height = 52.dp).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        val loaded = bitmap
        if (loaded != null) Image(loaded, "Sheet ${page?.pageNumber} thumbnail", Modifier.fillMaxSize())
        else Icon(Icons.Default.Image, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
