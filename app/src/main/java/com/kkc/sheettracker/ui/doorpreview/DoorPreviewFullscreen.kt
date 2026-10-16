package com.kkc.sheettracker.ui.doorpreview

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.FilterChip
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.kkc.sheettracker.data.cvjob.*
import kotlin.math.roundToInt

/** In-memory, full-screen inspection of the same generated drawing shown in the parts list. */
@Composable
internal fun DoorPreviewFullscreen(door: DoorPreviewData, onDismiss: () -> Unit,
    onSelectDoor: (String) -> Boolean = { false }, doorsById: Map<String, DoorPreviewData> = mapOf(door.doorId to door)) {
    val drawing = remember(door) { buildDoorDrawing(door.geometry) }
    val regions = remember(door, drawing) { buildDoorPartRegions(door, drawing) }
    var selectedPartId by remember(door.doorId) { mutableStateOf<String?>(null) }
    val layout = door.cabinetLayout.takeUnless { drawing.refused }
    var showCabinet by rememberSaveable(door.doorId) { mutableStateOf(layout != null) }
    val cabinetMode = showCabinet && layout != null
    var selectedMemberId by remember(door.doorId) { mutableStateOf<String?>(null) }
    val selectedMember = layout?.frameMembers?.firstOrNull { it.part.partId == selectedMemberId }
    val shape: (CabinetFront) -> FrontShape = { frontShape(checkNotNull(layout), it, doorsById) }
    val front = layout?.fronts?.singleOrNull { it.doorId == door.doorId }
    val cabinetRegions = remember(door, drawing, front) {
        front?.let { cabinetDoorPartRegions(it, door, drawing) }.orEmpty()
    }
    val cabinetDrawings = remember(doorsById) {
        doorsById.mapValues { (_, preview) -> buildDoorDrawing(preview.geometry) }
    }
    val selected = if (cabinetMode) selectedMember?.let(::frameMemberRegion)
        ?: cabinetRegions.firstOrNull { it.part.partId == selectedPartId }
        else regions.firstOrNull { it.part.partId == selectedPartId }
    val measureLines = if (cabinetMode && front != null) {
        val measures = frontMeasures(checkNotNull(layout), front, shape(front))
        // The pure helper explains unmeasured shapes; the operator panel shows measured numbers only.
        if (measures.left != null) frontMeasureLines(measures) else emptyList()
    } else emptyList()
    val paddingPx = with(LocalDensity.current) { 24.dp.toPx() }
    var showHiddenLines by remember(door.doorId) { mutableStateOf(true) }
    var scale by remember(door.doorId) { mutableFloatStateOf(1f) }
    var offset by remember(door.doorId) { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val transform = rememberTransformableState { centroid, zoomChange, panChange, _ ->
        val nextScale = (scale * zoomChange).coerceIn(1f, 5f)
        val appliedZoom = nextScale / scale
        val anchor = centroid - Offset(viewport.width / 2f, viewport.height / 2f)
        val nextOffset = offset * appliedZoom + panChange + anchor * (1f - appliedZoom)
        val maxX = viewport.width * (nextScale - 1f) / 2f
        val maxY = viewport.height * (nextScale - 1f) / 2f
        scale = nextScale
        offset = Offset(nextOffset.x.coerceIn(-maxX, maxX), nextOffset.y.coerceIn(-maxY, maxY))
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Column(
            Modifier.fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .windowInsetsPadding(WindowInsets.safeDrawing)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        listOf(door.cabinet.takeIf { it.isNotBlank() }?.let { "Cab $it" }, door.label.ifBlank { "Door" })
                            .filterNotNull().joinToString(" · "),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "${formatDoorInches(drawing.width)} × ${formatDoorInches(drawing.height)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text("Hidden lines", style = MaterialTheme.typography.labelMedium)
                Switch(checked = showHiddenLines, onCheckedChange = { showHiddenLines = it })
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close door preview")
                }
            }
            if (layout != null) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(selected = cabinetMode, onClick = {
                        showCabinet = true; selectedPartId = null; selectedMemberId = null; scale = 1f; offset = Offset.Zero
                    }, label = { Text("Cabinet") })
                    FilterChip(selected = !cabinetMode, onClick = {
                        showCabinet = false; selectedPartId = null; selectedMemberId = null; scale = 1f; offset = Offset.Zero
                    }, label = { Text("Door only") })
                }
            }
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth()
                    .clipToBounds()
                    .onSizeChanged { viewport = it }
                    .pointerInput(door, paddingPx, cabinetMode, doorsById) {
                        detectTapGestures(
                            onTap = { point ->
                                if (cabinetMode) {
                                    val cabinet = checkNotNull(layout)
                                    val fit = cabinetFit(cabinet, viewport.width.toFloat(), viewport.height.toFloat(), paddingPx, shape)
                                    when (val target = hitCabinetTarget(cabinet, fit, viewport.width.toFloat(), viewport.height.toFloat(),
                                        point.x, point.y, scale, offset.x, offset.y, shape, cabinetRegions)) {
                                        is CabinetTarget.Part -> {
                                            selectedMemberId = null
                                            selectedPartId = target.region.part.partId
                                        }
                                        is CabinetTarget.Member -> {
                                            selectedPartId = null
                                            selectedMemberId = target.member.part.partId
                                        }
                                        is CabinetTarget.Front -> {
                                            selectedPartId = null
                                            selectedMemberId = null
                                            target.front.doorId?.takeIf { it != door.doorId }?.let { onSelectDoor(it) }
                                        }
                                        null -> { selectedMemberId = null; selectedPartId = null }
                                    }
                                } else {
                                    val fit = DoorPreviewFit.of(drawing.width, drawing.height,
                                        viewport.width.toFloat(), viewport.height.toFloat(), paddingPx)
                                    selectedPartId = hitDoorPart(
                                        regions, fit, viewport.width.toFloat(), viewport.height.toFloat(),
                                        point.x, point.y, zoom = scale, panX = offset.x, panY = offset.y
                                    )?.part?.partId
                                }
                            },
                            onDoubleTap = { scale = 1f; offset = Offset.Zero }
                        )
                    }
                    .transformable(transform)
                    .semantics {
                        contentDescription = if (cabinetMode) "Full screen cabinet drawing" else "Full screen door drawing"
                        stateDescription = "${(scale * 100).roundToInt()}% zoom"
                    }
            ) {
                val drawingModifier = Modifier.fillMaxSize().graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    }
                if (cabinetMode) CabinetFrontCanvas(checkNotNull(layout), door.doorId, drawing, selectedMember,
                    modifier = drawingModifier, showHiddenLines = showHiddenLines, frontShape = shape,
                    doorDrawings = cabinetDrawings, selectedPart = selected.takeIf { selectedMember == null })
                else DoorPreviewCanvas(
                    drawing = drawing,
                    modifier = drawingModifier,
                    padding = 24.dp,
                    showHiddenLines = showHiddenLines,
                    selectedPartRect = selected?.rect,
                    selectedPartPolygon = selected?.polygon
                )
            }
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 112.dp).padding(horizontal = 16.dp, vertical = 12.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (selected == null && cabinetMode) {
                        Text(door.label.ifBlank { "Door" }, style = MaterialTheme.typography.titleSmall)
                        Text("${formatDoorInches(drawing.width)} × ${formatDoorInches(drawing.height)} in",
                            style = MaterialTheme.typography.bodyMedium)
                        if (measureLines.isNotEmpty()) Text(measureLines.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                    } else if (selected == null) {
                        Text(
                            if (regions.isEmpty()) "Part positions unavailable" else "Tap a part to inspect it",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            if (regions.isEmpty()) "See the parts list for names and sizes" else "Name, cut size and material",
                            style = MaterialTheme.typography.bodySmall
                        )
                    } else {
                        val part = selected.part
                        Text(
                            part.description.ifBlank { doorRoleLabel(part.role) },
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        val sizeLines = doorPartSizeLines(selected)
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text(sizeLines.first(), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            Text("Qty ${part.quantity}", style = MaterialTheme.typography.bodyMedium)
                        }
                        sizeLines.drop(1).forEach { line ->
                            Text(line, style = MaterialTheme.typography.bodyMedium)
                        }
                        if (part.material.isNotBlank()) Text(
                            part.material, style = MaterialTheme.typography.bodySmall,
                            maxLines = 2, overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            Text(
                text = "Pinch to zoom · Double-tap to reset",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(12.dp)
            )
        }
    }
}
