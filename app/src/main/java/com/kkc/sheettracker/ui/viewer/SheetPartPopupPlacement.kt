package com.kkc.sheettracker.ui.viewer

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.abs
import kotlin.math.roundToInt

/** Choose an attachment once per selection, then follow that edge without re-ranking sides. */
internal class SheetPartPopupPlacement(
    private val viewport: IntSize,
    private val popup: IntSize,
    anchor: Rect?,
    occupied: List<Rect>,
    private val gap: Float
) {
    private enum class Side { Above, Below, Left, Right }

    private val initial = sheetPartPopupPosition(viewport, popup, anchor, occupied, gap)
    private val side = anchor?.let {
        Side.entries.minBy { side ->
            when (side) {
                Side.Above -> abs(initial.y + popup.height - anchor.top + gap)
                Side.Below -> abs(initial.y - anchor.bottom - gap)
                Side.Left -> abs(initial.x + popup.width - anchor.left + gap)
                Side.Right -> abs(initial.x - anchor.right - gap)
            }
        }
    }
    private val alignment = if (anchor == null) 0.5f else {
        val horizontal = side == Side.Above || side == Side.Below
        val span = if (horizontal) anchor.width - popup.width else anchor.height - popup.height
        val offset = if (horizontal) initial.x - anchor.left else initial.y - anchor.top
        if (abs(span) < 1f) 0.5f else (offset / span).coerceIn(0f, 1f)
    }

    fun position(anchor: Rect?): IntOffset {
        if (anchor == null || side == null) return initial
        val alignedX = anchor.left + (anchor.width - popup.width) * alignment
        val alignedY = anchor.top + (anchor.height - popup.height) * alignment
        val x = when (side) {
            Side.Above, Side.Below -> alignedX
            Side.Left -> anchor.left - popup.width - gap
            Side.Right -> anchor.right + gap
        }
        val y = when (side) {
            Side.Above -> anchor.top - popup.height - gap
            Side.Below -> anchor.bottom + gap
            Side.Left, Side.Right -> alignedY
        }
        val marginX = gap.coerceAtMost(((viewport.width - popup.width) / 2f).coerceAtLeast(0f))
        val marginY = gap.coerceAtMost(((viewport.height - popup.height) / 2f).coerceAtLeast(0f))
        return IntOffset(
            x.coerceIn(marginX, (viewport.width - popup.width - marginX).coerceAtLeast(marginX)).roundToInt(),
            y.coerceIn(marginY, (viewport.height - popup.height - marginY).coerceAtLeast(marginY)).roundToInt()
        )
    }
}
