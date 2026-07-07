package com.kkc.sheettracker.ui.components

/**
 * Rebuilds the full production-order folder-name list after an admin drags jobs within the
 * Active section. [reorderedActiveFolderNames] is the new order for the boardSection==0 subset;
 * boardSection==1 (Pending Delivery) entries are left untouched in their original relative slots,
 * preserving whatever interleaving `production_order.json` already had.
 */
fun <T> mergeActiveReorder(
    original: List<T>,
    reorderedActiveFolderNames: List<String>,
    boardSectionOf: (T) -> Int,
    folderNameOf: (T) -> String
): List<String> {
    val activeCount = original.count { boardSectionOf(it) == 0 }
    if (reorderedActiveFolderNames.size != activeCount) {
        // Active membership has desynced from the captured drag order (e.g. a job moved in/out
        // of the Active section, or the board refreshed mid-drag). The positional index mapping
        // below is no longer valid, so reset to each item's original folder name instead of
        // risking an overrun or silently reassigning the wrong name.
        return original.map { folderNameOf(it) }
    }
    var i = 0
    return original.map { item ->
        if (boardSectionOf(item) == 0) {
            reorderedActiveFolderNames.getOrElse(i) { folderNameOf(item) }.also { i++ }
        } else {
            folderNameOf(item)
        }
    }
}
