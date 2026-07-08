package com.kkc.sheettracker.ui.hardwoods

import android.view.MotionEvent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import com.kkc.sheettracker.data.models.HardwoodCutlistRow
import com.kkc.sheettracker.data.models.HardwoodDocType
import com.kkc.sheettracker.data.models.HardwoodInkStroke
import com.kkc.sheettracker.data.models.HardwoodRowProgress
import com.kkc.sheettracker.ui.markup.distanceToScaledStroke
import com.kkc.sheettracker.ui.markup.DrawingTool
import com.kkc.sheettracker.ui.markup.PdfMarkupToolState
import com.kkc.sheettracker.ui.markup.resolveEffectiveDrawingTool
import com.kkc.sheettracker.ui.markup.shouldAppendStrokePoint
import com.kkc.sheettracker.ui.components.LocalNavBarDecoration
import com.kkc.sheettracker.ui.theme.DimensionTextStyle
import com.kkc.sheettracker.ui.theme.KKCThemeColors
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.max
import kotlin.math.sqrt

private data class TallyHitTarget(
    val boundsInRoot: Rect,
    val enabled: Boolean,
    val onTap: () -> Unit
)

internal fun calculateClassicViewFitWidthScale(
    viewportWidthPx: Int,
    contentWidthPx: Int,
    minScale: Float = 0.7f,
    maxScale: Float = 2.5f
): Float {
    if (viewportWidthPx <= 0 || contentWidthPx <= 0) return 0.85f.coerceIn(minScale, maxScale)
    return (viewportWidthPx.toFloat() / contentWidthPx.toFloat()).coerceIn(minScale, maxScale)
}

internal fun classicRowLongPressEnabled(allowFingerDrawing: Boolean): Boolean = !allowFingerDrawing

internal fun classicTallyActionsEnabled(
    activeTool: DrawingTool,
    allowFingerDrawing: Boolean
): Boolean = !(allowFingerDrawing && activeTool != DrawingTool.PAN_ZOOM)

internal fun classicPointerCanDraw(
    pointerType: PointerType,
    allowFingerDrawing: Boolean
): Boolean = pointerType == PointerType.Stylus || pointerType == PointerType.Eraser || allowFingerDrawing

internal fun classicCutListBottomScrollPadding() = 200.dp

/**
 * The table's graphicsLayer scales from its top-left corner (transformOrigin = 0,0), so a
 * pinch centroid is already expressed in the same scaled, scroll-content-local coordinate
 * space as [androidx.compose.foundation.ScrollState]'s offset. Returns the (x, y) delta to
 * apply via `scrollBy` so the content under the pinch centroid stays under the fingers
 * instead of the table growing away from them.
 */
internal fun computeClassicZoomScrollDelta(
    oldScale: Float,
    newScale: Float,
    centroidX: Float,
    centroidY: Float
): Pair<Float, Float> {
    val appliedZoomChange = newScale / oldScale - 1f
    return (centroidX * appliedZoomChange) to (centroidY * appliedZoomChange)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ClassicCutListTable(
    docType: HardwoodDocType,
    rows: List<HardwoodCutlistRow>,
    rowProgressMap: Map<Pair<String, String>, HardwoodRowProgress>,
    onIncrementProgress: (rowId: String, currentDone: Int, maxQty: Int) -> Unit,
    onDecrementProgress: (rowId: String, currentDone: Int, maxQty: Int) -> Unit,
    onToggleSkip: (rowId: String, currentSkipped: Boolean) -> Unit,
    onCompleteProgress: (rowId: String, qty: Int) -> Unit,
    onZeroProgress: (rowId: String, qty: Int) -> Unit,
    activeStrokes: List<HardwoodInkStroke>,
    onSaveStrokes: (strokes: List<HardwoodInkStroke>, deletedIds: List<String>) -> Unit,
    onRowLongPress: (HardwoodCutlistRow) -> Unit,
    isDarkTheme: Boolean,
    widthColorBands: Map<String, Color>,
    toolState: PdfMarkupToolState,
    modifier: Modifier = Modifier,
    showMarkupToolbar: Boolean = true,
    hostMarkupToolbarInNavBar: Boolean = false
) {
    val haptic = LocalHapticFeedback.current
    var classicPage by rememberSaveable(docType.name) { mutableIntStateOf(1) }
    
    // Group rows by page
    val pages = remember(rows) {
        rows.map { it.page }.distinct().sorted().ifEmpty { listOf(1) }
    }
    if (classicPage !in pages && pages.isNotEmpty()) {
        classicPage = pages.first()
    }
    
    val pageRows = remember(rows, classicPage) {
        rows.filter { it.page == classicPage }
    }
    
    // Group page rows by material for headers
    val groupedPageRows = remember(pageRows) {
        pageRows.groupBy { it.material?.trim().orEmpty().ifBlank { "Unassigned" } }
    }

    val activeTool = resolveEffectiveDrawingTool(
        selectedTool = toolState.selectedTool,
        isTemporaryEraserActive = toolState.isStylusButtonEraserActive
    )
    val activeColor = toolState.activeColor
    val activeThickness = if (activeTool == DrawingTool.HIGHLIGHTER) 24f else 4f
    val allowFingerDrawing = toolState.allowFingerDrawing
    val isViewLocked = allowFingerDrawing && activeTool != DrawingTool.PAN_ZOOM

    val localStrokes = remember { mutableStateListOf<HardwoodInkStroke>() }
    val localDeletedIds = remember { mutableStateListOf<String>() }
    val tallyHitTargets = remember { mutableStateMapOf<String, TallyHitTarget>() }
    var showNavMarkupControls by rememberSaveable(docType.name) { mutableStateOf(true) }
    val tallyActionsEnabled = classicTallyActionsEnabled(activeTool, allowFingerDrawing)

    // Sync in-memory drawing list with repository updates
    LaunchedEffect(activeStrokes) {
        localStrokes.clear()
        localStrokes.addAll(activeStrokes)
    }

    LaunchedEffect(docType, classicPage) {
        tallyHitTargets.clear()
    }

    val navBarDeco = LocalNavBarDecoration.current
    DisposableEffect(
        navBarDeco,
        hostMarkupToolbarInNavBar,
        showNavMarkupControls,
        activeTool,
        activeColor,
        allowFingerDrawing,
        docType,
        classicPage,
        localStrokes.size,
        localDeletedIds.size
    ) {
        if (hostMarkupToolbarInNavBar) {
            navBarDeco.extendedControls = {
                if (showNavMarkupControls) {
                    IconButton(onClick = { showNavMarkupControls = false }) {
                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Hide pen controls")
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = { toolState.selectedTool = DrawingTool.PEN },
                            colors = IconButtonDefaults.iconButtonColors(
                                containerColor = if (activeTool == DrawingTool.PEN) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
                            )
                        ) {
                            Icon(Icons.Default.Create, contentDescription = "Pen Tool")
                        }
                        IconButton(
                            onClick = { toolState.selectedTool = DrawingTool.HIGHLIGHTER },
                            colors = IconButtonDefaults.iconButtonColors(
                                containerColor = if (activeTool == DrawingTool.HIGHLIGHTER) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
                            )
                        ) {
                            Icon(Icons.Default.BorderColor, contentDescription = "Highlighter Tool")
                        }
                        IconButton(
                            onClick = { toolState.selectedTool = DrawingTool.ERASER },
                            colors = IconButtonDefaults.iconButtonColors(
                                containerColor = if (activeTool == DrawingTool.ERASER) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
                            )
                        ) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = "Eraser Tool")
                        }
                    }

                    if (activeTool == DrawingTool.PEN || activeTool == DrawingTool.HIGHLIGHTER) {
                        val colors = listOf(Color.Red, Color.Blue, Color.Green, Color.Black, Color(0xFFE5A823))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            colors.forEach { color ->
                                Box(
                                    modifier = Modifier
                                        .size(24.dp)
                                        .background(
                                            if (isDarkTheme && color == Color.Black) Color.White else color,
                                            shape = CircleShape
                                        )
                                        .border(
                                            width = if (activeColor == color) 2.dp else 1.dp,
                                            color = if (activeColor == color) MaterialTheme.colorScheme.primary else Color.Gray.copy(alpha = 0.5f),
                                            shape = CircleShape
                                        )
                                        .clickable { toolState.activeColor = color }
                                )
                            }
                        }
                    }

                    FilterChip(
                        selected = activeTool != DrawingTool.PAN_ZOOM,
                        onClick = {
                            toolState.selectedTool = if (activeTool == DrawingTool.PAN_ZOOM) DrawingTool.PEN else DrawingTool.PAN_ZOOM
                        },
                        label = { Text("Draw") },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Edit,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    )

                    FilterChip(
                        selected = allowFingerDrawing,
                        onClick = { toolState.allowFingerDrawing = !toolState.allowFingerDrawing },
                        label = { Text("Finger Draw") },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Gesture,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    )

                    IconButton(
                        onClick = {
                            val mine = localStrokes.lastOrNull {
                                it.docType == docType.name && it.page == classicPage && it.id !in localDeletedIds
                            }
                            if (mine != null) {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                localDeletedIds.add(mine.id)
                                onSaveStrokes(
                                    localStrokes.filter { it.id !in localDeletedIds },
                                    localDeletedIds.toList()
                                )
                            }
                        },
                        enabled = localStrokes.any {
                            it.docType == docType.name && it.page == classicPage && it.id !in localDeletedIds
                        }
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo")
                    }

                    IconButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            val pageStrokes = localStrokes.filter { it.docType == docType.name && it.page == classicPage }
                            pageStrokes.forEach { localDeletedIds.add(it.id) }
                            onSaveStrokes(
                                localStrokes.filter { it.id !in localDeletedIds },
                                localDeletedIds.toList()
                            )
                        }
                    ) {
                        Icon(Icons.Default.LayersClear, contentDescription = "Clear All")
                    }
                } else {
                    IconButton(onClick = { showNavMarkupControls = true }) {
                        Icon(Icons.Default.Create, contentDescription = "Show pen controls")
                    }
                }
            }
        }
        onDispose {
            if (hostMarkupToolbarInNavBar) {
                navBarDeco.extendedControls = null
            }
        }
    }

    // Controls Layout
    Column(modifier = modifier.fillMaxSize()) {
        // Toolbar card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
            ),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Navigation
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { if (classicPage > pages.first()) classicPage-- },
                        enabled = classicPage > pages.first()
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Prev page")
                    }
                    Text(
                        "Page $classicPage of ${pages.last()}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                    IconButton(
                        onClick = { if (classicPage < pages.last()) classicPage++ },
                        enabled = classicPage < pages.last()
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Next page")
                    }
                }
                
                if (showMarkupToolbar) {
                    Spacer(Modifier.width(8.dp))
                    VerticalDivider(Modifier.height(32.dp))
                    Spacer(Modifier.width(8.dp))

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = { toolState.selectedTool = DrawingTool.PEN },
                            colors = IconButtonDefaults.iconButtonColors(
                                containerColor = if (activeTool == DrawingTool.PEN) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
                            )
                        ) {
                            Icon(Icons.Default.Create, contentDescription = "Pen Tool")
                        }
                        IconButton(
                            onClick = { toolState.selectedTool = DrawingTool.HIGHLIGHTER },
                            colors = IconButtonDefaults.iconButtonColors(
                                containerColor = if (activeTool == DrawingTool.HIGHLIGHTER) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
                            )
                        ) {
                            Icon(Icons.Default.BorderColor, contentDescription = "Highlighter Tool")
                        }
                        IconButton(
                            onClick = { toolState.selectedTool = DrawingTool.ERASER },
                            colors = IconButtonDefaults.iconButtonColors(
                                containerColor = if (activeTool == DrawingTool.ERASER) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
                            )
                        ) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = "Eraser Tool")
                        }
                    }

                    Spacer(Modifier.width(4.dp))

                    if (activeTool == DrawingTool.PEN || activeTool == DrawingTool.HIGHLIGHTER) {
                        val colors = listOf(Color.Red, Color.Blue, Color.Green, Color.Black, Color(0xFFE5A823))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            colors.forEach { color ->
                                Box(
                                    modifier = Modifier
                                        .size(24.dp)
                                        .background(
                                            if (isDarkTheme && color == Color.Black) Color.White else color,
                                            shape = CircleShape
                                        )
                                        .border(
                                            width = if (activeColor == color) 2.dp else 1.dp,
                                            color = if (activeColor == color) MaterialTheme.colorScheme.primary else Color.Gray.copy(alpha = 0.5f),
                                            shape = CircleShape
                                        )
                                        .clickable { toolState.activeColor = color }
                                )
                            }
                        }
                    }

                    Spacer(Modifier.weight(1f))

                    FilterChip(
                        selected = activeTool != DrawingTool.PAN_ZOOM,
                        onClick = {
                            toolState.selectedTool = if (activeTool == DrawingTool.PAN_ZOOM) DrawingTool.PEN else DrawingTool.PAN_ZOOM
                        },
                        label = { Text("Draw") },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Edit,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    )

                    FilterChip(
                        selected = allowFingerDrawing,
                        onClick = { toolState.allowFingerDrawing = !toolState.allowFingerDrawing },
                        label = { Text("Finger Draw") },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Gesture,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    )

                    VerticalDivider(Modifier.height(32.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        IconButton(
                            onClick = {
                                val mine = localStrokes.lastOrNull { it.id !in localDeletedIds }
                                if (mine != null) {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    localDeletedIds.add(mine.id)
                                    onSaveStrokes(
                                        localStrokes.filter { it.id !in localDeletedIds },
                                        localDeletedIds.toList()
                                    )
                                }
                            },
                            enabled = localStrokes.any { it.id !in localDeletedIds }
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo")
                        }

                        IconButton(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                val pageStrokes = localStrokes.filter { it.docType == docType.name && it.page == classicPage }
                                pageStrokes.forEach { localDeletedIds.add(it.id) }
                                onSaveStrokes(
                                    localStrokes.filter { it.id !in localDeletedIds },
                                    localDeletedIds.toList()
                                )
                            }
                        ) {
                            Icon(Icons.Default.LayersClear, contentDescription = "Clear All")
                        }
                    }
                } else {
                    Spacer(Modifier.weight(1f))
                }
            }
        }

        // Table Container (Scrollable)
        val verticalScrollState = rememberScrollState()
        val horizontalScrollState = rememberScrollState()

        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.8f))
        ) {
            val density = androidx.compose.ui.platform.LocalDensity.current
            val viewportWidthPx = remember(maxWidth, density) {
                with(density) { maxWidth.roundToPx() }
            }
            val zoomScrollScope = rememberCoroutineScope()
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(verticalScrollState, enabled = !isViewLocked)
                    .horizontalScroll(horizontalScrollState, enabled = !isViewLocked)
            ) {
                var scale by remember(classicPage) { mutableFloatStateOf(0.85f) }
                var tableSize by remember { mutableStateOf(IntSize.Zero) }
                var hasAutoFitScale by remember(classicPage) { mutableStateOf(false) }

                LaunchedEffect(viewportWidthPx, tableSize, classicPage) {
                    if (!hasAutoFitScale && tableSize.width > 0 && viewportWidthPx > 0) {
                        scale = calculateClassicViewFitWidthScale(
                            viewportWidthPx = viewportWidthPx,
                            contentWidthPx = tableSize.width
                        )
                        hasAutoFitScale = true
                    }
                }

                Box(
                    modifier = Modifier
                        .wrapContentSize()
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                do {
                                    val event = awaitPointerEvent()
                                    if (event.changes.size >= 2) {
                                        val oldScale = scale
                                        val zoomChange = event.calculateZoom()
                                        val newScale = (oldScale * zoomChange).coerceIn(0.7f, 2.5f)
                                        val centroid = event.calculateCentroid(useCurrent = true)
                                        val (scrollDeltaX, scrollDeltaY) = computeClassicZoomScrollDelta(
                                            oldScale = oldScale,
                                            newScale = newScale,
                                            centroidX = centroid.x,
                                            centroidY = centroid.y
                                        )
                                        scale = newScale
                                        hasAutoFitScale = true
                                        zoomScrollScope.launch {
                                            horizontalScrollState.scroll(MutatePriority.PreventUserInput) {
                                                scrollBy(scrollDeltaX)
                                            }
                                        }
                                        zoomScrollScope.launch {
                                            verticalScrollState.scroll(MutatePriority.PreventUserInput) {
                                                scrollBy(scrollDeltaY)
                                            }
                                        }
                                        event.changes.forEach { it.consume() }
                                    }
                                } while (event.changes.any { it.pressed })
                            }
                        }
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            transformOrigin = TransformOrigin(0f, 0f)
                        )
                        .layout { measurable, constraints ->
                            val placeable = measurable.measure(constraints)
                            layout((placeable.width * scale).toInt(), (placeable.height * scale).toInt()) {
                                placeable.placeRelative(0, 0)
                            }
                        }
                        .padding(bottom = classicCutListBottomScrollPadding())
                ) {
                    // Main Table Columns Layout
                    Column(
                        modifier = Modifier
                            .wrapContentSize()
                            .onSizeChanged { tableSize = it }
                            .background(MaterialTheme.colorScheme.surface)
                    ) {
                        // Headers Row
                        TableHeaderRow(isDarkTheme)

                        var globalRowIndex = 0
                        // Page rows grouped by material
                        groupedPageRows.forEach { (material, materialRows) ->
                            // Sticky-style section header row
                            TableSectionHeaderRow(material)

                            materialRows.forEach { row ->
                                val progress = rowProgressMap[docType.name to row.rowId] ?: HardwoodRowProgress()
                                val qty = row.qty.coerceAtLeast(0)
                                val done = progress.doneCount.coerceIn(0, qty)
                                val skipped = progress.skipped

                                // Status wash styling per design guidelines
                                val statusColors = KKCThemeColors.statusColors
                                val widthColor = widthColorBands[normalizeWidthForGrouping(row.width)] ?: Color.Transparent
                                val (washColor, borderWashColor) = when {
                                    skipped -> statusColors.skipBg.copy(alpha = 0.08f) to statusColors.skipBorder
                                    qty > 0 && done == qty -> statusColors.completeBg.copy(alpha = 0.08f) to statusColors.completeBorder
                                    done > 0 -> statusColors.inProgress.copy(alpha = 0.08f) to statusColors.inProgress
                                    else -> {
                                        val isEven = globalRowIndex % 2 == 0
                                        val alpha = if (isEven) 0.04f else 0.12f
                                        if (widthColor != Color.Transparent) {
                                            widthColor.copy(alpha = alpha) to Color.Transparent
                                        } else {
                                            if (isEven) Color.Transparent to Color.Transparent
                                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f) to Color.Transparent
                                        }
                                    }
                                }

                                TableRow(
                                    row = row,
                                    done = done,
                                    qty = qty,
                                    skipped = skipped,
                                    washColor = washColor,
                                    borderWashColor = borderWashColor,
                                    widthColor = widthColor,
                                    tallyActionsEnabled = tallyActionsEnabled,
                                    onIncrement = { onIncrementProgress(row.rowId, done, qty) },
                                    onDecrement = { onDecrementProgress(row.rowId, done, qty) },
                                    onToggleSkip = { onToggleSkip(row.rowId, skipped) },
                                    onCompleteAll = { onCompleteProgress(row.rowId, qty) },
                                    onZeroOut = { onZeroProgress(row.rowId, qty) },
                                    longPressEnabled = classicRowLongPressEnabled(allowFingerDrawing),
                                    onLongPress = { onRowLongPress(row) },
                                    tallyTargetKeyPrefix = "${docType.name}-${classicPage}-${row.rowId}",
                                    onTallyTargetChanged = { key, target ->
                                        if (target == null) {
                                            tallyHitTargets.remove(key)
                                        } else {
                                            tallyHitTargets[key] = target
                                        }
                                    }
                                )
                                globalRowIndex++
                            }
                        }
                    }

                    // Drawing overlay Canvas matching table size
                    if (tableSize != IntSize.Zero) {
                        StylusDrawingCanvas(
                            modifier = Modifier.size(
                                width = with(density) { tableSize.width.toDp() },
                                height = with(density) { tableSize.height.toDp() }
                            ),
                            canvasSize = tableSize,
                            docType = docType.name,
                            page = classicPage,
                            activeStrokes = localStrokes.toList(),
                            deletedStrokeIds = localDeletedIds.toList(),
                            activeTool = activeTool,
                            activeColor = activeColor,
                            activeThickness = activeThickness,
                            allowFingerDrawing = allowFingerDrawing,
                            tallyHitTargets = tallyHitTargets.values.toList(),
                            onStylusButtonEraserChanged = { isActive ->
                                toolState.isStylusButtonEraserActive = isActive
                            },
                            onStrokeAdded = { stroke ->
                                localStrokes.add(stroke)
                                onSaveStrokes(
                                    localStrokes.filter { it.id !in localDeletedIds },
                                    localDeletedIds.toList()
                                )
                            },
                            onStrokeErased = { strokeId ->
                                localDeletedIds.add(strokeId)
                                onSaveStrokes(
                                    localStrokes.filter { it.id !in localDeletedIds },
                                    localDeletedIds.toList()
                                )
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TableHeaderRow(isDarkTheme: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .drawBehind {
                drawLine(
                    color = Color.Gray.copy(alpha = 0.5f),
                    start = Offset(0f, size.height),
                    end = Offset(size.width, size.height),
                    strokeWidth = 2f
                )
            },
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Offset for the width color band
        Spacer(Modifier.width(4.dp))
        val style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold)
        Text("Qty", Modifier.width(60.dp), textAlign = TextAlign.Center, style = style)
        VerticalBorder()
        Text("Width x Length", Modifier.width(150.dp), textAlign = TextAlign.Center, style = style)
        VerticalBorder()
        Text("Description", Modifier.width(220.dp), textAlign = TextAlign.Center, style = style)
        VerticalBorder()
        Text("Cabinet(s)", Modifier.width(180.dp), textAlign = TextAlign.Center, style = style)
        VerticalBorder()
        Text("Tally / Done Progress", Modifier.width(220.dp), textAlign = TextAlign.Center, style = style)
        VerticalBorder()
        Text("Notes / Markings", Modifier.width(262.dp), textAlign = TextAlign.Center, style = style)
    }
}

@Composable
private fun TableSectionHeaderRow(material: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(MaterialTheme.colorScheme.primaryContainer)
            .drawBehind {
                // Top border line
                drawLine(
                    color = Color.Gray.copy(alpha = 0.5f),
                    start = Offset(0f, 0f),
                    end = Offset(size.width, 0f),
                    strokeWidth = 2.5f
                )
                // Bottom border line
                drawLine(
                    color = Color.Gray.copy(alpha = 0.5f),
                    start = Offset(0f, size.height),
                    end = Offset(size.width, size.height),
                    strokeWidth = 2.5f
                )
            },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            material.uppercase(),
            style = MaterialTheme.typography.titleLarge.copy(
                letterSpacing = 1.sp
            ),
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.padding(horizontal = 24.dp)
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TableRow(
    row: HardwoodCutlistRow,
    done: Int,
    qty: Int,
    skipped: Boolean,
    washColor: Color,
    borderWashColor: Color,
    widthColor: Color,
    tallyActionsEnabled: Boolean,
    onIncrement: () -> Unit,
    onDecrement: () -> Unit,
    onCompleteAll: () -> Unit,
    onZeroOut: () -> Unit,
    onToggleSkip: () -> Unit,
    longPressEnabled: Boolean,
    onLongPress: () -> Unit,
    tallyTargetKeyPrefix: String,
    onTallyTargetChanged: (String, TallyHitTarget?) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val statusColors = KKCThemeColors.statusColors
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp) // Touch target safety minimum is 48dp, 56dp is extra comfortable
            .combinedClickable(
                onClick = {},
                onLongClick = {
                    if (!longPressEnabled) return@combinedClickable
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongPress()
                }
            )
            .background(washColor)
            .drawBehind {
                // Bottom row gridline
                drawLine(
                    color = Color.Gray.copy(alpha = 0.2f),
                    start = Offset(0f, size.height),
                    end = Offset(size.width, size.height),
                    strokeWidth = 1f
                )
                // Guidelines 3dp status border wash
                if (borderWashColor != Color.Transparent) {
                    drawLine(
                        color = borderWashColor,
                        start = Offset(0f, 0f),
                        end = Offset(0f, size.height),
                        strokeWidth = 6f
                    )
                }
            },
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Width Color Stripe
        Box(
            modifier = Modifier
                .width(4.dp)
                .fillMaxHeight()
                .background(widthColor)
        )
        // Qty Column
        Text(
            "$qty",
            modifier = Modifier.width(60.dp),
            textAlign = TextAlign.Center,
            style = DimensionTextStyle,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold
        )
        VerticalBorder()

        // Size Column
        Text(
            cutlistDimensionDisplay(row),
            modifier = Modifier.width(150.dp),
            textAlign = TextAlign.Center,
            style = DimensionTextStyle,
            fontSize = 15.sp
        )
        VerticalBorder()

        // Description Column
        Text(
            row.description,
            modifier = Modifier
                .width(220.dp)
                .padding(horizontal = 8.dp),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium
        )
        VerticalBorder()

        // Cabinets Column
        Text(
            row.rawCabinetText.ifBlank { "—" },
            modifier = Modifier
                .width(180.dp)
                .padding(horizontal = 8.dp),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium
        )
        VerticalBorder()

        // Tally / Done Column
        Row(
            modifier = Modifier
                .width(220.dp)
                .fillMaxHeight()
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            val actionsEnabled = tallyActionsEnabled
            val decrementEnabled = actionsEnabled && !skipped && done > 0
            val incrementEnabled = actionsEnabled && !skipped && done < qty
            val skipEnabled = actionsEnabled

            Box(
                modifier = Modifier
                    .size(36.dp)
                    .trackTallyTarget(
                        key = "$tallyTargetKeyPrefix-decrement",
                        enabled = decrementEnabled,
                        onTap = onDecrement,
                        onTargetChanged = onTallyTargetChanged
                    )
                    .combinedClickable(
                        enabled = decrementEnabled,
                        onClick = onDecrement,
                        onLongClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onZeroOut()
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.RemoveCircleOutline,
                    contentDescription = "Minus",
                    tint = if (decrementEnabled) statusColors.bad else Color.Gray.copy(alpha = 0.3f)
                )
            }

            // Central progress count
            Text(
                text = if (skipped) "SKIPPED" else "$done / $qty",
                modifier = Modifier
                    .weight(1f)
                    .trackTallyTarget(
                        key = "$tallyTargetKeyPrefix-count",
                        enabled = actionsEnabled,
                        onTap = {
                            if (!skipped && done < qty) {
                                onIncrement()
                            }
                        },
                        onTargetChanged = onTallyTargetChanged
                    )
                    .clickable(enabled = actionsEnabled) {
                        if (!skipped && done < qty) {
                            onIncrement()
                        }
                    },
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = when {
                    skipped -> statusColors.skipBorder
                    done == qty -> statusColors.completeBorder
                    done > 0 -> statusColors.inProgress
                    else -> MaterialTheme.colorScheme.onSurface
                }
            )

            Box(
                modifier = Modifier
                    .size(36.dp)
                    .trackTallyTarget(
                        key = "$tallyTargetKeyPrefix-increment",
                        enabled = incrementEnabled,
                        onTap = onIncrement,
                        onTargetChanged = onTallyTargetChanged
                    )
                    .combinedClickable(
                        enabled = incrementEnabled,
                        onClick = onIncrement,
                        onLongClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onCompleteAll()
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.AddCircleOutline,
                    contentDescription = "Plus",
                    tint = if (incrementEnabled) statusColors.completeBorder else Color.Gray.copy(alpha = 0.3f)
                )
            }
            
            Spacer(Modifier.width(4.dp))

            // Skip chip
            IconButton(
                onClick = {
                    onToggleSkip()
                },
                enabled = skipEnabled,
                modifier = Modifier
                    .size(32.dp)
                    .trackTallyTarget(
                        key = "$tallyTargetKeyPrefix-skip",
                        enabled = skipEnabled,
                        onTap = onToggleSkip,
                        onTargetChanged = onTallyTargetChanged
                    )
            ) {
                Icon(
                    if (skipped) Icons.Default.Restore else Icons.Default.Block,
                    contentDescription = "Toggle Skip",
                    tint = if (skipEnabled) statusColors.skipBorder else Color.Gray.copy(alpha = 0.3f),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        VerticalBorder()
        Box(modifier = Modifier.width(262.dp).fillMaxHeight())
    }
}

private fun Modifier.trackTallyTarget(
    key: String,
    enabled: Boolean,
    onTap: () -> Unit,
    onTargetChanged: (String, TallyHitTarget?) -> Unit
): Modifier = this.then(
    Modifier.onGloballyPositioned { coordinates ->
        onTargetChanged(
            key,
            TallyHitTarget(
                boundsInRoot = coordinates.boundsInRoot(),
                enabled = enabled,
                onTap = onTap
            )
        )
    }
)

@Composable
private fun VerticalBorder() {
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .width(1.dp)
            .background(Color.Gray.copy(alpha = 0.2f))
    )
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun StylusDrawingCanvas(
    canvasSize: IntSize,
    docType: String,
    page: Int,
    activeStrokes: List<HardwoodInkStroke>,
    deletedStrokeIds: List<String>,
    activeTool: DrawingTool,
    activeColor: Color,
    activeThickness: Float,
    allowFingerDrawing: Boolean,
    tallyHitTargets: List<TallyHitTarget>,
    onStylusButtonEraserChanged: (Boolean) -> Unit,
    onStrokeAdded: (HardwoodInkStroke) -> Unit,
    onStrokeErased: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val currentPoints = remember { mutableStateListOf<Float>() }
    val isDrawing = remember { mutableStateOf(false) }
    var canvasBoundsInRoot by remember { mutableStateOf<Rect?>(null) }

    val pageStrokes = remember(activeStrokes, docType, page, deletedStrokeIds) {
        activeStrokes.filter {
            it.docType == docType && it.page == page && it.id !in deletedStrokeIds
        }
    }

    // Capture Stylus (or allowed finger) drawing gestures
    Canvas(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { coordinates ->
                canvasBoundsInRoot = coordinates.boundsInRoot()
            }
            .pointerInteropFilter { motionEvent ->
                val isStylusButtonPressed =
                    (motionEvent.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0 ||
                    (motionEvent.buttonState and MotionEvent.BUTTON_STYLUS_SECONDARY) != 0 ||
                    (motionEvent.buttonState and MotionEvent.BUTTON_SECONDARY) != 0
                val isEraserTool = motionEvent.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER
                val shouldUseTemporaryEraser = when (motionEvent.actionMasked) {
                    MotionEvent.ACTION_UP,
                    MotionEvent.ACTION_CANCEL -> false
                    else -> isStylusButtonPressed || isEraserTool
                }
                onStylusButtonEraserChanged(shouldUseTemporaryEraser)
                false
            }
            .pointerInput(docType, page, activeTool, allowFingerDrawing, tallyHitTargets, canvasBoundsInRoot) {
                if (activeTool == DrawingTool.PAN_ZOOM) return@pointerInput

                awaitEachGesture {
                    val firstDown = awaitFirstDown(requireUnconsumed = false)
                    
                    // Palm Rejection & Input Filtering
                    val isStylus = firstDown.type == PointerType.Stylus
                    val isEraser = firstDown.type == PointerType.Eraser
                    val canDraw = classicPointerCanDraw(
                        pointerType = firstDown.type,
                        allowFingerDrawing = allowFingerDrawing
                    )

                    if (!canDraw && firstDown.type == PointerType.Touch && classicTallyActionsEnabled(activeTool, allowFingerDrawing)) {
                        val overlayBounds = canvasBoundsInRoot
                        if (overlayBounds != null) {
                            val downInRoot = firstDown.position + overlayBounds.topLeft
                            val hitTarget = tallyHitTargets.firstOrNull { it.enabled && it.boundsInRoot.contains(downInRoot) }
                            if (hitTarget != null) {
                                val touchSlop = viewConfiguration.touchSlop
                                var exceededSlop = false

                                do {
                                    val event = awaitPointerEvent()
                                    val tracked = event.changes.firstOrNull { it.id == firstDown.id } ?: break
                                    if (tracked.positionChange().getDistance() > 0f) {
                                        val totalDx = tracked.position.x - firstDown.position.x
                                        val totalDy = tracked.position.y - firstDown.position.y
                                        if ((totalDx * totalDx) + (totalDy * totalDy) > touchSlop * touchSlop) {
                                            exceededSlop = true
                                        }
                                    }

                                    if (!tracked.pressed) {
                                        if (!exceededSlop) {
                                            hitTarget.onTap()
                                        }
                                        break
                                    }
                                } while (true)
                            }
                        }
                    }

                    if (!canDraw) return@awaitEachGesture

                    val motionEvent = try {
                        val field = currentEvent.javaClass.getDeclaredField("motionEvent")
                        field.isAccessible = true
                        field.get(currentEvent) as? MotionEvent
                    } catch (e: Exception) {
                        null
                    }
                    val isMotionEventEraser = motionEvent != null && motionEvent.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER
                    val isSideButtonPressed = (motionEvent != null && (
                        (motionEvent.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0 ||
                        (motionEvent.buttonState and MotionEvent.BUTTON_STYLUS_SECONDARY) != 0 ||
                        (motionEvent.buttonState and MotionEvent.BUTTON_SECONDARY) != 0
                    )) || currentEvent.buttons.isSecondaryPressed || isMotionEventEraser
                    
                    val isEraserMode = activeTool == DrawingTool.ERASER || isEraser || isSideButtonPressed

                    val canvasW = size.width.toFloat().coerceAtLeast(1f)
                    val canvasH = size.height.toFloat().coerceAtLeast(1f)

                    if (isEraserMode) {
                        // Erase Mode
                        val touchX = firstDown.position.x
                        val touchY = firstDown.position.y

                        // Find closest stroke to delete
                        val thresholdPx = 30f // ~24dp
                        val toDelete = pageStrokes
                            .mapNotNull { stroke ->
                                val d = distanceToScaledStroke(touchX, touchY, stroke.points, canvasW, canvasH)
                                if (d < thresholdPx) stroke to d else null
                            }
                            .minByOrNull { it.second }
                            ?.first
                        if (toDelete != null) {
                            onStrokeErased(toDelete.id)
                        }

                        do {
                            val event = awaitPointerEvent()
                            event.changes.forEach { change ->
                                if (change.pressed) {
                                    val samples = change.historical.map { it.position } + change.position
                                    samples.forEach { pos ->
                                        val innerDelete = pageStrokes
                                            .mapNotNull { stroke ->
                                                val d = distanceToScaledStroke(pos.x, pos.y, stroke.points, canvasW, canvasH)
                                                if (d < thresholdPx) stroke to d else null
                                            }
                                            .minByOrNull { it.second }
                                            ?.first
                                        if (innerDelete != null) {
                                            onStrokeErased(innerDelete.id)
                                        }
                                    }
                                    change.consume()
                                }
                            }
                        } while (event.changes.any { it.pressed })

                    } else if (activeTool == DrawingTool.PEN || activeTool == DrawingTool.HIGHLIGHTER) {
                        // Draw Mode
                        isDrawing.value = true
                        currentPoints.clear()
                        currentPoints.add(firstDown.position.x / canvasW)
                        currentPoints.add(firstDown.position.y / canvasH)
                        firstDown.consume()

                        do {
                            val event = awaitPointerEvent()
                            val tracked = event.changes.firstOrNull { it.id == firstDown.id } ?: event.changes.firstOrNull { it.pressed }
                            if (tracked != null && tracked.pressed) {
                                val nx = tracked.position.x / canvasW
                                val ny = tracked.position.y / canvasH

                                // Thin out points while still allowing small dots and periods to register.
                                val lx = currentPoints[currentPoints.size - 2]
                                val ly = currentPoints[currentPoints.size - 1]
                                if (shouldAppendStrokePoint(lx, ly, nx, ny)) {
                                    currentPoints.add(nx)
                                    currentPoints.add(ny)
                                }
                                tracked.consume()
                            }
                        } while (event.changes.any { it.pressed })

                        // Finalize stroke
                        if (currentPoints.size >= 4) {
                            val stroke = HardwoodInkStroke(
                                id = UUID.randomUUID().toString(),
                                docType = docType,
                                page = page,
                                color = activeColor.toArgb(),
                                lineWidth = activeThickness,
                                isHighlighter = activeTool == DrawingTool.HIGHLIGHTER,
                                points = currentPoints.toList()
                            )
                            onStrokeAdded(stroke)
                        }
                        isDrawing.value = false
                        currentPoints.clear()
                    }
                }
            }
    ) {
        val w = size.width
        val h = size.height

        // 1. Draw static strokes using GPU-accelerated drawing
        pageStrokes.forEach { stroke ->
            if (stroke.points.size >= 4) {
                val path = Path()
                path.moveTo(stroke.points[0] * w, stroke.points[1] * h)
                for (i in 2 until stroke.points.size step 2) {
                    path.lineTo(stroke.points[i] * w, stroke.points[i+1] * h)
                }
                drawPath(
                    path = path,
                    color = Color(stroke.color),
                    style = Stroke(
                        width = stroke.lineWidth,
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round
                    ),
                    alpha = if (stroke.isHighlighter) 0.35f else 1.0f
                )
            }
        }

        // 2. Draw active temporary line under pointer
        if (isDrawing.value && currentPoints.size >= 4) {
            val path = Path()
            path.moveTo(currentPoints[0] * w, currentPoints[1] * h)
            for (i in 2 until currentPoints.size step 2) {
                path.lineTo(currentPoints[i] * w, currentPoints[i+1] * h)
            }
            drawPath(
                path = path,
                color = activeColor,
                style = Stroke(
                    width = activeThickness,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                ),
                alpha = if (activeTool == DrawingTool.HIGHLIGHTER) 0.35f else 1.0f
            )
        }
    }
}
