package com.kkc.sheettracker.ui.standards

import com.kkc.sheettracker.data.models.MoldingLibrary
import com.kkc.sheettracker.data.models.MoldingLibraryItem

object MoldingLibraryScreenLogic {

    fun moldingsForCategory(library: MoldingLibrary, category: String): List<MoldingLibraryItem> {
        return library.moldings.filter { it.category == category }
    }

    fun defaultCategory(library: MoldingLibrary): String? {
        return library.categories.firstOrNull()
    }

    fun svgFileName(fileId: String, showMeasurements: Boolean): String {
        return if (showMeasurements) "${fileId}_dim.svg" else "$fileId.svg"
    }
}
