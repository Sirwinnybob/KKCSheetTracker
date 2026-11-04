package com.kkc.sheettracker.ui.mixes

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.kkc.sheettracker.data.mixfiles.*
import com.kkc.sheettracker.data.models.SheetStatus
import com.kkc.sheettracker.ui.components.StatusBorderedCard
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
    // Horizontal/top padding leaves room for the row cards' shadows inside the list's clip.
    LazyColumn(state = listState, modifier = modifier.testTag("mixes-sheet-list"),
        contentPadding = PaddingValues(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 160.dp)) {
        fun section(title: String, sectionRows: List<RowModel>) {
            if (sectionRows.isEmpty()) return
            item(key = title) { GroupCaption(title, Modifier.padding(start = 6.dp, top = 14.dp, bottom = 6.dp)) }
            items(sectionRows, key = { it.key }) { row ->
                ReorderableItem(reorder, key = row.key) { dragging ->
                    val drag = if (row.draggable && enabled) Modifier.draggableHandle(
                        onDragStarted = { moves.clear(); onDragStart() },
                        onDragStopped = { onDrop(moves.toList()); moves.clear() }) else Modifier
                    Column(Modifier.padding(vertical = 5.dp)) {
                        if (splitMode && row.canSplit) Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(row.draftEntryIds.all { it in splitIds }, { onSplitSelection(row) }, enabled = enabled)
                            Text("Select ${row.label}")
                        }
                        MixesSheetRow(row, secondPass, revertSupported, secondPassEnabled, pdf, drag, onSecondPass, onRowAction, supportedRowActions, enabled, dragging)
                    }
                }
            }
        }
        section("Remaining · drag to reorder", rows.filter { it.item is MixItem.Sheet })
        section("Unmatched", rows.filter { it.item is MixItem.Unmatched })
        if (groups.notInMix.isNotEmpty()) {
            item(key = "not-in") { GroupCaption("Not in this mix", Modifier.padding(start = 6.dp, top = 14.dp, bottom = 6.dp)) }
            items(groups.notInMix, key = { "add-${it.pageNumber}" }) { page ->
                StatusBorderedCard(SheetStatus.NOT_STARTED, Modifier.padding(vertical = 5.dp)) {
                Row(Modifier.fillMaxWidth().heightIn(min = RowMinHeight).padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    SheetThumbnail(pdf, page, page.pgmFiles.joinToString(" + "))
                    Column(Modifier.weight(1f).padding(12.dp)) {
                        Text(page.pgmFiles.joinToString(" + "), style = MaterialTheme.typography.bodyMedium)
                        Text("Sheet ${page.pageNumber}", style = MaterialTheme.typography.bodySmall)
                        groups.notInMixOtherMixes[page.pageNumber]?.let { Text("In ${it.joinToString()}", style = MaterialTheme.typography.labelSmall) }
                    }
                    OutlinedButton(onClick = { onAdd(page) }, enabled = enabled) { Text("Add") }
                }
                }
            }
        }
        section("Completed · locked", groups.completed)
    }
}

@Composable
private fun MixesSheetRow(row: RowModel, secondPass: SecondPassDraft?, revertSupported: Boolean, enabled: Boolean,
    pdf: File?, drag: Modifier, onSecondPass: (List<String>, SecondPassField, Boolean) -> Unit,
    onAction: ((RowModel, MixRowAction) -> Unit)?, supportedActions: Set<MixRowAction>, mixEnabled: Boolean, dragging: Boolean) {
    val statuses = LocalKKCStatusColors.current
    val scheme = MaterialTheme.colorScheme
    val page = (row.item as? MixItem.Sheet)?.page
    val pgms = row.entries.map { it.source?.pgm ?: it.newPgm.orEmpty() }
    var menu by remember(row.key) { mutableStateOf(false) }
    val tone = when { row.item is MixItem.Unmatched -> statuses.bad; row.locked -> statuses.complete; else -> scheme.onSurface }
    // The app's status card: edge + wash for what needs attention on this sheet, most urgent first.
    val cardStatus = when {
        row.item is MixItem.Unmatched || RowChip.PGM_MISSING in row.chips -> SheetStatus.HAS_BAD_PARTS
        row.frzRemaining != null && row.disagreement -> SheetStatus.SKIPPED
        row.locked -> SheetStatus.COMPLETE
        page != null && secondPass?.pending(pgms) == true -> SheetStatus.IN_PROGRESS
        else -> row.tabletStatus?.takeIf { it != SheetStatus.COMPLETE } ?: SheetStatus.NOT_STARTED
    }
    StatusBorderedCard(cardStatus, Modifier.testTag("mixes-row-${row.key}"), tonalElevation = if (dragging) 8.dp else 3.dp) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().heightIn(min = RowMinHeight).padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(drag.size(48.dp), contentAlignment = Alignment.Center) {
                Icon(if (row.locked) Icons.Default.Lock else Icons.Default.DragHandle,
                    if (row.draggable) "Drag ${row.label}" else if (row.locked) "Completed sheet locked" else null,
                    tint = if (row.draggable || row.locked) tone else MaterialTheme.colorScheme.outline)
            }
            SheetThumbnail(pdf, page, pgms.joinToString(" + "))
            Column(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(pgms.joinToString(" + "), style = MaterialTheme.typography.titleSmall, color = tone)
                Text(row.label, style = MaterialTheme.typography.bodySmall)
                if (row.locked) Text(row.tabletStatus.toString().replace('_', ' '), style = MaterialTheme.typography.labelSmall, color = tone)
                if (row.frzRemaining != null && row.disagreement) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(Icons.Default.WarningAmber, null, Modifier.size(16.dp), tint = statuses.skip)
                    Text("FRZ: ${row.frzRemaining} left · tablet disagrees", style = MaterialTheme.typography.labelMedium, color = statuses.skip)
                }
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
    }
    }
}

/**
 * Loads PDF/sidecar thumbnails off the UI thread. Fills the row's height at the sheet's own aspect ratio;
 * tapping it opens [SheetPreviewDialog].
 */
@Composable
private fun SheetThumbnail(pdf: File?, page: SheetPage?, title: String) {
    val bitmap by produceState<ImageBitmap?>(null, pdf, page?.pageNumber, page?.thumbnailPath) {
        value = if (pdf != null && page != null) withContext(Dispatchers.IO) {
            runCatching { com.kkc.sheettracker.ui.viewer.loadSheetThumbnailForToc(pdf, page.pageNumber - 1, page.thumbnailPath)?.asImageBitmap() }.getOrNull()
        } else null
    }
    var expanded by remember(page?.pageNumber) { mutableStateOf(false) }
    val loaded = bitmap
    val aspect = loaded?.let { (it.width.toFloat() / it.height).coerceIn(0.75f, 2.4f) } ?: 1.6f
    Box(Modifier.height(ThumbHeight).width(ThumbHeight * aspect).clip(ThumbShape).background(MaterialTheme.colorScheme.surfaceVariant)
        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, ThumbShape)
        .then(if (pdf != null && page != null) Modifier.clickable(onClickLabel = "Expand sheet preview") { expanded = true } else Modifier),
        contentAlignment = Alignment.Center) {
        if (loaded != null) Image(loaded, "Sheet ${page?.pageNumber} preview", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        else Icon(Icons.Default.Image, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (expanded && pdf != null && page != null) SheetPreviewDialog(pdf, page, title, loaded) { expanded = false }
}

/** Full-size sheet diagram: the splitter's sidecar at full resolution, else the PDF's embedded diagram. Pinch to zoom. */
@Composable
private fun SheetPreviewDialog(pdf: File, page: SheetPage, title: String, placeholder: ImageBitmap?, onDismiss: () -> Unit) {
    val full by produceState(placeholder, pdf, page.pageNumber, page.thumbnailPath) {
        withContext(Dispatchers.IO) {
            runCatching {
                (com.kkc.sheettracker.ui.viewer.loadCncSidecarBitmap(pdf, page.thumbnailPath)
                    ?: com.kkc.sheettracker.ui.viewer.extractLargestEmbeddedImage(pdf, page.pageNumber - 1))?.asImageBitmap()
            }.getOrNull()
        }?.let { value = it }
    }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        StatusBorderedCard(SheetStatus.NOT_STARTED, Modifier.fillMaxWidth(0.94f)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text("Sheet ${page.pageNumber} · pinch to zoom", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Close preview") }
                }
                val image = full
                Box(Modifier.fillMaxWidth().heightIn(max = 900.dp).clip(ThumbShape).background(MaterialTheme.colorScheme.surfaceVariant)
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 6f)
                            offset = if (scale == 1f) androidx.compose.ui.geometry.Offset.Zero else offset + pan
                        }
                    }, contentAlignment = Alignment.Center) {
                    if (image != null) Image(image, "Sheet ${page.pageNumber} full preview",
                        Modifier.fillMaxWidth().aspectRatio(image.width.toFloat() / image.height)
                            .graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y },
                        contentScale = ContentScale.Fit)
                    else CircularProgressIndicator(Modifier.padding(48.dp))
                }
            }
        }
    }
}

private val RowMinHeight = 110.dp
private val ThumbHeight = 96.dp
private val ThumbShape = RoundedCornerShape(6.dp)
