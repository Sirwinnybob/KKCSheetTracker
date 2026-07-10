package com.kkc.sheettracker.ui.supply

import com.kkc.sheettracker.data.models.SupplyCategory

sealed interface SupplyTabType {
    object Updates : SupplyTabType
    object NeedsAttention : SupplyTabType
    object ToOrder : SupplyTabType
    data class CategoryTab(val category: SupplyCategory) : SupplyTabType
}

data class SupplyTabItem(
    val id: String,
    val type: SupplyTabType,
    val name: String
)

fun buildSupplyTabsList(
    categories: List<SupplyCategory>,
    isAdminMode: Boolean,
    savedTabOrder: List<String>
): List<SupplyTabItem> {
    val availableTabs = mutableListOf<SupplyTabItem>()
    availableTabs.add(SupplyTabItem("updates", SupplyTabType.Updates, "Updates"))
    availableTabs.add(SupplyTabItem("needs_attention", SupplyTabType.NeedsAttention, "Needs Attention"))
    if (isAdminMode) {
        availableTabs.add(SupplyTabItem("to_order", SupplyTabType.ToOrder, "To Order"))
    }
    categories.forEach { category ->
        availableTabs.add(SupplyTabItem(category.id, SupplyTabType.CategoryTab(category), category.name))
    }

    if (savedTabOrder.isEmpty()) {
        return availableTabs
    }

    val sortedList = mutableListOf<SupplyTabItem>()
    // First, place tabs that are found in the saved order
    savedTabOrder.forEach { tabId ->
        val found = availableTabs.find { it.id == tabId }
        if (found != null) {
            sortedList.add(found)
            availableTabs.remove(found)
        }
    }
    // Then, append any remaining available tabs (e.g. newly added categories or admin tabs)
    sortedList.addAll(availableTabs)
    return sortedList
}
