package com.kkc.sheettracker.ui.viewer

import com.kkc.sheettracker.data.models.OcrBoxMetadata
import com.kkc.sheettracker.data.models.PageMetadata
import kotlin.math.sqrt

/** Tap radius around a printed part-number label, in source-image pixels at zoom 1. */
internal const val SIDECAR_LABEL_HIT_RADIUS_PX = 90f

/** Label-sized boxes around each part's printed number, from the sidecar's `ocrBoxes`. */
internal fun PageMetadata?.toSidecarDiagramBounds(): Map<Int, List<OcrBoxMetadata>> {
    val ocrBoxes = this?.ocrBoxes.orEmpty()
    if (ocrBoxes.isEmpty()) return emptyMap()
    return ocrBoxes.mapNotNull { (numText, boxes) ->
        val num = numText.toIntOrNull() ?: return@mapNotNull null
        num to boxes.take(2)
    }.toMap()
}

/**
 * Full part outlines from the sidecar's `partBoxes` (one per sheet view, so up to two on
 * double-sided sheets). Empty when the sidecar predates the field; degenerate boxes are dropped.
 */
internal fun PageMetadata?.toSidecarPartOutlines(): Map<Int, List<OcrBoxMetadata>> {
    val partBoxes = this?.partBoxes.orEmpty()
    if (partBoxes.isEmpty()) return emptyMap()
    return partBoxes.mapNotNull { (numText, boxes) ->
        val num = numText.toIntOrNull() ?: return@mapNotNull null
        val valid = boxes.filter { it.right > it.left && it.bottom > it.top }.take(2)
        if (valid.isEmpty()) null else num to valid
    }.toMap()
}

/** True when a list row at [itemOffset] with [itemSize] sits entirely inside the viewport. */
internal fun isListItemFullyVisible(itemOffset: Int, itemSize: Int, viewportStart: Int, viewportEnd: Int): Boolean =
    itemOffset >= viewportStart && itemOffset + itemSize <= viewportEnd

internal fun OcrBoxMetadata.contains(x: Float, y: Float): Boolean =
    x >= left && x <= right && y >= top && y <= bottom

/**
 * Part under a tap at ([x], [y]) in source-image pixels. A tap inside a part outline selects that
 * part (the smallest outline wins where outlines overlap); otherwise the nearest label centre within
 * [SIDECAR_LABEL_HIT_RADIUS_PX] / [zoom] wins, which is the only path for sidecars without outlines.
 */
internal fun hitSidecarPart(
    x: Float,
    y: Float,
    outlines: Map<Int, List<OcrBoxMetadata>>,
    labels: Map<Int, List<OcrBoxMetadata>>,
    zoom: Float
): Int? {
    var outlinePart: Int? = null
    var outlineArea = Float.MAX_VALUE
    for ((num, boxes) in outlines) {
        for (box in boxes) {
            if (!box.contains(x, y)) continue
            val area = (box.right - box.left).toFloat() * (box.bottom - box.top).toFloat()
            if (area < outlineArea) {
                outlineArea = area
                outlinePart = num
            }
        }
    }
    if (outlinePart != null) return outlinePart

    var bestPart: Int? = null
    var bestDist = Float.MAX_VALUE
    val maxDist = SIDECAR_LABEL_HIT_RADIUS_PX / zoom
    for ((num, boxes) in labels) {
        for (box in boxes) {
            val cx = (box.left + box.right) / 2f
            val cy = (box.top + box.bottom) / 2f
            val dist = sqrt((x - cx) * (x - cx) + (y - cy) * (y - cy))
            if (dist < bestDist && dist < maxDist) {
                bestDist = dist
                bestPart = num
            }
        }
    }
    return bestPart
}
