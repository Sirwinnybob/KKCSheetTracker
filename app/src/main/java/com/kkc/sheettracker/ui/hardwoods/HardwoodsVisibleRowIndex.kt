package com.kkc.sheettracker.ui.hardwoods

/** Shared identity for collapse state, expansion state, and row-jump sections. */
internal fun hardwoodsPartSectionStateKey(docTypeName: String, material: String) = "$docTypeName|$material"

/** Jump indices for the same visible sections/rows supplied to the lazy list. */
internal fun hardwoodsVisibleRowIndices(
    sections: List<Pair<String, List<String>>>,
    collapsedSectionKeys: Set<String>
): Map<String, Int> = buildMap {
    var index = 0
    for ((key, rows) in sections) {
        index++ // Each visible section contributes one header.
        if (key !in collapsedSectionKeys) {
            for (rowId in rows) put(rowId, index++)
        }
    }
}
