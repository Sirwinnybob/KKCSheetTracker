package com.kkc.sheettracker.ui.supply

import com.kkc.sheettracker.data.models.SupplyCategory
import org.junit.Assert.assertEquals
import org.junit.Test

class SupplyTabSortingTest {
    @Test
    fun testBuildSupplyTabsListDefaultOrder() {
        val categories = listOf(
            SupplyCategory("cat1", "Category 1", 0),
            SupplyCategory("cat2", "Category 2", 1)
        )
        // Default order without saved preferences
        val result = buildSupplyTabsList(categories, isAdminMode = true, savedTabOrder = emptyList())
        val ids = result.map { it.id }
        assertEquals(listOf("updates", "needs_attention", "to_order", "cat1", "cat2"), ids)
    }

    @Test
    fun testBuildSupplyTabsListReordered() {
        val categories = listOf(
            SupplyCategory("cat1", "Category 1", 0),
            SupplyCategory("cat2", "Category 2", 1)
        )
        val savedOrder = listOf("cat2", "updates", "to_order", "cat1", "needs_attention")
        val result = buildSupplyTabsList(categories, isAdminMode = true, savedTabOrder = savedOrder)
        val ids = result.map { it.id }
        assertEquals(savedOrder, ids)
    }

    @Test
    fun testBuildSupplyTabsListWithNewAndDeletedCategories() {
        val categories = listOf(
            SupplyCategory("cat1", "Category 1", 0),
            SupplyCategory("cat3", "Category 3", 2) // cat3 is new
        )
        // cat2 was deleted, cat3 is not in savedOrder
        val savedOrder = listOf("cat2", "updates", "needs_attention", "cat1")
        val result = buildSupplyTabsList(categories, isAdminMode = false, savedTabOrder = savedOrder)
        val ids = result.map { it.id }
        // cat2 should be filtered out because it is not available (not in categories list), and cat3 should be appended at the end
        assertEquals(listOf("updates", "needs_attention", "cat1", "cat3"), ids)
    }
}
