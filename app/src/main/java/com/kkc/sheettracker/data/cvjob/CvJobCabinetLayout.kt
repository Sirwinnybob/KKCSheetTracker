package com.kkc.sheettracker.data.cvjob

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import kotlin.math.abs

data class CabinetOpening(val id: String, val rect: DoorRect, val polygon: List<DoorPoint>? = null)
data class CabinetFront(val opening: String, val doorId: String?, val rect: DoorRect)
data class CabinetFrameMember(val code: String, val rect: DoorRect, val part: DoorPart, val polygon: List<DoorPoint>? = null)
data class CabinetSlope(val degrees: Double, val highOnLeft: Boolean)
data class CabinetFrontLayout(
    val faceWidth: Double, val faceHeight: Double,
    val openings: List<CabinetOpening>, val fronts: List<CabinetFront>,
    val frameMembers: List<CabinetFrameMember>, val slope: CabinetSlope? = null
)

sealed interface FrontShape {
    data class Rect(val rect: DoorRect) : FrontShape
    data class Outline(val points: List<DoorPoint>) : FrontShape
    data object Blank : FrontShape
}

/** A door's own shape is independent of the cabinet's slope. Unknown shaped fronts stay blank. */
internal fun frontShape(layout: CabinetFrontLayout, front: CabinetFront, doorsById: Map<String, DoorPreviewData>): FrontShape {
    val door = doorsById[front.doorId]
    return when (val slope = door?.geometry?.slope) {
        DoorSlope.None -> FrontShape.Rect(front.rect)
        is DoorSlope.Verified -> FrontShape.Outline(slope.outline.map { DoorPoint(it.x + front.rect.x, it.y + front.rect.y) })
        is DoorSlope.Refused -> FrontShape.Blank
        null -> if (layout.slope == null) FrontShape.Rect(front.rect) else FrontShape.Blank
    }
}

private fun JsonElement?.obj() = this?.takeIf { it.isJsonObject }?.asJsonObject
private fun JsonObject.text(key: String) = get(key)?.takeIf { it.isJsonPrimitive }?.asString
private fun JsonObject.number(key: String) = text(key)?.toDoubleOrNull()?.takeIf { it.isFinite() }
private fun JsonObject.objects(key: String): List<JsonObject>? {
    val array = get(key)?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
    return array.map { it.obj() ?: return null }
}
private fun JsonObject.rect(): DoorRect? {
    val x = number("x") ?: return null; val y = number("y") ?: return null
    val w = number("width")?.takeIf { it > 0.0 } ?: return null
    val h = number("height")?.takeIf { it > 0.0 } ?: return null
    if (!(x + w).isFinite() || !(y + h).isFinite()) return null
    return DoorRect(x, y, w, h)
}
/** A present polygon must be a finite, convex four-corner piece within its reported bounds. */
private fun polygon(entry: JsonObject, rect: DoorRect): List<DoorPoint>? {
    val points = entry.objects("polygon")?.map { DoorPoint(it.number("x") ?: return null, it.number("y") ?: return null) } ?: return null
    if (points.size != 4 || points.any { it.x < rect.x - 0.002 || it.x > rect.x + rect.width + 0.002 || it.y < rect.y - 0.002 || it.y > rect.y + rect.height + 0.002 }) return null
    val crosses = points.indices.map { i ->
        val a = points[i]; val b = points[(i + 1) % 4]; val c = points[(i + 2) % 4]
        (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
    }
    if (crosses.any { !it.isFinite() || abs(it) < 1e-10 } || !(crosses.all { it > 0.0 } || crosses.all { it < 0.0 })) return null
    return points
}
private fun parseLayout(entry: JsonObject, parts: Map<String, JsonObject>): CabinetFrontLayout? {
    val face = entry.get("face").obj() ?: return null
    val width = face.number("width")?.takeIf { it > 0.0 } ?: return null
    val height = face.number("height")?.takeIf { it > 0.0 } ?: return null
    val openings = entry.objects("openings")?.map { o ->
        val id = o.text("id")?.takeIf { it.isNotBlank() } ?: return null
        val rect = o.get("rect").obj()?.rect() ?: return null
        val poly = if (o.has("polygon")) polygon(o, rect) ?: return null else null
        CabinetOpening(id, rect, poly)
    } ?: return null
    if (openings.map { it.id }.distinct().size != openings.size) return null
    val fronts = entry.objects("fronts")?.map { f ->
        val opening = f.text("opening")?.takeIf { id -> openings.any { it.id == id } } ?: return null
        val rect = f.get("rect").obj()?.rect() ?: return null
        CabinetFront(opening, f.text("doorId")?.takeIf { it.isNotBlank() }, rect)
    } ?: return null
    val doorIds = fronts.mapNotNull { it.doorId }
    if (doorIds.distinct().size != doorIds.size) return null
    val members = entry.objects("frameMembers")?.map { m ->
        val code = m.text("code")?.takeIf { it in setOf("LS", "RS", "MS", "TR", "BR", "MR") } ?: return null
        val rect = m.get("rect").obj()?.rect() ?: return null
        val poly = if (m.has("polygon")) polygon(m, rect) ?: return null else null
        val id = m.text("part") ?: return null
        val p = parts[id] ?: return null
        if (p.number("width")?.takeIf { it > 0.0 } == null || p.number("length")?.takeIf { it > 0.0 } == null) return null
        CabinetFrameMember(code, rect, DoorPart(id, "frame", p.text("description").orEmpty(), p.text("material").orEmpty(),
            p.text("width").orEmpty(), p.text("length").orEmpty(), p.text("quantity")?.toIntOrNull() ?: 1), poly)
    } ?: return null
    if (members.map { it.part.partId }.distinct().size != members.size) return null
    val slope = if (entry.has("slope")) {
        val s = entry.get("slope").obj() ?: return null
        val degrees = s.number("degrees")?.takeIf { it > 0.0 && it < 90.0 } ?: return null
        CabinetSlope(degrees, s.text("highOnLeft")?.toBooleanStrictOrNull() ?: return null)
    } else null
    return CabinetFrontLayout(width, height, openings, fronts, members, slope)
}

/** Malformed layout entries are omitted atomically; the exporter remains the geometry authority. */
internal fun parseCabinetLayouts(root: JsonObject, partsById: Map<String, JsonObject>): Map<String, CabinetFrontLayout> {
    val entries = root.objects("cabinetLayouts") ?: return emptyMap()
    val counts = entries.mapNotNull { it.text("cabinet") }.groupingBy { it }.eachCount()
    return entries.mapNotNull { entry ->
        val id = entry.text("cabinet")?.takeIf { it.isNotBlank() && counts[it] == 1 } ?: return@mapNotNull null
        parseLayout(entry, partsById)?.let { id to it }
    }.toMap()
}
