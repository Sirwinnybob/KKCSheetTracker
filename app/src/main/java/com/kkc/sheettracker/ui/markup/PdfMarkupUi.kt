package com.kkc.sheettracker.ui.markup

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.BorderColor
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.data.models.PdfInkStroke
import com.kkc.sheettracker.ui.components.PdfViewportState
import java.util.UUID
import kotlin.math.abs

/** Eraser reach, in overlay view px at an eraserRadiusScale of 1. */
private const val ERASER_HIT_RADIUS_PX = 30f

@Stable
class PdfMarkupToolState {
    var selectedTool by mutableStateOf(DrawingTool.PEN)
    var activeColor by mutableStateOf(Color.Red)
    var allowFingerDrawing by mutableStateOf(false)
    var isStylusButtonEraserActive by mutableStateOf(false)

    val activeTool: DrawingTool
        get() = resolveEffectiveDrawingTool(selectedTool, isStylusButtonEraserActive)

    val activeThickness: Float
        get() = if (activeTool == DrawingTool.HIGHLIGHTER) 24f else 4f
}

@Composable
fun rememberPdfMarkupToolState(): PdfMarkupToolState = remember { PdfMarkupToolState() }

@Composable
fun RowScope.PdfMarkupToolbar(
    state: PdfMarkupToolState,
    hasUndo: Boolean,
    onUndo: () -> Unit,
    strokesVisible: Boolean,
    onToggleVisibility: () -> Unit,
    onHide: (() -> Unit)? = null
) {
    if (onHide != null) {
        IconButton(onClick = onHide) {
            Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Hide pen controls")
        }
    }

    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = { state.selectedTool = DrawingTool.PEN },
            colors = IconButtonDefaults.iconButtonColors(
                containerColor = if (state.activeTool == DrawingTool.PEN) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
            )
        ) {
            Icon(Icons.Default.Create, contentDescription = "Pen Tool")
        }
        IconButton(
            onClick = { state.selectedTool = DrawingTool.HIGHLIGHTER },
            colors = IconButtonDefaults.iconButtonColors(
                containerColor = if (state.activeTool == DrawingTool.HIGHLIGHTER) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
            )
        ) {
            Icon(Icons.Default.BorderColor, contentDescription = "Highlighter Tool")
        }
        IconButton(
            onClick = { state.selectedTool = DrawingTool.ERASER },
            colors = IconButtonDefaults.iconButtonColors(
                containerColor = if (state.activeTool == DrawingTool.ERASER) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
            )
        ) {
            Icon(Icons.Default.DeleteOutline, contentDescription = "Eraser Tool")
        }
    }

    if (state.activeTool == DrawingTool.PEN || state.activeTool == DrawingTool.HIGHLIGHTER) {
        val colors = listOf(Color.Red, Color.Blue, Color.Green, Color.Black, Color(0xFFE5A823))
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            colors.forEach { color ->
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .background(color = color, shape = CircleShape)
                        .border(
                            width = if (state.activeColor == color) 2.dp else 1.dp,
                            color = if (state.activeColor == color) MaterialTheme.colorScheme.primary else Color.Gray.copy(alpha = 0.5f),
                            shape = CircleShape
                        )
                        .clickable { state.activeColor = color }
                )
            }
        }
    }

    FilterChip(
        selected = state.allowFingerDrawing,
        onClick = { state.allowFingerDrawing = !state.allowFingerDrawing },
        label = { androidx.compose.material3.Text("Finger Draw") },
        leadingIcon = {
            Icon(
                Icons.Default.Gesture,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
        }
    )

    IconButton(onClick = onUndo, enabled = hasUndo) {
        Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo")
    }
    IconButton(onClick = onToggleVisibility, enabled = hasUndo || !strokesVisible) {
        Icon(
            if (strokesVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
            contentDescription = if (strokesVisible) "Hide markup" else "Show markup"
        )
    }
}

@Composable
fun PdfMarkupOverlay(
    viewportState: PdfViewportState,
    pageAspectRatio: Float?,
    activeStrokes: List<PdfInkStroke>,
    inputEnabled: Boolean,
    activeTool: DrawingTool,
    activeColor: Color,
    activeThickness: Float,
    allowFingerDrawing: Boolean,
    onStylusButtonEraserChanged: (Boolean) -> Unit,
    onStrokeAdded: (PdfInkStroke) -> Unit,
    onStrokeErased: (String) -> Unit,
    modifier: Modifier = Modifier,
    // Multiplier for drawn stroke widths only. Defaults to the viewport zoom (paged viewers draw
    // through viewportState.zoom). The continuous pane draws this overlay OUTSIDE its whole-stack
    // zoom layer, so it passes its shared zoom here to keep ink the same visual weight it had
    // when it was scaled along with the page.
    strokeWidthScale: Float = viewportState.zoom.coerceAtLeast(1f),
    // Multiplier for the eraser hit radius (ERASER_HIT_RADIUS_PX view px). Paged callers keep the
    // fixed 30 px reach; only the continuous pane scales it with its zoom.
    eraserRadiusScale: Float = 1f,
    // When non-null, the page's rect in THIS overlay's own px, read at draw and input time. It
    // replaces the viewportState fit/zoom/pan math: draw, input and eraser all map through it.
    // A provider rather than a value because the continuous pane only knows the rect in its
    // layout pass (every pan/scroll frame); a value would arrive a frame late via recomposition.
    // The caller must invalidate this overlay's draw when the rect it returns changes.
    pageRectInView: (() -> Rect?)? = null
) {
    val currentPoints = remember { mutableStateListOf<Float>() }
    var isDrawing by remember { mutableStateOf(false) }
    var gestureTool by remember { mutableStateOf(DrawingTool.PEN) }
    val committedPathCache = remember { NormalizedStrokePathCache() }
    // Last valid transform of the gesture in progress; plain holder, only read by the handler.
    val gestureTransformMemory = remember { MarkupGestureTransformMemory() }
    // Which pointer is marking the page; plain holder, only read by the handler.
    val pointerTracker = remember { MarkupPointerTracker() }

    fun currentTransform(): PdfPageTransform? {
        val aspect = pageAspectRatio ?: return null
        if (pageRectInView != null) {
            val rect = pageRectInView() ?: return null
            return pdfPageTransformForRect(viewportState.viewSize, rect)
        }
        if (viewportState.viewSize == IntSize.Zero) return null
        return computePdfPageTransform(
            viewSize = viewportState.viewSize,
            pageAspectRatio = aspect,
            zoom = viewportState.zoom,
            panX = viewportState.panX,
            panY = viewportState.panY
        )
    }

    fun endGesture() {
        pointerTracker.reset()
        isDrawing = false
        currentPoints.clear()
        gestureTransformMemory.transform = null
    }

    // Compose pointer input rather than pointerInteropFilter: the interop filter gates the whole
    // MotionEvent stream on its first ACTION_DOWN, so refusing a resting palm's down made it drop
    // the pen's ACTION_POINTER_DOWN too, and the first pen stroke after a palm never inked. Here
    // each event is judged per pointer: a finger with finger drawing off is left alone (unconsumed)
    // for the viewer to scroll and zoom with, and a pen starts a stroke whenever it lands.
    fun handlePointerEvent(event: PointerEvent) {
        if (!inputEnabled) {
            onStylusButtonEraserChanged(false)
            if (pointerTracker.isTracking) endGesture()
            return
        }
        val gestureInProgress = pointerTracker.isTracking
        val action = pointerTracker.next(
            pointers = event.changes.map { change ->
                MarkupPointer(
                    id = change.id.value,
                    isStylus = change.type == PointerType.Stylus || change.type == PointerType.Eraser,
                    pressed = change.pressed,
                    previousPressed = change.previousPressed
                )
            },
            allowFingerDrawing = allowFingerDrawing
        )
        val actionId = when (action) {
            is MarkupPointerAction.Start -> action.id
            is MarkupPointerAction.Continue -> action.id
            is MarkupPointerAction.Finish -> action.id
            MarkupPointerAction.None, MarkupPointerAction.Abandon -> null
        }
        val change = actionId?.let { id -> event.changes.firstOrNull { it.id.value == id } }

        // Side button or pen-eraser end: the pen erases while it's held (also while hovering, so
        // the toolbar shows it). Judged on the marking pointer, else any pen in the event.
        val buttonPointer = change
            ?: event.changes.firstOrNull { it.type == PointerType.Stylus || it.type == PointerType.Eraser }
            ?: event.changes.firstOrNull()
        val allLifted = event.type == PointerEventType.Release && event.changes.none { it.pressed }
        val shouldUseTemporaryEraser = buttonPointer != null && !allLifted && (
            isStylusEraserButtonState(event.motionEvent?.buttonState ?: 0) ||
                buttonPointer.type == PointerType.Eraser
            )
        onStylusButtonEraserChanged(shouldUseTemporaryEraser)

        if (action == MarkupPointerAction.Abandon) {
            endGesture()
            return
        }
        if (change == null) return
        // A continuous-mode page can leave view mid-stroke (e.g. a programmatic scroll),
        // making its rect null: finish the gesture on the transform it last had.
        val transform = resolveMarkupGestureTransform(
            current = currentTransform(),
            gestureTransform = gestureTransformMemory.transform,
            gestureInProgress = gestureInProgress && action !is MarkupPointerAction.Start
        )
        if (transform == null) {
            // No transform at all: close out any gesture so state never goes stale.
            endGesture()
            return
        }
        change.consume()

        fun eraseAt(position: Offset) {
            val toDelete = activeStrokes
                .mapNotNull { stroke ->
                    val d = distanceToViewStroke(
                        px = position.x,
                        py = position.y,
                        points = stroke.points,
                        transform = transform
                    )
                    if (d < ERASER_HIT_RADIUS_PX * eraserRadiusScale) stroke to d else null
                }
                .minByOrNull { it.second }
                ?.first
            if (toDelete != null) onStrokeErased(toDelete.id)
        }

        fun appendPoint(position: Offset) {
            val next = transform.viewToNormalizedPage(position.x, position.y)
            if (currentPoints.size < 2) {
                currentPoints.add(next.first)
                currentPoints.add(next.second)
                return
            }
            val lx = currentPoints[currentPoints.size - 2]
            val ly = currentPoints[currentPoints.size - 1]
            if (shouldAppendStrokePoint(lx, ly, next.first, next.second)) {
                currentPoints.add(next.first)
                currentPoints.add(next.second)
            }
        }

        when (action) {
            is MarkupPointerAction.Start -> {
                gestureTransformMemory.transform = transform
                val effectiveTool = if (activeTool == DrawingTool.ERASER || shouldUseTemporaryEraser) {
                    DrawingTool.ERASER
                } else {
                    activeTool
                }
                gestureTool = effectiveTool
                currentPoints.clear()
                if (effectiveTool == DrawingTool.ERASER) {
                    isDrawing = false
                    eraseAt(change.position)
                } else {
                    isDrawing = true
                    appendPoint(change.position)
                }
            }
            is MarkupPointerAction.Continue -> {
                gestureTransformMemory.transform = transform
                if (change.historical.isEmpty() && change.previousPosition == change.position) return
                if (gestureTool == DrawingTool.ERASER) {
                    change.historical.forEach { eraseAt(it.position) }
                    eraseAt(change.position)
                } else if (isDrawing) {
                    change.historical.forEach { appendPoint(it.position) }
                    appendPoint(change.position)
                }
            }
            is MarkupPointerAction.Finish -> {
                if (gestureTool != DrawingTool.ERASER && isDrawing) {
                    appendPoint(change.position)
                    val finalizedPoints = finalizeStrokePoints(
                        points = currentPoints,
                        activeThickness = activeThickness,
                        canvasWidth = transform.pageWidth,
                        canvasHeight = transform.pageHeight
                    )
                    if (finalizedPoints.size >= 4) {
                        onStrokeAdded(
                            PdfInkStroke(
                                id = UUID.randomUUID().toString(),
                                color = activeColor.toArgb(),
                                lineWidth = activeThickness,
                                isHighlighter = gestureTool == DrawingTool.HIGHLIGHTER,
                                points = finalizedPoints
                            )
                        )
                    }
                }
                endGesture()
            }
            MarkupPointerAction.None, MarkupPointerAction.Abandon -> Unit
        }
    }

    Canvas(
        modifier = modifier
            .then(
                MarkupPointerInputElement(
                    onEvent = { handlePointerEvent(it) },
                    // Compose-level cancel (the old ACTION_CANCEL): drop the stroke, never commit.
                    onCancel = {
                        onStylusButtonEraserChanged(false)
                        endGesture()
                    }
                )
            )
    ) {
        val transform = currentTransform() ?: return@Canvas

        if (pageRectInView != null) {
            // The rect moves on every pan/scroll frame while its size only changes with zoom, so
            // keep page-local paths and just translate them — no per-frame Path rebuild.
            val paths = committedPathCache.pathsFor(activeStrokes, transform.pageWidth, transform.pageHeight)
            translate(left = transform.pageLeft, top = transform.pageTop) {
                for (index in activeStrokes.indices) {
                    val stroke = activeStrokes[index]
                    val path = paths[index] ?: continue
                    drawPath(
                        path = path,
                        color = Color(stroke.color),
                        style = Stroke(
                            width = stroke.lineWidth * strokeWidthScale,
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round
                        ),
                        alpha = if (stroke.isHighlighter) 0.35f else 1.0f
                    )
                }
            }
        } else activeStrokes.forEach { stroke ->
            if (stroke.points.size >= 4) {
                val path = Path()
                val start = transform.normalizedPageToView(stroke.points[0], stroke.points[1])
                path.moveTo(start.first, start.second)
                for (i in 2 until stroke.points.size step 2) {
                    val point = transform.normalizedPageToView(stroke.points[i], stroke.points[i + 1])
                    path.lineTo(point.first, point.second)
                }
                drawPath(
                    path = path,
                    color = Color(stroke.color),
                    style = Stroke(
                        width = stroke.lineWidth * strokeWidthScale,
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round
                    ),
                    alpha = if (stroke.isHighlighter) 0.35f else 1.0f
                )
            }
        }

        if (isDrawing && currentPoints.size >= 4) {
            val path = Path()
            val start = transform.normalizedPageToView(currentPoints[0], currentPoints[1])
            path.moveTo(start.first, start.second)
            for (i in 2 until currentPoints.size step 2) {
                val point = transform.normalizedPageToView(currentPoints[i], currentPoints[i + 1])
                path.lineTo(point.first, point.second)
            }
            drawPath(
                path = path,
                color = activeColor,
                style = Stroke(
                    width = activeThickness * strokeWidthScale,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                ),
                alpha = if (activeTool == DrawingTool.HIGHLIGHTER) 0.35f else 1.0f
            )
        }
    }
}

/**
 * Raw pointer input for [PdfMarkupOverlay], handled in the Main pass. Shares input with siblings,
 * like the pointerInteropFilter it replaced: in paged viewers the overlay is a sibling drawn above
 * the zoomable page, which must keep receiving fingers to scroll and zoom.
 */
private class MarkupPointerInputElement(
    val onEvent: (PointerEvent) -> Unit,
    val onCancel: () -> Unit
) : ModifierNodeElement<MarkupPointerInputNode>() {
    override fun create() = MarkupPointerInputNode(onEvent, onCancel)

    override fun update(node: MarkupPointerInputNode) {
        node.onEvent = onEvent
        node.onCancel = onCancel
    }

    override fun equals(other: Any?): Boolean =
        other is MarkupPointerInputElement && other.onEvent === onEvent && other.onCancel === onCancel

    override fun hashCode(): Int = 31 * System.identityHashCode(onEvent) + System.identityHashCode(onCancel)
}

private class MarkupPointerInputNode(
    var onEvent: (PointerEvent) -> Unit,
    var onCancel: () -> Unit
) : Modifier.Node(), PointerInputModifierNode {
    override fun onPointerEvent(pointerEvent: PointerEvent, pass: PointerEventPass, bounds: IntSize) {
        if (pass == PointerEventPass.Main) onEvent(pointerEvent)
    }

    override fun onCancelPointerInput() = onCancel()

    override fun sharePointerInputWithSiblings(): Boolean = true
}

/**
 * Page transform for an overlay that is told exactly where the page sits in its own px
 * ([pageRect]), with no further zoom or pan. [viewSize] only feeds the (unused at zoom 1, pan 0)
 * view center.
 */
internal fun pdfPageTransformForRect(viewSize: IntSize, pageRect: Rect): PdfPageTransform? {
    if (!(pageRect.width > 0f) || !(pageRect.height > 0f)) return null
    return PdfPageTransform(
        viewWidth = viewSize.width.toFloat(),
        viewHeight = viewSize.height.toFloat(),
        zoom = 1f,
        panX = 0f,
        panY = 0f,
        pageLeft = pageRect.left,
        pageTop = pageRect.top,
        pageWidth = pageRect.width,
        pageHeight = pageRect.height
    )
}

/**
 * Committed-stroke paths in page-local px (origin at the page's top-left), rebuilt only when the
 * stroke list or the page's on-screen size changes. Entries are null for strokes too short to draw.
 */
internal class NormalizedStrokePathCache {
    private var strokes: List<PdfInkStroke>? = null
    private var pageWidth = Float.NaN
    private var pageHeight = Float.NaN
    private var paths: List<Path?> = emptyList()

    fun pathsFor(activeStrokes: List<PdfInkStroke>, width: Float, height: Float): List<Path?> {
        if (isStrokePathCacheHit(strokes, pageWidth, pageHeight, activeStrokes, width, height)) return paths
        paths = activeStrokes.map { stroke ->
            val points = stroke.points
            if (points.size < 4) {
                null
            } else {
                Path().apply {
                    moveTo(points[0].coerceIn(0f, 1f) * width, points[1].coerceIn(0f, 1f) * height)
                    for (i in 2 until points.size - 1 step 2) {
                        lineTo(points[i].coerceIn(0f, 1f) * width, points[i + 1].coerceIn(0f, 1f) * height)
                    }
                }
            }
        }
        strokes = activeStrokes
        pageWidth = width
        pageHeight = height
        return paths
    }
}

/**
 * Page sizes that differ by less than this are the same page size. The continuous pane derives
 * the size from edge subtraction of a moving rect, which wobbles in float32 on every pan frame.
 */
internal const val STROKE_PATH_CACHE_SIZE_TOLERANCE_PX = 0.01f

/** Whether cached page-local paths built for ([cachedStrokes], [cachedWidth] x [cachedHeight]) can be reused. */
internal fun isStrokePathCacheHit(
    cachedStrokes: List<PdfInkStroke>?,
    cachedWidth: Float,
    cachedHeight: Float,
    strokes: List<PdfInkStroke>,
    width: Float,
    height: Float
): Boolean =
    cachedStrokes === strokes &&
        abs(width - cachedWidth) < STROKE_PATH_CACHE_SIZE_TOLERANCE_PX &&
        abs(height - cachedHeight) < STROKE_PATH_CACHE_SIZE_TOLERANCE_PX

/**
 * The transform a markup event maps through: the live one when available, otherwise — only while
 * a gesture is in progress — the last one that gesture had, so a stroke whose page leaves view
 * mid-stroke still commits on ACTION_UP.
 */
internal fun <T : Any> resolveMarkupGestureTransform(current: T?, gestureTransform: T?, gestureInProgress: Boolean): T? =
    current ?: if (gestureInProgress) gestureTransform else null

internal class MarkupGestureTransformMemory {
    var transform: PdfPageTransform? = null
}
