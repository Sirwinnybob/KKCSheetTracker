package com.kkc.sheettracker.data.cvjob

import com.kkc.sheettracker.data.models.SpecialtyItem
import com.kkc.sheettracker.data.models.SpecialtyItemCategory

/** Resolves only explicit Hours Tracker cabinet links in the current parsed job export. */
fun resolveSpecialtyWings(item: SpecialtyItem, wings: CvJobWings): List<CvWingGeometry> {
    if (item.category != SpecialtyItemCategory.CUSTOM) return emptyList()
    val linkedCabinets = item.cabinetNumbers.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    if (linkedCabinets.isEmpty()) return emptyList()
    return (wings as? CvJobWings.Loaded)?.wings.orEmpty().filter { it.cabinet in linkedCabinets }
}

/** A decorative end can hand off only when its published door ID identifies one captured wing. */
fun resolvePaneledEndWing(door: DoorPreviewData, wings: CvJobWings): CvWingGeometry? {
    val loaded = wings as? CvJobWings.Loaded ?: return null
    return loaded.wings.filter { it.nodeForDoor(door.doorId) != null }.singleOrNull()
}
