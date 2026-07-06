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
    var i = 0
    return original.map { item ->
        if (boardSectionOf(item) == 0) reorderedActiveFolderNames[i++] else folderNameOf(item)
    }
}
