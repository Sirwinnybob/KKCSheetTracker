package com.kkc.sheettracker.ui.doorpreview

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.data.cvjob.CvJobDoorPreviews
import com.kkc.sheettracker.data.cvjob.DoorHingeSide
import com.kkc.sheettracker.data.cvjob.DoorPart
import com.kkc.sheettracker.data.cvjob.DoorPreviewData
import com.kkc.sheettracker.ui.theme.DimensionTextStyle
import com.kkc.sheettracker.ui.theme.KKCShapeTokens
import com.kkc.sheettracker.ui.theme.KKCSpacing
import com.kkc.sheettracker.ui.theme.KKCThemeColors
import com.kkc.sheettracker.ui.theme.LocalKKCIsDarkTheme
import com.kkc.sheettracker.ui.theme.kkcZebraTint

/*
 * Door Assembly tab of the Hardwoods workspace: one collapsible card per door (drawing thumbnail,
 * label, cabinet, size, style, hinges) that opens to the full drawing and every part of the door.
 * Data comes only from CV Cutlist Query's job_data.json (read-only).
 */

/** Doors in shop order: cabinet number, then label. */
internal fun doorAssemblyDoors(previews: CvJobDoorPreviews?): List<DoorPreviewData> {
    val loaded = previews as? CvJobDoorPreviews.Loaded ?: return emptyList()
    return loaded.doors.sortedWith(
        compareBy<DoorPreviewData>(
            { it.cabinet.toIntOrNull() ?: Int.MAX_VALUE },
            { it.cabinet },
            { it.label },
            { it.doorId.toLongOrNull() ?: Long.MAX_VALUE }
        )
    )
}

/** Identical parts of one door shown once with a count (two hinges, two bumpers...). */
data class DoorPartLine(val part: DoorPart, val count: Int)

private val DOOR_ROLE_ORDER = listOf(
    "leftStile", "rightStile", "topRail", "bottomRail", "member", "panel", "slab", "banding",
    "hinge", "hingePlate", "pull", "bumper"
)

private val DOOR_HARDWARE_ROLES = setOf("hinge", "hingePlate", "pull", "bumper")

internal fun isDoorHardware(role: String): Boolean = role in DOOR_HARDWARE_ROLES

/** Frame members first, then panel/slab, then hardware; identical parts merged. */
internal fun groupDoorParts(parts: List<DoorPart>): List<DoorPartLine> =
    parts
        .groupBy { listOf(it.role, it.description, it.material, it.width, it.length) }
        .values
        .map { same -> DoorPartLine(same.first(), same.size) }
        .sortedBy { line -> DOOR_ROLE_ORDER.indexOf(line.part.role).let { if (it < 0) DOOR_ROLE_ORDER.size else it } }

internal fun doorRoleLabel(role: String): String = when (role) {
    "leftStile" -> "Left stile"
    "rightStile" -> "Right stile"
    "topRail" -> "Top rail"
    "bottomRail" -> "Bottom rail"
    "member" -> "Member"
    "panel" -> "Panel"
    "slab" -> "Slab"
    "banding" -> "Banding"
    "hinge" -> "Hinge"
    "hingePlate" -> "Hinge plate"
    "pull" -> "Pull"
    "bumper" -> "Bumper"
    else -> "Other"
}

/** Cut size as the cut lists show it (`2.2813 x 31.5625`); hardware sizes are not shown. */
internal fun doorPartSizeText(part: DoorPart): String {
    if (isDoorHardware(part.role)) return ""
    val width = part.width.trim()
    val length = part.length.trim()
    return if (width.isNotEmpty() && length.isNotEmpty()) "$width x $length" else ""
}

/** `16 5/8 × 31 1/2 · 2 1/4" Flat Panel · Hinged left`. */
internal fun doorHeaderSubtitle(door: DoorPreviewData): String {
    val g = door.geometry
    val hinged = hingeCups(g).isNotEmpty()
    val hinge = when {
        !hinged -> "No hinges"
        g.hinges.side == DoorHingeSide.LEFT -> "Hinged left"
        g.hinges.side == DoorHingeSide.RIGHT -> "Hinged right"
        else -> "Hinged top"
    }
    return listOf("${formatDoorInches(g.width)} × ${formatDoorInches(g.height)}", door.style, hinge)
        .filter { it.isNotBlank() }
        .joinToString(" · ")
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DoorAssemblyList(
    previews: CvJobDoorPreviews?,
    stateKey: String,
    modifier: Modifier = Modifier,
    bottomPadding: Dp = 0.dp
) {
    val doors = remember(previews) { doorAssemblyDoors(previews) }
    val message = doorPreviewStatusMessage(previews, doors.size)
    if (message != null) {
        Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
        return
    }
    var expandedIds by rememberSaveable(stateKey) { mutableStateOf(listOf<String>()) }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 6.dp, bottom = bottomPadding)
    ) {
        doors.forEach { door ->
            val expanded = door.doorId in expandedIds
            stickyHeader(key = "door:${door.doorId}") {
                DoorAssemblyHeader(
                    door = door,
                    expanded = expanded,
                    onToggle = {
                        expandedIds = if (expanded) expandedIds - door.doorId else expandedIds + door.doorId
                    }
                )
            }
            if (expanded) {
                item(key = "door-body:${door.doorId}") {
                    DoorAssemblyBody(door)
                }
            }
        }
    }
}

@Composable
private fun DoorAssemblyHeader(door: DoorPreviewData, expanded: Boolean, onToggle: () -> Unit) {
    val drawing = remember(door) { buildDoorDrawing(door.geometry) }
    val partCount = remember(door) { door.parts.size }
    val scheme = MaterialTheme.colorScheme
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(durationMillis = 300),
        label = "doorChevron"
    )
    val shape = if (expanded) RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp) else RoundedCornerShape(8.dp)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = if (expanded) 0.dp else 4.dp)
            .clickable(onClick = onToggle),
        color = scheme.surface,
        shape = shape,
        shadowElevation = if (expanded) 0.dp else 2.dp,
        border = BorderStroke(1.dp, if (expanded) scheme.primary.copy(alpha = 0.45f) else scheme.outlineVariant.copy(alpha = 0.55f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .background(
                    Brush.verticalGradient(
                        if (expanded) {
                            listOf(scheme.primary.copy(alpha = 0.10f), scheme.primary.copy(alpha = 0.02f))
                        } else {
                            listOf(scheme.surfaceVariant.copy(alpha = 0.70f), scheme.surfaceVariant.copy(alpha = 0.35f))
                        }
                    )
                )
        ) {
            Box(
                modifier = Modifier
                    .width(5.dp)
                    .fillMaxHeight()
                    .background(Brush.verticalGradient(listOf(scheme.primary.copy(alpha = 0.7f), scheme.primary.copy(alpha = 0.3f))))
            )
            Row(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = KKCSpacing.l, vertical = KKCSpacing.inCardSpacing),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                DoorPreviewCanvas(
                    drawing = drawing,
                    modifier = Modifier.size(width = 36.dp, height = 46.dp),
                    padding = 2.dp,
                    showHiddenLines = false
                )
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = door.label.ifBlank { "Door" },
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = scheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (door.cabinet.isNotBlank()) HeaderChip("Cab ${door.cabinet}")
                        HeaderChip("$partCount ${if (partCount == 1) "part" else "parts"}")
                    }
                    Text(
                        text = doorHeaderSubtitle(door),
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (drawing.warnings.isNotEmpty()) DoorWarningBadge(drawing.warnings.size)
                Surface(
                    shape = CircleShape,
                    color = scheme.surface.copy(alpha = 0.60f),
                    border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.40f)),
                    modifier = Modifier.size(28.dp)
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Icon(
                            imageVector = Icons.Default.ArrowDropDown,
                            contentDescription = if (expanded) "Collapse door" else "Expand door",
                            tint = scheme.onSurfaceVariant,
                            modifier = Modifier.rotate(chevronRotation)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HeaderChip(text: String) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.60f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun DoorAssemblyBody(door: DoorPreviewData) {
    val drawing = remember(door) { buildDoorDrawing(door.geometry) }
    val lines = remember(door) { groupDoorParts(door.parts) }
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        shape = RoundedCornerShape(bottomStart = 8.dp, bottomEnd = 8.dp),
        color = scheme.surface,
        shadowElevation = 2.dp,
        border = BorderStroke(1.dp, scheme.primary.copy(alpha = 0.45f))
    ) {
        BoxWithConstraints(modifier = Modifier.padding(12.dp)) {
            if (maxWidth >= 560.dp) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    DoorDrawingPanel(door, drawing, Modifier.width(250.dp))
                    DoorPartsColumn(lines, Modifier.weight(1f))
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    DoorDrawingPanel(door, drawing, Modifier.fillMaxWidth())
                    DoorPartsColumn(lines, Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun DoorDrawingPanel(door: DoorPreviewData, drawing: DoorDrawing, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    val details = remember(door, drawing) { doorDetailLines(door, drawing).drop(1) }
    val ratio = (drawing.width / drawing.height).toFloat().coerceIn(0.75f, 2.2f)
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = scheme.surfaceContainerLow,
            border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.4f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                DoorPreviewCanvas(
                    drawing = drawing,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 340.dp).aspectRatio(ratio),
                    padding = 14.dp
                )
            }
        }
        Text(
            text = "${formatDoorInches(drawing.width)} × ${formatDoorInches(drawing.height)}" +
                (door.geometry.thickness?.let { " × ${formatDoorInches(it)}" } ?: ""),
            style = DimensionTextStyle,
            color = scheme.onSurface,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center
        )
        details.forEach { line ->
            Text(line, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        }
        drawing.warnings.forEach { warning ->
            Text(warning, style = MaterialTheme.typography.bodySmall, color = KKCThemeColors.statusColors.bad)
        }
    }
}

@Composable
private fun DoorPartsColumn(lines: List<DoorPartLine>, modifier: Modifier) {
    Column(modifier = modifier) {
        Text(
            "PARTS",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 2.dp)
        )
        if (lines.isEmpty()) {
            Text(
                "No parts listed for this door.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(4.dp)
            )
        }
        lines.forEachIndexed { index, line -> DoorPartRow(line, index) }
    }
}

@Composable
private fun DoorPartRow(line: DoorPartLine, zebraIndex: Int) {
    val scheme = MaterialTheme.colorScheme
    val part = line.part
    val backdrop = if (LocalKKCIsDarkTheme.current) Color(0xFF22252A) else Color.White
    val rowColor = kkcZebraTint(zebraIndex).compositeOver(backdrop)
    val roleColor = when {
        isDoorHardware(part.role) -> scheme.outline
        part.role == "panel" || part.role == "slab" -> scheme.tertiary
        else -> scheme.primary
    }
    val size = doorPartSizeText(part)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 36.dp)
            .padding(horizontal = 2.dp, vertical = 4.dp),
        shape = RoundedCornerShape(8.dp),
        color = rowColor,
        shadowElevation = 2.dp,
        border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.4f))
    ) {
        Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .width(KKCShapeTokens.statusBorderWidth)
                    .fillMaxHeight()
                    .background(roleColor)
            )
            Row(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (size.isNotEmpty()) Text(size, style = DimensionTextStyle)
                        Text(
                            part.description.ifBlank { doorRoleLabel(part.role) },
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurface,
                            fontStyle = FontStyle.Italic,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        listOf(part.material, doorRoleLabel(part.role)).filter { it.isNotBlank() }.joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (line.count > 1) HeaderChip("× ${line.count}")
            }
        }
    }
}
