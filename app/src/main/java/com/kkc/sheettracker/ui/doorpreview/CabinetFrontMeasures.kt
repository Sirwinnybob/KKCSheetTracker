package com.kkc.sheettracker.ui.doorpreview

import com.kkc.sheettracker.data.cvjob.CabinetFront
import com.kkc.sheettracker.data.cvjob.CabinetFrontLayout
import com.kkc.sheettracker.data.cvjob.FrontShape
import java.util.Locale
import kotlin.math.abs
import kotlin.math.round

internal data class FrontMeasures(val framed: Boolean,
    val left: Double?, val right: Double?, val top: Double?, val bottom: Double?,
    val toNeighborAcross: Double?, val toNeighborAbove: Double?)

private fun measured(value: Double): Double {
    val step = round(value * 64) / 64
    return if (abs(value - step) <= 0.0003) step else round(value * 10000) / 10000
}

/** Measures actual placements only. CV's reveal settings are deliberately not inputs. */
internal fun frontMeasures(layout: CabinetFrontLayout, front: CabinetFront, shape: FrontShape): FrontMeasures {
    val framed = layout.frameMembers.isNotEmpty()
    if (shape !is FrontShape.Rect) return FrontMeasures(framed, null, null, null, null, null, null)
    val r = shape.rect
    val opening = layout.openings.singleOrNull { it.id == front.opening }?.rect
    val sides = if (framed && opening != null) listOf(opening.x - r.x,
        r.x + r.width - opening.x - opening.width, r.y + r.height - opening.y - opening.height, opening.y - r.y)
    else if (!framed) listOf(r.x, layout.faceWidth - r.x - r.width, layout.faceHeight - r.y - r.height, r.y)
    else return FrontMeasures(true, null, null, null, null, null, null)
    val others = layout.fronts.filter { it != front }.map { it.rect }
    val across = others.filter { minOf(it.y + it.height, r.y + r.height) - maxOf(it.y, r.y) > 0.002 }
        .mapNotNull { other ->
            when {
                other.x >= r.x + r.width - 0.002 -> other.x - r.x - r.width
                r.x >= other.x + other.width - 0.002 -> r.x - other.x - other.width
                else -> null
            }
        }.minOrNull()
    val above = others.filter { minOf(it.x + it.width, r.x + r.width) - maxOf(it.x, r.x) > 0.002 && it.y >= r.y + r.height - 0.002 }
        .minOfOrNull { it.y - r.y - r.height }
    val s = sides.map(::measured)
    return FrontMeasures(framed, s[0], s[1], s[2], s[3], across?.let(::measured), above?.let(::measured))
}

internal fun frontMeasureLines(m: FrontMeasures): List<String> {
    if (listOf(m.left, m.right, m.top, m.bottom, m.toNeighborAcross, m.toNeighborAbove).all { it == null })
        return listOf("Angled door: reveals not measured")
    fun number(v: Double) = String.format(Locale.US, "%.4f", abs(v)).trimEnd('0').trimEnd('.').ifEmpty { "0" }
    val lines = mutableListOf<String>()
    for ((label, value) in listOf("Left" to m.left, "Right" to m.right, "Top" to m.top, "Bottom" to m.bottom)) {
        if (value == null) continue
        val kind = if (m.framed) { if (value < 0) "inset" else "overlay" } else { if (value < 0) "overhang" else "reveal" }
        lines += "$label $kind ${number(value)}"
    }
    m.toNeighborAcross?.let { lines += "Gap to next door ${number(it)}" }
    m.toNeighborAbove?.let { lines += "Gap to door above ${number(it)}" }
    return lines
}
