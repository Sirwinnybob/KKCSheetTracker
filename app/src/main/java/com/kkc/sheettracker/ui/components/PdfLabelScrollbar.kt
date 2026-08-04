package com.kkc.sheettracker.ui.components

internal fun segmentIndexForOffsetFraction(segmentCount: Int, fraction: Float): Int {
    if (segmentCount <= 0) return 0
    val clamped = fraction.coerceIn(0f, 1f)
    return (clamped * segmentCount).toInt().coerceIn(0, segmentCount - 1)
}
