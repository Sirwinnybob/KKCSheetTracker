package com.kkc.sheettracker.data

import com.kkc.sheettracker.data.models.SpecialtyStation
import java.util.Locale

const val SPECIALTY_VIEWER_SECTION_ID_SHEET_RIPS = "sheet-rips"
const val SPECIALTY_VIEWER_SECTION_ID_OTHER = "other"

data class SpecialtyViewerDefaults(
    val stationOrder: List<SpecialtyStation> = defaultSpecialtyViewerStationOrder(),
    val expandedSectionIds: Set<String> = defaultSpecialtyViewerExpandedSectionIds(),
)

data class SpecialtyViewerSectionOption(
    val id: String,
    val label: String,
)

fun specialtyViewerStations(): List<SpecialtyStation> =
    SpecialtyStation.entries.filterNot { it == SpecialtyStation.DELIVERY }

fun defaultSpecialtyViewerStationOrder(): List<SpecialtyStation> = specialtyViewerStations()

fun specialtyViewerSectionIdForStation(station: SpecialtyStation): String = station.name

fun specialtyViewerStationLabel(station: SpecialtyStation): String = when (station) {
    SpecialtyStation.SAW -> "Saw"
    SpecialtyStation.EDGE_BANDER -> "Edge Bander"
    SpecialtyStation.ASSEMBLY -> "Assembly"
    SpecialtyStation.CNC -> "CNC"
    SpecialtyStation.HARDWOODS -> "Hardwoods"
    SpecialtyStation.SPECIALTY -> "Specialty"
    SpecialtyStation.DELIVERY -> "Delivery"
}

fun specialtyViewerSectionOptions(
    stationOrder: List<SpecialtyStation> = defaultSpecialtyViewerStationOrder(),
): List<SpecialtyViewerSectionOption> = buildList {
    add(
        SpecialtyViewerSectionOption(
            id = SPECIALTY_VIEWER_SECTION_ID_SHEET_RIPS,
            label = "Sheet Rips",
        )
    )
    stationOrder.forEach { station ->
        add(
            SpecialtyViewerSectionOption(
                id = specialtyViewerSectionIdForStation(station),
                label = specialtyViewerStationLabel(station),
            )
        )
    }
    add(
        SpecialtyViewerSectionOption(
            id = SPECIALTY_VIEWER_SECTION_ID_OTHER,
            label = "Other",
        )
    )
}

fun defaultSpecialtyViewerExpandedSectionIds(): Set<String> =
    specialtyViewerSectionOptions().mapTo(linkedSetOf()) { it.id }

fun sanitizeSpecialtyViewerStationOrder(rawValue: String?): List<SpecialtyStation> {
    if (rawValue.isNullOrBlank()) return defaultSpecialtyViewerStationOrder()

    val stations = rawValue.split(',')
        .mapNotNull { stationName ->
            runCatching {
                SpecialtyStation.valueOf(stationName.trim().uppercase(Locale.US))
            }.getOrNull()
        }

    val defaultOrder = defaultSpecialtyViewerStationOrder()
    return if (stations.size == defaultOrder.size && stations.toSet().size == defaultOrder.size) {
        stations
    } else {
        defaultOrder
    }
}

fun sanitizeSpecialtyViewerExpandedSectionIds(rawValues: Set<String>?): Set<String> {
    if (rawValues == null) return defaultSpecialtyViewerExpandedSectionIds()
    if (rawValues.isEmpty()) return emptySet()

    val allowedIds = defaultSpecialtyViewerExpandedSectionIds()
    val validIds = rawValues.mapNotNullTo(linkedSetOf()) { rawId ->
        rawId.trim().takeIf { it.isNotEmpty() && it in allowedIds }
    }

    return if (validIds.isNotEmpty()) validIds else defaultSpecialtyViewerExpandedSectionIds()
}
