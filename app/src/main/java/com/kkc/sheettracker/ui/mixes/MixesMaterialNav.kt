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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.kkc.sheettracker.data.cnc.MachineStatus
import com.kkc.sheettracker.data.models.SheetStatus
import com.kkc.sheettracker.ui.components.KKCPillStyle
import com.kkc.sheettracker.ui.components.StatusBorderedCard
import com.kkc.sheettracker.ui.components.icons.ActionMixesIcon
import com.kkc.sheettracker.ui.components.kkcPillIndicator
import com.kkc.sheettracker.ui.components.rememberKKCPillStyle
import com.kkc.sheettracker.ui.dashboard.DashboardAccent
import com.kkc.sheettracker.ui.dashboard.DashboardAccentPill
import com.kkc.sheettracker.ui.dashboard.DashboardHeaderIcon
import com.kkc.sheettracker.ui.theme.KKCShapeTokens
import com.kkc.sheettracker.ui.theme.KKCThemeColors

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
    onMove: ((fromMachineId: String, material: String, toMachineId: String) -> Unit)? = null,
    moveInFlight: MoveInFlight? = null) {
    var drag by remember { mutableStateOf<DragState?>(null) }
    val bounds = remember { mutableStateMapOf<String, Rect>() }
    val hover = drag?.let { dropTarget(it.pointer, bounds, it.machineId) }
    val scheme = MaterialTheme.colorScheme
    // One card per machine. Cards paint their depth on a background layer so a dragged chip is not clipped
    // by its own card while it crosses into another machine's card.
    val pill = rememberKKCPillStyle()
    val groupCard: @Composable (MachineGroupModel, Modifier) -> Unit = { group, cardModifier ->
        // A dropped material shows as arriving on its target until the move review (or a refusal) appears.
        val inbound = moveInFlight?.takeIf { it.toMachineId == group.machineId }
        val targeted = hover == group.machineId || inbound != null
        Box(cardModifier
            .zIndex(if (drag?.machineId == group.machineId) 1f else 0f)
            .onGloballyPositioned { bounds[group.machineId] = it.boundsInRoot() }
            .testTag("mixes-machine-group-${group.machineId}")) {
            // The status card is a background layer: its Card clips, and a dragged chip must not be clipped.
            StatusBorderedCard(machineCardStatus(group.status), Modifier.matchParentSize()
                .then(if (targeted) Modifier.border(2.dp, scheme.primary, MaterialTheme.shapes.medium) else Modifier),
                containerColor = if (targeted) scheme.primaryContainer.copy(alpha = 0.45f).compositeOver(scheme.surface) else scheme.surface) {}
            Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                MachineHeader(group)
                if (group.items.isEmpty() && inbound == null) {
                    Box(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .border(1.dp, if (targeted) scheme.primary else scheme.outlineVariant, ChipShape)
                        .padding(horizontal = 14.dp, vertical = 12.dp)) {
                        Text(if (drag != null) "Drop here to move to ${group.name}" else "No materials on ${group.name}",
                            style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                    }
                }
                if (group.items.isNotEmpty() || inbound != null) MixesPillTrack(style = pill) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
                    itemVerticalAlignment = Alignment.CenterVertically) {
                    inbound?.let { InboundMaterialTile(it.material, group.name) }
                    group.items.forEach { item -> key(item.machineId, item.material) {
                                val dragging = drag?.let { it.machineId == item.machineId && it.material == item.material } == true
                                val others = groups.filter { it.machineId != item.machineId }
                                val outgoing = moveInFlight?.let { it.material == item.material && it.fromMachineId == item.machineId } == true
                                MaterialTile(item, pill, { onSelect(item.machineId, item.material) }, Modifier
                                    .alpha(if (outgoing) 0.4f else 1f)
                                    .zIndex(if (dragging) 1f else 0f)
                                    .graphicsLayer {
                                        // This block re-runs on its own when drag changes, before recomposition updates
                                        // `dragging`, so a drop (drag = null) must not be dereferenced here.
                                        val current = drag
                                        if (dragging && current != null) {
                                            translationX = current.delta.x; translationY = current.delta.y
                                            shape = ChipShape; shadowElevation = 12f
                                        }
                                    }
                                    .then(if (onMove != null && item.transfer == null) Modifier.materialDrag(item, onStart = { drag = it },
                                        onDelta = { d -> drag = drag?.let { it.copy(delta = it.delta + d) } },
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
                }
                }
            }
        }
    }
    // Padding inside the scroll keeps the card shadows from being clipped at its edges.
    val scroll = Modifier.verticalScroll(rememberScrollState(), enabled = drag == null).padding(4.dp)
    if (rail) {
        Column(modifier.heightIn(max = 1000.dp).then(scroll).testTag("mixes-material-rail"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            groups.forEach { groupCard(it, Modifier.fillMaxWidth()) }
        }
    } else {
        Row(modifier.fillMaxWidth().heightIn(max = 320.dp).then(scroll).testTag("mixes-material-strip"),
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // Wider cards for machines with more materials; stripColumns keeps a single narrow screen to one column.
            if (stripColumns(widthDp) == 1) Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                groups.forEach { groupCard(it, Modifier.fillMaxWidth()) }
            } else groups.forEach { groupCard(it, Modifier.weight(it.items.size.coerceIn(1, 3).toFloat())) }
        }
    }
}

private val ChipShape = RoundedCornerShape(6.dp)

/** A drop whose move checks and preview are still running; the source tile dims while the target shows [InboundMaterialTile]. */
internal data class MoveInFlight(val material: String, val fromMachineId: String, val toMachineId: String)

/** Placeholder for a dropped material on its target machine while the move is being checked. */
@Composable
private fun InboundMaterialTile(material: String, machineName: String) {
    val scheme = MaterialTheme.colorScheme
    Row(Modifier.heightIn(min = 44.dp).background(scheme.surface, ChipShape).border(1.5.dp, scheme.primary, ChipShape)
        .semantics(mergeDescendants = true) {}.padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = scheme.primary)
        Column {
            Text(material, style = MaterialTheme.typography.labelLarge, color = scheme.onSurface)
            Text("Checking move to $machineName", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
        }
    }
}

private fun machineCardStatus(status: MachineStatus): SheetStatus = when {
    status !is MachineStatus.Live -> SheetStatus.SKIPPED
    status.producing == true -> SheetStatus.COMPLETE
    status.producing == false && status.state == "ready" -> SheetStatus.IN_PROGRESS
    else -> SheetStatus.NOT_STARTED
}

@Composable
private fun MachineHeader(group: MachineGroupModel) {
    val scheme = MaterialTheme.colorScheme
    val live = group.status as? MachineStatus.Live
    val (label, accent) = when {
        live == null -> "Unavailable" to DashboardAccent.WARNING
        live.producing == true -> "Cutting" to DashboardAccent.SUCCESS
        live.producing == false && live.state == "ready" -> "Ready" to DashboardAccent.INFO
        live.producing == false -> "Idle" to DashboardAccent.NEUTRAL
        else -> "Unknown" to DashboardAccent.NEUTRAL
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        DashboardHeaderIcon(ActionMixesIcon)
        Text(group.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        DashboardAccentPill(label, accent)
        live?.loaded?.name?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
private fun MaterialTile(item: MaterialNavItem, style: KKCPillStyle, onSelect: () -> Unit, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    val status = KKCThemeColors.statusColors
    // Status as the app's filled count chips, readable on any pill or track color.
    val (label, tone) = when (val chip = item.chip) {
        is MaterialChip.Left -> "${chip.n} left" to if (chip.interrupted) status.skipBorder else status.inProgress
        MaterialChip.Done -> "Done" to status.completeBorder
        MaterialChip.NoMix -> "No mix" to status.bad
        MaterialChip.NoPgms -> "No PGMs" to status.notStarted
        MaterialChip.Offline -> "Offline" to status.notStarted
    }
    // Every material is its own pill on the track: the selected one wears the sliding-pill fill, the rest a plain
    // surface pill, so neighbours read as separate buttons on any track color.
    val text = if (item.selected) style.selectedText else scheme.onSurface
    Row(modifier.heightIn(min = 44.dp)
        .then(if (item.selected) Modifier.kkcPillIndicator(style)
            else Modifier.background(scheme.surface, ChipShape).border(1.dp, scheme.outlineVariant, ChipShape))
        .clip(ChipShape)
        .selectable(item.selected, role = Role.Tab, onClick = onSelect)
        .padding(horizontal = 12.dp, vertical = 6.dp).testTag("mixes-material-${item.machineId}-${item.material}"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column {
            Text(item.material, style = MaterialTheme.typography.labelLarge, fontWeight = if (item.selected) FontWeight.SemiBold else FontWeight.Normal, color = text)
            item.transfer?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = text) }
        }
        Text(label, Modifier.clip(KKCShapeTokens.pill).background(tone).padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = Color.White)
    }
}
