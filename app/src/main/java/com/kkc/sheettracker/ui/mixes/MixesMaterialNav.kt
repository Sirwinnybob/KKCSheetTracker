package com.kkc.sheettracker.ui.mixes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.kkc.sheettracker.ui.settings.GroupCard
import com.kkc.sheettracker.ui.settings.GroupCaption
import com.kkc.sheettracker.ui.theme.LocalKKCStatusColors

internal fun useRail(widthDp: Int): Boolean = widthDp >= 1000
internal fun stripColumns(widthDp: Int): Int = (widthDp / 240).coerceAtLeast(1)
internal fun selectAvailableMaterial(current: String?, materials: List<String>): String? =
    if (materials.isEmpty() || current in materials) current else materials.first()

/** Pure drop resolution: the group under the pointer, unless it is the tile's own machine. */
internal fun dropTarget(pointer: Offset, groups: Map<String, Rect>, sourceMachineId: String): String? =
    groups.entries.firstOrNull { it.value.contains(pointer) }?.key?.takeIf { it != sourceMachineId }

private data class DragState(val machineId: String, val material: String, val origin: Offset, val delta: Offset) {
    val pointer: Offset get() = origin + delta
}

/**
 * Materials grouped per machine (landscape rail A / portrait strip P2). Long-press a tile and drag it onto
 * another machine's group to request a move. Gestures stay on the tiles, so sheet reordering is unaffected.
 */
@Composable
internal fun MixesMaterialNav(groups: List<MachineGroupModel>, rail: Boolean, widthDp: Int,
    onSelect: (machineId: String, material: String) -> Unit, modifier: Modifier = Modifier,
    onMove: ((fromMachineId: String, material: String, toMachineId: String) -> Unit)? = null) {
    var drag by remember { mutableStateOf<DragState?>(null) }
    val bounds = remember { mutableStateMapOf<String, Rect>() }
    val hover = drag?.let { dropTarget(it.pointer, bounds, it.machineId) }
    val scheme = MaterialTheme.colorScheme
    GroupCard(modifier = modifier.testTag(if (rail) "mixes-material-rail" else "mixes-material-strip")) {
        Column(Modifier.padding(horizontal = 6.dp).heightIn(max = if (rail) 1000.dp else 320.dp)
            .verticalScroll(rememberScrollState(), enabled = drag == null)) {
            groups.forEach { group ->
                val targeted = hover == group.machineId
                Column(Modifier.fillMaxWidth().padding(bottom = 6.dp)
                    .onGloballyPositioned { bounds[group.machineId] = it.boundsInRoot() }
                    .clip(RoundedCornerShape(10.dp))
                    .then(if (targeted) Modifier.border(2.dp, scheme.primary, RoundedCornerShape(10.dp))
                        .background(scheme.primaryContainer.copy(alpha = 0.35f)) else Modifier)
                    .testTag("mixes-machine-group-${group.machineId}")) {
                    GroupCaption("${group.name} · ${machineStatusText(group.status)}", Modifier.padding(horizontal = 6.dp, vertical = 8.dp))
                    if (group.items.isEmpty()) {
                        Box(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp).heightIn(min = 56.dp)
                            .border(1.dp, if (targeted) scheme.primary else scheme.outlineVariant, RoundedCornerShape(9.dp))
                            .padding(12.dp)) {
                            Text(if (drag != null) "Drop here to move to ${group.name}" else "No materials on ${group.name}",
                                style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                        }
                    }
                    val columns = if (rail) 1 else stripColumns(widthDp)
                    group.items.chunked(columns).forEach { chunk ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            chunk.forEach { item -> key(item.machineId, item.material) {
                                val dragging = drag?.let { it.machineId == item.machineId && it.material == item.material } == true
                                val others = groups.filter { it.machineId != item.machineId }
                                MaterialTile(item, { onSelect(item.machineId, item.material) }, Modifier.weight(1f)
                                    .zIndex(if (dragging) 1f else 0f)
                                    .graphicsLayer {
                                        if (dragging) { translationX = drag!!.delta.x; translationY = drag!!.delta.y; shadowElevation = 12f }
                                    }
                                    .then(if (onMove != null && item.transfer == null) Modifier.materialDrag(item, onStart = { drag = it },
                                        onDelta = { d -> drag = drag?.copy(delta = drag!!.delta + d) },
                                        onEnd = {
                                            val state = drag
                                            drag = null
                                            state?.let { s -> dropTarget(s.pointer, bounds, s.machineId)?.let { onMove(s.machineId, s.material, it) } }
                                        }, onCancel = { drag = null })
                                        .semantics {
                                            // The menu alternative to dragging, also reachable by accessibility services.
                                            customActions = others.map { target -> CustomAccessibilityAction("Move to ${target.name}") {
                                                onMove(item.machineId, item.material, target.machineId); true
                                            } }
                                        } else Modifier))
                            } }
                            repeat(columns - chunk.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Modifier.materialDrag(item: MaterialNavItem, onStart: (DragState) -> Unit, onDelta: (Offset) -> Unit,
    onEnd: () -> Unit, onCancel: () -> Unit): Modifier {
    var origin by remember(item.machineId, item.material) { mutableStateOf(Offset.Zero) }
    val start by rememberUpdatedState(onStart)
    val delta by rememberUpdatedState(onDelta)
    val end by rememberUpdatedState(onEnd)
    val cancel by rememberUpdatedState(onCancel)
    // The origin is read once at drag start; later layout passes (including the drag translation) don't move it.
    return onGloballyPositioned { origin = it.positionInRoot() }
        .pointerInput(item.machineId, item.material) {
            detectDragGesturesAfterLongPress(
                onDragStart = { at -> start(DragState(item.machineId, item.material, origin + at, Offset.Zero)) },
                onDrag = { change, amount -> change.consume(); delta(amount) },
                onDragEnd = { end() },
                onDragCancel = { cancel() },
            )
        }
}

@Composable
private fun MaterialTile(item: MaterialNavItem, onSelect: () -> Unit, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    val status = LocalKKCStatusColors.current
    val (label, tone) = when (val chip = item.chip) {
        is MaterialChip.Left -> "${chip.n} left" to if (chip.interrupted) status.skip else scheme.primary
        MaterialChip.Done -> "Done" to status.complete
        MaterialChip.NoMix -> "No mix" to status.bad
        MaterialChip.NoPgms -> "No PGMs" to scheme.onSurfaceVariant
        MaterialChip.Offline -> "Offline" to scheme.onSurfaceVariant
    }
    Column(modifier.padding(bottom = 4.dp).clip(RoundedCornerShape(9.dp))
        .background(if (item.selected) scheme.secondaryContainer else scheme.surface)
        .selectable(item.selected, role = Role.Tab, onClick = onSelect)
        .heightIn(min = 64.dp).padding(10.dp).testTag("mixes-material-${item.machineId}-${item.material}"),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(item.material, style = MaterialTheme.typography.bodyMedium, fontWeight = if (item.selected) FontWeight.SemiBold else FontWeight.Normal)
        Text(label, style = MaterialTheme.typography.labelMedium, color = tone)
        item.transfer?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = status.skip) }
    }
}
