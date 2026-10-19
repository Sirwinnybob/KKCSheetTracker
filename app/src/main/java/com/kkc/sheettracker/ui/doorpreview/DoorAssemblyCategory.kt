package com.kkc.sheettracker.ui.doorpreview

import com.kkc.sheettracker.data.cvjob.DoorPreviewData
import java.util.Locale

internal enum class DoorAssemblyCategory(val label: String, val itemsLabel: String) {
    DOORS_DRAWERS("Doors/Drawers", "doors or drawer fronts"),
    PANELED_ENDS_MISC("Paneled Ends and Misc.", "paneled ends or miscellaneous panels")
}

private val panelDoorTypes = setOf("BE", "WE", "TE")
private val miscellaneousPanelName = Regex("\\b(?:island\\s+back|wing|end\\s+panel)\\b", RegexOption.IGNORE_CASE)

/** Use published CV types and explicit panel names, never the absence of hinges. */
internal fun filterDoorAssemblyCategory(
    doors: List<DoorPreviewData>,
    category: DoorAssemblyCategory
): List<DoorPreviewData> = doors.filter { door ->
    val type = door.doorType.trim().uppercase(Locale.US)
    val isPanel = type in panelDoorTypes || (type != "DF" &&
        (miscellaneousPanelName.containsMatchIn(door.assemblyName) ||
            miscellaneousPanelName.containsMatchIn(door.style)))
    isPanel == (category == DoorAssemblyCategory.PANELED_ENDS_MISC)
}
