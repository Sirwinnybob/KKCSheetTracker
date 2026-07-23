package com.kkc.sheettracker.ui.standards

import com.kkc.sheettracker.data.models.MoldingLibrary
import com.kkc.sheettracker.data.models.MoldingLibraryItem

enum class FrameStyleGroup(val label: String) {
    FACE_FRAME("Face Frame"),
    FRAMELESS("Frameless"),
    UNSET("Unset")
}

object MoldingLibraryScreenLogic {

    fun moldingsForCategory(library: MoldingLibrary, category: String): List<MoldingLibraryItem> {
        return library.moldings.filter { it.category == category }
    }

    fun defaultCategory(library: MoldingLibrary): String? {
        val crownCategory = library.categories.firstOrNull { cat ->
            cat.equals("Crown", ignoreCase = true) || cat.equals("Crown Molding", ignoreCase = true)
        }
        return crownCategory ?: library.categories.firstOrNull()
    }

    fun searchMoldings(
        library: MoldingLibrary,
        selectedCategory: String?,
        query: String
    ): List<MoldingLibraryItem> {
        val trimmedQuery = query.trim()
        if (trimmedQuery.isEmpty()) {
            return if (selectedCategory != null) {
                moldingsForCategory(library, selectedCategory)
            } else {
                library.moldings
            }
        }
        return library.moldings.filter { item ->
            item.name.contains(trimmedQuery, ignoreCase = true) ||
            item.fileId.contains(trimmedQuery, ignoreCase = true) ||
            item.category.contains(trimmedQuery, ignoreCase = true)
        }
    }

    fun svgFileName(fileId: String, showMeasurements: Boolean): String {
        return if (showMeasurements) "${fileId}_dim.svg" else "$fileId.svg"
    }

    fun shouldUseDarkPreview(isDarkTheme: Boolean, useStandardSheets: Boolean): Boolean {
        return isDarkTheme && !useStandardSheets
    }

    fun crownFrameGroups(items: List<MoldingLibraryItem>): List<Pair<FrameStyleGroup, List<MoldingLibraryItem>>> {
        val byStyle = items.groupBy {
            when (it.frameStyle) {
                "face_frame" -> FrameStyleGroup.FACE_FRAME
                "frameless" -> FrameStyleGroup.FRAMELESS
                else -> FrameStyleGroup.UNSET
            }
        }
        return listOf(FrameStyleGroup.FACE_FRAME, FrameStyleGroup.FRAMELESS, FrameStyleGroup.UNSET)
            .mapNotNull { group -> byStyle[group]?.let { group to it } }
    }
}
