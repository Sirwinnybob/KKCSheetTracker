package com.kkc.sheettracker.ui.doorpreview

import com.kkc.sheettracker.data.cvjob.DoorConstruction
import com.kkc.sheettracker.data.cvjob.DoorGeometry
import com.kkc.sheettracker.data.cvjob.DoorHingeSide
import com.kkc.sheettracker.data.cvjob.DoorPoint
import com.kkc.sheettracker.data.cvjob.DoorRect
import com.kkc.sheettracker.data.cvjob.DoorSlope
import com.kkc.sheettracker.data.cvjob.DoorMaterialRef
import kotlin.math.min

/*
 * Pure mapping from published door geometry to drawing shapes, so the Canvas only paints.
 * Shapes are in door inches: origin at the door's bottom-left seen from the front, x across, y up
 * (the job_data contract's coordinates). [DoorPreviewFit] turns them into canvas pixels (y down).
 */

/** 35 mm cup, the size the shop's hinges bore. */
const val HINGE_CUP_DIAMETER_IN = 35.0 / 25.4

enum class DoorShapeRole {
    OUTLINE,
    STILE,
    RAIL,
    PANEL,
    GLASS,
    /** Panel edge hidden in the frame groove (dashed). */
    HIDDEN_PANEL_EDGE,
    /** Rail tenon inside a stile (dashed). */
    TENON
}

sealed interface DoorShape {
    data class Rect(val rect: DoorRect, val role: DoorShapeRole, val horizontalGrain: Boolean? = null) : DoorShape
    /** A piece that is not a rectangle (an angled door), corners in door inches. */
    data class Poly(val points: List<DoorPoint>, val role: DoorShapeRole) : DoorShape
    data class HingeCup(val center: DoorPoint, val radius: Double) : DoorShape
}

data class DoorDrawing(
    val width: Double,
    val height: Double,
    /** Paint order: fills first, then members, dashed hidden lines, hinge cups, outline last. */
    val shapes: List<DoorShape>,
    /** Plain-language notes about what is not drawn. */
    val notes: List<String>,
    /** The publisher's `checks`, shown as a warning badge. */
    val warnings: List<String>,
    /** Direction for panel/slab faces; frame members retain their own lengthwise grain. */
    val panelGrainVertical: Boolean = true,
    val showFaceGrain: Boolean = true,
    /** The door's face when it is not a rectangle (a verified angled door); null means the width x height box. */
    val outline: List<DoorPoint>? = null,
    /** A door that is clearly not rectangular but whose outline could not be proven: nothing is painted. */
    val refused: Boolean = false,
    val faceMaterial: DoorMaterialRef = DoorMaterialRef(),
    val surfaceMaterials: Map<Int, DoorMaterialRef> = emptyMap()
)

const val OUTLINE_ONLY_NOTE = "Mid rails, mullions or several panels: outline only"
const val INVALID_LAYOUT_NOTE = "Door layout incomplete or invalid: outline only"
const val FRAME_MISSING_NOTE = "Frame sizes missing: outline only"

fun buildDoorDrawing(door: DoorGeometry): DoorDrawing {
    val w = door.width
    val h = door.height
    when (val slope = door.slope) {
        is DoorSlope.Verified -> return slopedDoorDrawing(door, slope)
        is DoorSlope.Refused -> return DoorDrawing(
            width = w,
            height = h,
            shapes = emptyList(),
            notes = listOf("$ANGLED_NOT_DRAWN_NOTE (${slope.reason})"),
            warnings = door.checks,
            panelGrainVertical = !door.horizontalGrain,
            showFaceGrain = door.showFaceGrain,
            refused = true
        )
        DoorSlope.None -> Unit
    }
    val notes = mutableListOf<String>()
    val fills = mutableListOf<DoorShape>()
    val members = mutableListOf<DoorShape>()
    val hidden = mutableListOf<DoorShape>()
    val hardware = mutableListOf<DoorShape>()

    val layoutShapes = door.layout?.let(::buildMultiPanelShapes).orEmpty()
    if (door.hasInvalidLayout) {
        notes += INVALID_LAYOUT_NOTE
    } else if (door.layout == null) when (door.construction) {
        DoorConstruction.FIVE_PIECE -> {
            val frame = door.frame
            if (frame == null) {
                notes += FRAME_MISSING_NOTE
            } else {
                val railLength = w - frame.leftStile - frame.rightStile
                // Stiles run the full height; rails sit between them.
                members += DoorShape.Rect(DoorRect(0.0, 0.0, frame.leftStile, h), DoorShapeRole.STILE)
                members += DoorShape.Rect(DoorRect(w - frame.rightStile, 0.0, frame.rightStile, h), DoorShapeRole.STILE)
                members += DoorShape.Rect(DoorRect(frame.leftStile, h - frame.topRail, railLength, frame.topRail), DoorShapeRole.RAIL)
                members += DoorShape.Rect(DoorRect(frame.leftStile, 0.0, railLength, frame.bottomRail), DoorShapeRole.RAIL)

                val panel = door.panel
                val opening = panel?.opening ?: DoorRect(
                    frame.leftStile,
                    frame.bottomRail,
                    railLength,
                    h - frame.topRail - frame.bottomRail
                )
                if (opening.width > 0.0 && opening.height > 0.0) {
                    fills += DoorShape.Rect(opening, if (panel?.isGlass == true) DoorShapeRole.GLASS else DoorShapeRole.PANEL)
                    val insetX = panel?.insetX ?: 0.0
                    val insetY = panel?.insetY ?: 0.0
                    if (insetX > 0.0 || insetY > 0.0) {
                        hidden += DoorShape.Rect(
                            DoorRect(opening.x - insetX, opening.y - insetY, opening.width + 2 * insetX, opening.height + 2 * insetY),
                            DoorShapeRole.HIDDEN_PANEL_EDGE
                        )
                    }
                }

                val tenon = frame.tenon ?: 0.0
                if (tenon > 0.0) {
                    for ((railY, railHeight) in listOf(h - frame.topRail to frame.topRail, 0.0 to frame.bottomRail)) {
                        hidden += DoorShape.Rect(DoorRect(frame.leftStile - tenon, railY, tenon, railHeight), DoorShapeRole.TENON)
                        hidden += DoorShape.Rect(DoorRect(w - frame.rightStile, railY, tenon, railHeight), DoorShapeRole.TENON)
                    }
                }
            }
        }
        DoorConstruction.SLAB -> Unit
        DoorConstruction.OTHER -> notes += OUTLINE_ONLY_NOTE
    }

    if (!door.hasInvalidLayout && (door.layout != null || door.construction != DoorConstruction.OTHER)) {
        hardware += hingeCups(door)
        if (door.hinges.side == DoorHingeSide.OTHER) {
            notes += "Hinge code ${door.hinges.code.ifBlank { "?" }}: side not confirmed, no hinges drawn"
        }
    }

    val outline = DoorShape.Rect(DoorRect(0.0, 0.0, w, h), DoorShapeRole.OUTLINE)
    return DoorDrawing(
        width = w,
        height = h,
        shapes = layoutShapes + fills + members + hidden + hardware + outline,
        notes = notes,
        warnings = door.checks,
        panelGrainVertical = !door.horizontalGrain,
        showFaceGrain = door.showFaceGrain
    )
}

const val ANGLED_NOT_DRAWN_NOTE = "Angled door: not drawn"

/** The exporter measures an angled door as if it were square, so it flags these two sizes. */
private val SLOPE_FALSE_WARNINGS = setOf(
    "Rail length does not match the door width; check the frame data",
    "Panel size does not fit the frame opening; check the panel data"
)

/** Pieces and outline of a verified angled door; hinge cups use CV's door coordinates as they are. */
private fun slopedDoorDrawing(door: DoorGeometry, slope: DoorSlope.Verified): DoorDrawing {
    val fills = mutableListOf<DoorShape>()
    val members = mutableListOf<DoorShape>()
    for (member in slope.members) {
        when (member.role) {
            "panel" -> fills += DoorShape.Poly(member.polygon, DoorShapeRole.PANEL)
            "leftStile", "rightStile" -> members += DoorShape.Poly(member.polygon, DoorShapeRole.STILE)
            else -> members += DoorShape.Poly(member.polygon, DoorShapeRole.RAIL)
        }
    }
    val notes = mutableListOf(
        "Angled door: top slopes ${"%.2f".format(java.util.Locale.US, slope.slopeDegrees)}° " +
            "(high side ${if (slope.highOnLeft) "left" else "right"}); outline built from the cut sizes " +
            "and checked against the rails and panel"
    )
    if (door.hinges.side == DoorHingeSide.OTHER) {
        notes += "Hinge code ${door.hinges.code.ifBlank { "?" }}: side not confirmed, no hinges drawn"
    }
    return DoorDrawing(
        width = door.width,
        height = door.height,
        shapes = fills + members + hingeCups(door) + DoorShape.Poly(slope.outline, DoorShapeRole.OUTLINE),
        notes = notes,
        warnings = door.checks.filterNot { it in SLOPE_FALSE_WARNINGS },
        panelGrainVertical = !door.horizontalGrain,
        showFaceGrain = door.showFaceGrain,
        outline = slope.outline
    )
}

/**
 * Cup centres are CV's published `points` (verified to share the door coordinates on job 688).
 * A door without points or without `edgeOffset` gets no cups: many doors have no hinges, and
 * guessing from `positions` would draw hinges that are not there. Doors whose side is none or
 * unconfirmed get none either. Cups off the door are dropped.
 */
internal fun hingeCups(door: DoorGeometry): List<DoorShape.HingeCup> {
    val hinges = door.hinges
    if (hinges.side == DoorHingeSide.NONE || hinges.side == DoorHingeSide.OTHER) return emptyList()
    if (hinges.edgeOffset == null) return emptyList()
    return hinges.points
        .filter { it.x in 0.0..door.width && it.y in 0.0..door.height }
        .map { DoorShape.HingeCup(it, HINGE_CUP_DIAMETER_IN / 2) }
}

/** Scale-to-fit transform from door inches (y up) to canvas pixels (y down), centred. */
data class DoorPreviewFit(val scale: Float, val offsetX: Float, val offsetY: Float, val doorHeight: Double) {
    fun x(inches: Double): Float = offsetX + (inches * scale).toFloat()
    fun y(inches: Double): Float = offsetY + ((doorHeight - inches) * scale).toFloat()

    /** Canvas left, top, right, bottom of a door-coordinate rectangle. */
    fun rect(r: DoorRect): FloatArray = floatArrayOf(x(r.x), y(r.y + r.height), x(r.x + r.width), y(r.y))

    fun length(inches: Double): Float = (inches * scale).toFloat()

    companion object {
        fun of(doorWidth: Double, doorHeight: Double, canvasWidth: Float, canvasHeight: Float, padding: Float): DoorPreviewFit {
            val availW = (canvasWidth - 2 * padding).coerceAtLeast(1f)
            val availH = (canvasHeight - 2 * padding).coerceAtLeast(1f)
            val scale = min(availW / doorWidth.toFloat(), availH / doorHeight.toFloat())
            val drawnW = (doorWidth * scale).toFloat()
            val drawnH = (doorHeight * scale).toFloat()
            return DoorPreviewFit(
                scale = scale,
                offsetX = (canvasWidth - drawnW) / 2,
                offsetY = (canvasHeight - drawnH) / 2,
                doorHeight = doorHeight
            )
        }
    }
}

/** Decimal inches, up to four places to match CV's published precision. */
fun formatDoorInches(value: Double): String {
    val text = "%.4f".format(java.util.Locale.US, value).trimEnd('0').trimEnd('.')
    return if (text == "-0") "0" else text
}
