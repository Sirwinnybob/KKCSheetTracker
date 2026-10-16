package com.kkc.sheettracker.ui.doorpreview

import com.kkc.sheettracker.data.cvjob.CabinetFront
import com.kkc.sheettracker.data.cvjob.DoorPoint
import com.kkc.sheettracker.data.cvjob.DoorPreviewData

/** Place the existing door inspection regions in the same CV face coordinates as its front. */
internal fun cabinetDoorPartRegions(front: CabinetFront, door: DoorPreviewData, drawing: DoorDrawing): List<DoorPartRegion> =
    buildDoorPartRegions(door, drawing).map { region ->
        region.copy(
            rect = region.rect.copy(x = region.rect.x + front.rect.x, y = region.rect.y + front.rect.y),
            polygon = region.polygon?.map { DoorPoint(it.x + front.rect.x, it.y + front.rect.y) }
        )
    }
