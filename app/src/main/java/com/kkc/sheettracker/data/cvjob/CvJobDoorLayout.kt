package com.kkc.sheettracker.data.cvjob

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import kotlin.math.abs

/** Positioned geometry is optional: legacy five-piece/slab files still use their original path. */
data class DoorLayout(val members: List<DoorLayoutMember>, val panels: List<DoorLayoutPanel>)
data class DoorLayoutMember(val partId: String, val vertical: Boolean, val rect: DoorRect, val tenon: Double)
data class DoorLayoutPanel(val partId: String, val panel: DoorPanel, val horizontalGrain: Boolean)

/** Reject the whole layout if any piece is absent, outside the door, overlapping, or incomplete. */
internal fun parseDoorLayout(value: JsonElement, entry: JsonObject, width: Double, height: Double): DoorLayout? {
    if (!value.isJsonObject) return null
    val layout = value.asJsonObject
    fun JsonObject.text(key: String) = get(key)?.takeIf { it.isJsonPrimitive }?.asString
    fun JsonObject.number(key: String) = text(key)?.toDoubleOrNull()?.takeIf { it.isFinite() }
    fun JsonObject.rows(key: String): List<JsonObject>? {
        val a = get(key)?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
        if (a.any { !it.isJsonObject }) return null
        return a.map { it.asJsonObject }
    }
    fun rect(o: JsonObject, key: String): DoorRect? {
        val r = o.get(key)?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        val x = r.number("x") ?: return null
        val y = r.number("y") ?: return null
        val w = r.number("width") ?: return null
        val h = r.number("height") ?: return null
        if (x < 0 || y < 0 || w <= 0 || h <= 0 || x+w > width+0.002 || y+h > height+0.002) return null
        return DoorRect(x,y,w,h)
    }
    fun inside(r: DoorRect) = r.x >= -0.002 && r.y >= -0.002 &&
        r.x+r.width <= width+0.002 && r.y+r.height <= height+0.002
    val components = entry.rows("components")?.mapNotNull { it.text("part") }?.toSet() ?: return null
    val used = mutableSetOf<String>()
    val memberRows = layout.rows("members") ?: return null
    val panelRows = layout.rows("panels") ?: return null
    if (memberRows.isEmpty() || panelRows.isEmpty()) return null
    val members = memberRows.map { m ->
        val id = m.text("part")?.takeIf { it in components && used.add(it) } ?: return null
        val vertical = when(m.text("role")) { "stile" -> true; "rail" -> false; else -> return null }
        val r = rect(m,"rect") ?: return null
        val t = m.number("tenon")?.takeIf { it >= 0 } ?: return null
        val extended = if (vertical) DoorRect(r.x,r.y-t,r.width,r.height+2*t)
            else DoorRect(r.x-t,r.y,r.width+2*t,r.height)
        if (!inside(extended)) return null
        DoorLayoutMember(id,vertical,r,t)
    }
    val panels = panelRows.map { p ->
        val id = p.text("part")?.takeIf { it in components && used.add(it) } ?: return null
        val opening = rect(p,"opening") ?: return null
        val ix = p.number("insetX")?.takeIf { it >= 0 } ?: return null
        val iy = p.number("insetY")?.takeIf { it >= 0 } ?: return null
        if (!inside(DoorRect(opening.x-ix,opening.y-iy,opening.width+2*ix,opening.height+2*iy))) return null
        val kind = p.text("kind")?.takeIf { it in setOf("flat","raised","glass","panel") } ?: return null
        val grain = p.text("grain")?.toBooleanStrictOrNull() ?: return null
        DoorLayoutPanel(id,DoorPanel(kind,ix,iy,opening,p.text("material").orEmpty()),grain)
    }
    val all = members.map { it.rect } + panels.map { requireNotNull(it.panel.opening) }
    for (i in all.indices) for (j in i+1 until all.size) {
        val a = all[i]; val b = all[j]
        if (minOf(a.x+a.width,b.x+b.width)-maxOf(a.x,b.x) > 0.002 &&
            minOf(a.y+a.height,b.y+b.height)-maxOf(a.y,b.y) > 0.002) return null
    }
    // A complete partition of the door face; tolerate only published decimal rounding.
    if (abs(all.sumOf { it.width*it.height } - width*height) > 0.002*(width+height)) return null
    return DoorLayout(members,panels)
}
