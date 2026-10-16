package com.kkc.sheettracker.data.cvjob

import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.sqrt

/*
 * A five-piece door whose top is cut at an angle (a sloped cabinet). CV publishes only the door's
 * bounding size, but every piece's cut size and the allowances are published, and they determine
 * the outline: the two stiles differ in length, the low stile's long point sits at its inner edge,
 * and the top rail is a parallelogram with vertical ends. The outline is accepted only when three
 * further published numbers (top rail, bottom rail, panel) reproduce; otherwise the door is
 * refused and the tablet draws nothing for it. See
 * docs/superpowers/specs/2026-10-16-sloped-door-drawing-design.md.
 */
sealed interface DoorSlope {
    /** An ordinary door: the stiles are the same length (or there is nothing to compare). */
    data object None : DoorSlope

    /** The door is clearly not rectangular but its published sizes do not prove an outline. */
    data class Refused(val reason: String) : DoorSlope

    /**
     * [outline] is bottom left, bottom right, top right, top left in door inches (y up). [members]
     * are in the order left stile, right stile, top rail, bottom rail, panel (the visible opening).
     */
    data class Verified(
        val outline: List<DoorPoint>,
        val members: List<SlopedMember>,
        val highOnLeft: Boolean,
        val slopeDegrees: Double
    ) : DoorSlope
}

data class SlopedMember(val role: String, val partId: String, val polygon: List<DoorPoint>)

private const val TOLERANCE = 0.002

private fun inches(value: Double): String =
    "%.4f".format(Locale.US, value).trimEnd('0').trimEnd('.')

private fun String.toLength(): Double? = trim().toDoubleOrNull()?.takeIf { it.isFinite() }

internal fun deriveDoorSlope(door: DoorGeometry, parts: List<DoorPart>): DoorSlope {
    if (door.construction != DoorConstruction.FIVE_PIECE || door.layout != null || door.hasInvalidLayout) {
        return DoorSlope.None
    }
    val frame = door.frame ?: return DoorSlope.None
    val leftPart = parts.singleOrNull { it.role == "leftStile" }
    val rightPart = parts.singleOrNull { it.role == "rightStile" }
    val leftCut = leftPart?.length?.toLength()
    val rightCut = rightPart?.length?.toLength()
    // Without both stile lengths there is nothing to compare, so the door is treated as ordinary.
    if (leftPart == null || rightPart == null || leftCut == null || rightCut == null) return DoorSlope.None
    if (abs(leftCut - rightCut) <= TOLERANCE) return DoorSlope.None

    // From here the door is clearly shaped: anything unproven is refused, never drawn as a rectangle.
    fun refuse(reason: String) = DoorSlope.Refused(reason)
    val allowance = frame.stileLengthAllowance ?: return refuse("the stile length allowance is not published")
    val tenon = frame.tenon ?: return refuse("the tenon size is not published")
    val leftLength = leftCut - allowance
    val rightLength = rightCut - allowance
    val highOnLeft = leftLength > rightLength
    val highLength = if (highOnLeft) leftLength else rightLength
    val lowLength = if (highOnLeft) rightLength else leftLength
    val width = door.width
    val height = door.height
    val leftWidth = frame.leftStile
    val rightWidth = frame.rightStile
    val lowWidth = if (highOnLeft) rightWidth else leftWidth
    val span = width - leftWidth - rightWidth

    if (abs(highLength - height) > TOLERANCE) {
        return refuse("the taller stile (${inches(highLength)}) does not match the door height (${inches(height)})")
    }
    if (span <= 0.0 || width - lowWidth <= 0.0) return refuse("the stiles leave no room for the rails")

    val s = (highLength - lowLength) / (width - lowWidth)
    val k = sqrt(1 + s * s)
    fun top(x: Double) = if (highOnLeft) highLength - s * x else highLength - s * (width - x)

    val lowOuter = top(if (highOnLeft) width else 0.0)
    if (lowOuter <= TOLERANCE) {
        return refuse("the outline does not close: the low side would end at ${inches(lowOuter)}")
    }
    val topRailThickness = frame.topRail * k
    val x1 = leftWidth
    val x2 = width - rightWidth
    val lowestTopRailEdge = minOf(top(x1), top(x2)) - topRailThickness
    if (lowestTopRailEdge < frame.bottomRail - TOLERANCE) {
        return refuse("the outline does not close: the top rail would meet the bottom rail")
    }

    // Check 1: top rail is a parallelogram with vertical ends; its cut length runs along its axis.
    val topRailCut = parts.singleOrNull { it.role == "topRail" }
    val topRailCutLength = topRailCut?.length?.toLength()
        ?: return refuse("the top rail part or its length is missing")
    val topRailFinished = topRailCutLength - 2 * tenon
    val topRailExpected = span * k + frame.topRail * s
    if (abs(topRailFinished - topRailExpected) > TOLERANCE) {
        return refuse(
            "the top rail (${inches(topRailFinished)}) does not match the angled top (${inches(topRailExpected)})"
        )
    }

    // Check 2: the bottom rail is straight between the stiles.
    val bottomRailCut = parts.singleOrNull { it.role == "bottomRail" }
    val bottomRailCutLength = bottomRailCut?.length?.toLength()
        ?: return refuse("the bottom rail part or its length is missing")
    val bottomRailFinished = bottomRailCutLength - 2 * tenon
    if (abs(bottomRailFinished - span) > TOLERANCE) {
        return refuse("the bottom rail (${inches(bottomRailFinished)}) does not match the gap between the stiles (${inches(span)})")
    }

    // Check 3: the panel is the visible opening plus its grooves, measured along the slope.
    val panelPart = parts.singleOrNull { it.role == "panel" }
        ?: return refuse("the panel part is missing")
    val panel = door.panel ?: return refuse("the panel data is missing")
    val insetX = panel.insetX ?: return refuse("the panel groove depths are not published")
    val insetY = panel.insetY ?: return refuse("the panel groove depths are not published")
    val panelWidth = panelPart.width.toLength() ?: return refuse("the panel size is missing")
    val panelLength = panelPart.length.toLength() ?: return refuse("the panel size is missing")
    val expectedPanelWidth = span + 2 * insetX
    val highEdge = if (highOnLeft) x1 - insetX else x2 + insetX
    val expectedPanelLength = (top(highEdge) - topRailThickness + insetY * k) - (frame.bottomRail - insetY)
    if (abs(panelWidth - expectedPanelWidth) > TOLERANCE || abs(panelLength - expectedPanelLength) > TOLERANCE) {
        return refuse(
            "the panel (${inches(panelWidth)} x ${inches(panelLength)}) does not match the opening " +
                "(${inches(expectedPanelWidth)} x ${inches(expectedPanelLength)})"
        )
    }

    fun p(x: Double, y: Double) = DoorPoint(x, y)
    val members = listOf(
        SlopedMember("leftStile", leftPart.partId,
            listOf(p(0.0, 0.0), p(x1, 0.0), p(x1, top(x1)), p(0.0, top(0.0)))),
        SlopedMember("rightStile", rightPart.partId,
            listOf(p(x2, 0.0), p(width, 0.0), p(width, top(width)), p(x2, top(x2)))),
        SlopedMember("topRail", topRailCut.partId,
            listOf(p(x1, top(x1) - topRailThickness), p(x2, top(x2) - topRailThickness), p(x2, top(x2)), p(x1, top(x1)))),
        SlopedMember("bottomRail", bottomRailCut.partId,
            listOf(p(x1, 0.0), p(x2, 0.0), p(x2, frame.bottomRail), p(x1, frame.bottomRail))),
        SlopedMember("panel", panelPart.partId,
            listOf(p(x1, frame.bottomRail), p(x2, frame.bottomRail),
                p(x2, top(x2) - topRailThickness), p(x1, top(x1) - topRailThickness)))
    )
    return DoorSlope.Verified(
        outline = listOf(p(0.0, 0.0), p(width, 0.0), p(width, top(width)), p(0.0, top(0.0))),
        members = members,
        highOnLeft = highOnLeft,
        slopeDegrees = Math.toDegrees(atan(s))
    )
}
