package com.kkc.sheettracker.ui.markup

import android.view.MotionEvent
import androidx.compose.ui.unit.IntSize
import kotlin.math.sqrt

enum class DrawingTool {
    PAN_ZOOM,
    PEN,
    HIGHLIGHTER,
    ERASER
}

fun resolveEffectiveDrawingTool(
    selectedTool: DrawingTool,
    isTemporaryEraserActive: Boolean
): DrawingTool {
    return if (isTemporaryEraserActive && selectedTool != DrawingTool.ERASER) {
        DrawingTool.ERASER
    } else {
        selectedTool
    }
}

fun shouldAppendStrokePoint(
    lastX: Float,
    lastY: Float,
    nextX: Float,
    nextY: Float,
    minDistance: Float = 0.0012f
): Boolean {
    val dx = nextX - lastX
    val dy = nextY - lastY
    return sqrt(dx * dx + dy * dy) > minDistance
}

fun finalizeStrokePoints(
    points: List<Float>,
    activeThickness: Float,
    canvasWidth: Float,
    canvasHeight: Float
): List<Float> {
    // Snapshot the live gesture buffer so completed strokes survive after the in-progress list is cleared.
    if (points.size != 2) return points.toList()
    val x = points[0].coerceIn(0f, 1f)
    val y = points[1].coerceIn(0f, 1f)
    val normalizedNudgeX = (activeThickness.coerceAtLeast(1f) / canvasWidth.coerceAtLeast(1f)) * 0.75f
    val normalizedNudgeY = (activeThickness.coerceAtLeast(1f) / canvasHeight.coerceAtLeast(1f)) * 0.75f
    return listOf(
        x,
        y,
        (x + normalizedNudgeX).coerceIn(0f, 1f),
        (y + normalizedNudgeY).coerceIn(0f, 1f)
    )
}

fun distanceToSegment(
    px: Float,
    py: Float,
    ax: Float,
    ay: Float,
    bx: Float,
    by: Float
): Float {
    val dx = bx - ax
    val dy = by - ay
    val lenSq = dx * dx + dy * dy
    val t = if (lenSq > 0f) {
        ((px - ax) * dx + (py - ay) * dy) / lenSq
    } else {
        0f
    }.coerceIn(0f, 1f)
    val projX = ax + t * dx
    val projY = ay + t * dy
    val diffX = px - projX
    val diffY = py - projY
    return sqrt(diffX * diffX + diffY * diffY)
}

fun distanceToScaledStroke(
    px: Float,
    py: Float,
    points: List<Float>,
    canvasWidth: Float,
    canvasHeight: Float
): Float {
    var minDistance = Float.MAX_VALUE
    for (i in 0 until points.size - 3 step 2) {
        val ax = points[i] * canvasWidth
        val ay = points[i + 1] * canvasHeight
        val bx = points[i + 2] * canvasWidth
        val by = points[i + 3] * canvasHeight
        val distance = distanceToSegment(px, py, ax, ay, bx, by)
        if (distance < minDistance) {
            minDistance = distance
        }
    }
    return minDistance
}

fun distanceToViewStroke(
    px: Float,
    py: Float,
    points: List<Float>,
    transform: PdfPageTransform
): Float {
    var minDistance = Float.MAX_VALUE
    for (i in 0 until points.size - 3 step 2) {
        val (ax, ay) = transform.normalizedPageToView(points[i], points[i + 1])
        val (bx, by) = transform.normalizedPageToView(points[i + 2], points[i + 3])
        val distance = distanceToSegment(px, py, ax, ay, bx, by)
        if (distance < minDistance) {
            minDistance = distance
        }
    }
    return minDistance
}

fun findRelevantMotionEventPointerIndex(motionEvent: MotionEvent): Int {
    return findRelevantPointerIndex(
        pointerCount = motionEvent.pointerCount,
        actionIndex = motionEvent.actionIndex,
        toolTypeAt = motionEvent::getToolType
    )
}

internal fun findRelevantPointerIndex(
    pointerCount: Int,
    actionIndex: Int,
    toolTypeAt: (Int) -> Int
): Int {
    if (pointerCount <= 0) return 0

    val safeActionIndex = actionIndex
        .takeIf { it in 0 until pointerCount }
        ?: 0
    val actionToolType = toolTypeAt(safeActionIndex)
    if (actionToolType == MotionEvent.TOOL_TYPE_STYLUS || actionToolType == MotionEvent.TOOL_TYPE_ERASER) {
        return safeActionIndex
    }

    for (pointerIndex in 0 until pointerCount) {
        val toolType = toolTypeAt(pointerIndex)
        if (toolType == MotionEvent.TOOL_TYPE_STYLUS || toolType == MotionEvent.TOOL_TYPE_ERASER) {
            return pointerIndex
        }
    }

    return safeActionIndex
}

data class PdfPageTransform(
    val viewWidth: Float,
    val viewHeight: Float,
    val zoom: Float,
    val panX: Float,
    val panY: Float,
    val pageLeft: Float,
    val pageTop: Float,
    val pageWidth: Float,
    val pageHeight: Float
) {
    val pageRight: Float
        get() = pageLeft + pageWidth

    val pageBottom: Float
        get() = pageTop + pageHeight

    fun viewToNormalizedPage(xView: Float, yView: Float): Pair<Float, Float> {
        val cx = viewWidth / 2f
        val cy = viewHeight / 2f
        val xBase = ((xView - cx - panX) / zoom) + cx
        val yBase = ((yView - cy - panY) / zoom) + cy
        val pageX = ((xBase - pageLeft) / pageWidth).coerceIn(0f, 1f)
        val pageY = ((yBase - pageTop) / pageHeight).coerceIn(0f, 1f)
        return pageX to pageY
    }

    fun normalizedPageToView(xNormalized: Float, yNormalized: Float): Pair<Float, Float> {
        val cx = viewWidth / 2f
        val cy = viewHeight / 2f
        val xBase = pageLeft + xNormalized.coerceIn(0f, 1f) * pageWidth
        val yBase = pageTop + yNormalized.coerceIn(0f, 1f) * pageHeight
        val xView = ((xBase - cx) * zoom) + cx + panX
        val yView = ((yBase - cy) * zoom) + cy + panY
        return xView to yView
    }
}

fun computePdfPageTransform(
    viewSize: IntSize,
    pageAspectRatio: Float,
    zoom: Float,
    panX: Float,
    panY: Float
): PdfPageTransform {
    val viewWidth = viewSize.width.toFloat().coerceAtLeast(1f)
    val viewHeight = viewSize.height.toFloat().coerceAtLeast(1f)
    val safeAspect = pageAspectRatio.coerceAtLeast(0.01f)
    val fitWidth = minOf(viewWidth, viewHeight * safeAspect)
    val fitHeight = minOf(viewHeight, viewWidth / safeAspect)
    val pageLeft = (viewWidth - fitWidth) / 2f
    val pageTop = (viewHeight - fitHeight) / 2f
    return PdfPageTransform(
        viewWidth = viewWidth,
        viewHeight = viewHeight,
        zoom = zoom,
        panX = panX,
        panY = panY,
        pageLeft = pageLeft,
        pageTop = pageTop,
        pageWidth = fitWidth,
        pageHeight = fitHeight
    )
}
