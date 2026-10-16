package com.kkc.sheettracker.ui.doorpreview

import com.kkc.sheettracker.data.cvjob.*

/** Attach the actual part material to each visible face, including multi-panel and angled doors. */
fun buildDoorDrawing(door: DoorPreviewData): DoorDrawing {
    val drawing = buildDoorDrawing(door.geometry)
    val regions = buildDoorPartRegions(door, drawing)
    val facePart = door.parts.singleOrNull { it.role == "slab" }
    val face = facePart?.let { DoorMaterialRef(it.materialId, it.material) }
        ?: DoorMaterialRef(name = door.geometry.panel?.material.orEmpty())
    val frame = DoorMaterialRef(name = door.geometry.frame?.material.orEmpty())
    val materials = drawing.shapes.mapIndexedNotNull { index, shape ->
        val role = when (shape) {
            is DoorShape.Rect -> shape.role
            is DoorShape.Poly -> shape.role
            is DoorShape.HingeCup -> return@mapIndexedNotNull null
        }
        if (role !in setOf(DoorShapeRole.STILE, DoorShapeRole.RAIL, DoorShapeRole.PANEL)) return@mapIndexedNotNull null
        val region = regions.singleOrNull {
            when (shape) {
                is DoorShape.Rect -> it.polygon == null && it.rect == shape.rect
                is DoorShape.Poly -> it.polygon == shape.points
            }
        }
        index to (region?.part?.let { DoorMaterialRef(it.materialId, it.material) }
            ?: if (role == DoorShapeRole.PANEL) face else frame)
    }.toMap()
    return drawing.copy(faceMaterial = face, surfaceMaterials = materials)
}
