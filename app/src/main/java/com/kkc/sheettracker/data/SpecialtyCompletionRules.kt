package com.kkc.sheettracker.data

import com.kkc.sheettracker.data.models.SpecialtyItem
import com.kkc.sheettracker.data.models.SpecialtyItemCategory

internal fun requiresStationSplit(item: SpecialtyItem): Boolean {
    return item.category == SpecialtyItemCategory.CUSTOM && item.stations.size >= 2
}

internal fun completionKeysForItem(item: SpecialtyItem): List<String> {
    return if (requiresStationSplit(item)) {
        item.stations.map { station -> station.name }
    } else {
        listOf(SpecialtyProgressStore.ITEM_COMPLETION_KEY)
    }
}
