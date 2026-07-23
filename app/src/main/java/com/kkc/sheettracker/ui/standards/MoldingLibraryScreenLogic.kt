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
        return library.moldings.filter { it.category == category && !it.hidden }
    }

    fun isCrownCategory(category: String?): Boolean {
        return category?.equals("Crown", ignoreCase = true) == true ||
            category?.equals("Crown Molding", ignoreCase = true) == true
    }

    fun defaultCategory(library: MoldingLibrary): String? {
        val crownCategory = library.categories.firstOrNull { isCrownCategory(it) }
        return crownCategory ?: library.categories.firstOrNull()
    }

    /** Crown grouping (Face Frame/Frameless/Unset sections) only applies while
     * browsing the Crown category with no active search -- search results
     * always stay a flat, ungrouped list. Shares [isCrownCategory] with
     * [defaultCategory] so the two "is this Crown" checks can't drift apart. */
    fun isCrownBrowse(selectedCategory: String?, query: String): Boolean {
        return query.isBlank() && isCrownCategory(selectedCategory)
    }

    fun searchMoldings(
        library: MoldingLibrary,
        selectedCategory: String?,
        query: String
    ): List<MoldingLibraryItem> {
        val visibleMoldings = library.moldings.filter { !it.hidden }
        val trimmedQuery = query.trim()
        if (trimmedQuery.isEmpty()) {
            return if (selectedCategory != null) {
                moldingsForCategory(library, selectedCategory)
            } else {
                visibleMoldings
            }
        }
        return visibleMoldings.filter { item ->
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
