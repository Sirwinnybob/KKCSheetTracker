package com.kkc.sheettracker.ui.markup

import android.view.MotionEvent
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
import androidx.compose.ui.ExperimentalComposeUiApi
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
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.data.models.PdfInkStroke
import com.kkc.sheettracker.ui.components.PdfViewportState
import java.util.UUID

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

@OptIn(ExperimentalComposeUiApi::class)
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
    var isHandlingGesture by remember { mutableStateOf(false) }
    var gestureTool by remember { mutableStateOf(DrawingTool.PEN) }
    val committedPathCache = remember { NormalizedStrokePathCache() }

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

    Canvas(
        modifier = modifier
            .pointerInteropFilter { motionEvent ->
                if (!inputEnabled) {
                    onStylusButtonEraserChanged(false)
                    return@pointerInteropFilter false
                }
                val pointerIndex = findRelevantMotionEventPointerIndex(motionEvent)
                val isStylusButtonPressed =
                    (motionEvent.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0 ||
                    (motionEvent.buttonState and MotionEvent.BUTTON_STYLUS_SECONDARY) != 0 ||
                    (motionEvent.buttonState and MotionEvent.BUTTON_SECONDARY) != 0
                val toolType = motionEvent.getToolType(pointerIndex)
                val isEraserTool = toolType == MotionEvent.TOOL_TYPE_ERASER
                val shouldUseTemporaryEraser = when (motionEvent.actionMasked) {
                    MotionEvent.ACTION_UP,
                    MotionEvent.ACTION_CANCEL -> false
                    else -> isStylusButtonPressed || isEraserTool
                }
                onStylusButtonEraserChanged(shouldUseTemporaryEraser)
                val isStylusTool =
                    toolType == MotionEvent.TOOL_TYPE_STYLUS || toolType == MotionEvent.TOOL_TYPE_ERASER
                val transform = currentTransform() ?: return@pointerInteropFilter false
                val effectiveTool = if (activeTool == DrawingTool.ERASER || shouldUseTemporaryEraser) {
                    DrawingTool.ERASER
                } else {
                    activeTool
                }
                // A finger marks the page (draw or erase) only with finger drawing on; otherwise
                // it's refused here so the parent viewer can scroll and zoom with it.
                val canHandleInput = isStylusTool || allowFingerDrawing

                fun eraseAt(viewX: Float, viewY: Float) {
                    val toDelete = activeStrokes
                        .mapNotNull { stroke ->
                            val d = distanceToViewStroke(
                                px = viewX,
                                py = viewY,
                                points = stroke.points,
                                transform = transform
                            )
                            if (d < ERASER_HIT_RADIUS_PX * eraserRadiusScale) stroke to d else null
                        }
                        .minByOrNull { it.second }
                        ?.first
                    if (toDelete != null) onStrokeErased(toDelete.id)
                }

                fun appendPoint(viewX: Float, viewY: Float) {
                    val next = transform.viewToNormalizedPage(viewX, viewY)
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

                when (motionEvent.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        if (!canHandleInput) return@pointerInteropFilter false
                        isHandlingGesture = true
                        gestureTool = effectiveTool
                        if (effectiveTool == DrawingTool.ERASER) {
                            eraseAt(motionEvent.getX(pointerIndex), motionEvent.getY(pointerIndex))
                            isDrawing = false
                            currentPoints.clear()
                        } else {
                            isDrawing = true
                            currentPoints.clear()
                            appendPoint(motionEvent.getX(pointerIndex), motionEvent.getY(pointerIndex))
                        }
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (!isHandlingGesture) return@pointerInteropFilter false
                        if (gestureTool == DrawingTool.ERASER) {
                            for (historyIndex in 0 until motionEvent.historySize) {
                                eraseAt(
                                    motionEvent.getHistoricalX(pointerIndex, historyIndex),
                                    motionEvent.getHistoricalY(pointerIndex, historyIndex)
                                )
                            }
                            eraseAt(motionEvent.getX(pointerIndex), motionEvent.getY(pointerIndex))
                        } else if (isDrawing) {
                            for (historyIndex in 0 until motionEvent.historySize) {
                                appendPoint(
                                    motionEvent.getHistoricalX(pointerIndex, historyIndex),
                                    motionEvent.getHistoricalY(pointerIndex, historyIndex)
                                )
                            }
                            appendPoint(motionEvent.getX(pointerIndex), motionEvent.getY(pointerIndex))
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!isHandlingGesture) return@pointerInteropFilter false
                        if (gestureTool != DrawingTool.ERASER && isDrawing) {
                            appendPoint(motionEvent.getX(pointerIndex), motionEvent.getY(pointerIndex))
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
                        isHandlingGesture = false
                        isDrawing = false
                        currentPoints.clear()
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        val handled = isHandlingGesture
                        isHandlingGesture = false
                        isDrawing = false
                        currentPoints.clear()
                        handled
                    }
                    else -> isHandlingGesture
                }
            }
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
        if (activeStrokes === strokes && width == pageWidth && height == pageHeight) return paths
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
