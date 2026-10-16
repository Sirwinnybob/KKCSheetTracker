package com.kkc.sheettracker.ui.doorpreview

import com.kkc.sheettracker.data.cvjob.DoorLayout
import com.kkc.sheettracker.data.cvjob.DoorRect

/** All coordinates are finished face rectangles; cut allowances never become visible geometry. */
internal fun buildMultiPanelShapes(layout: DoorLayout): List<DoorShape> {
    val fills = mutableListOf<DoorShape>()
    val members = mutableListOf<DoorShape>()
    val hidden = mutableListOf<DoorShape>()
    for (p in layout.panels) {
        val panel = p.panel
        val r = requireNotNull(panel.opening)
        fills += DoorShape.Rect(r, if (panel.isGlass) DoorShapeRole.GLASS else DoorShapeRole.PANEL, p.horizontalGrain)
        val ix = panel.insetX ?: 0.0
        val iy = panel.insetY ?: 0.0
        if (ix > 0 || iy > 0) hidden += DoorShape.Rect(
            DoorRect(r.x-ix,r.y-iy,r.width+2*ix,r.height+2*iy), DoorShapeRole.HIDDEN_PANEL_EDGE)
    }
    for (m in layout.members) {
        val r = m.rect
        members += DoorShape.Rect(r, if (m.vertical) DoorShapeRole.STILE else DoorShapeRole.RAIL)
        val t = m.tenon
        if (t > 0) {
            val ends = if (m.vertical) listOf(
                DoorRect(r.x,r.y-t,r.width,t), DoorRect(r.x,r.y+r.height,r.width,t)
            ) else listOf(
                DoorRect(r.x-t,r.y,t,r.height), DoorRect(r.x+r.width,r.y,t,r.height)
            )
            hidden += ends.map { DoorShape.Rect(it, DoorShapeRole.TENON) }
        }
    }
    return fills + members + hidden
}
