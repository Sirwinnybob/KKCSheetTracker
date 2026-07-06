package com.kkc.sheettracker.ui.supply

import com.kkc.sheettracker.data.ToOrderGroup
import com.kkc.sheettracker.data.models.SpecialtyItem
import com.kkc.sheettracker.data.models.SpecialtyItemCategory
import com.kkc.sheettracker.data.models.SpecialtyResolvedItem
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SupplyToOrderCollapseTest {
    @Test
    fun autoCollapsedToOrderJobIds_onlyIncludesFullyOrderedJobs() {
        val groups = listOf(
            group("job-open", item("one", complete = true), item("two", complete = false)),
            group("job-done", item("three", complete = true), item("four", complete = true))
        )

        assertEquals(setOf("job-done"), autoCollapsedToOrderJobIds(groups))
    }

    @Test
    fun toggleToOrderJobCollapse_flipsOnlySelectedJob() {
        val collapsed = setOf("job-done")

        val expanded = toggleToOrderJobCollapse(collapsed, "job-done")
        assertFalse("job-done" in expanded)

        val collapsedAgain = toggleToOrderJobCollapse(expanded, "job-open")
        assertTrue("job-open" in collapsedAgain)
        assertFalse("job-done" in collapsedAgain)
    }

    @Test
    fun toOrderItemCardText_placesCabinetBeforeNameAndQuantityBesideName() {
        val text = toOrderItemCardText(
            SpecialtyItem(
                id = "pulls",
                name = "Matte Black Pulls",
                cabinetNumbers = listOf("12", "14"),
                quantity = 6.0,
                dimensions = "5 in",
                material = "Metal",
                supplier = "Richelieu",
                model = "BP-123",
                orderDate = "2026-07-01"
            )
        )

        assertEquals("Cab #12, 14", text.cabinetLabel)
        assertEquals("Matte Black Pulls", text.itemName)
        assertEquals("Qty 6", text.quantityLabel)
        assertEquals("2026-07-01", text.orderDateLabel)
        assertEquals("5 in • Metal • Supplier: Richelieu • Model: BP-123", text.supportingText)
    }

    @Test
    fun toOrderStatusLabel_saysOrderedWhenChecked() {
        assertEquals("ORDERED", toOrderStatusLabel(isComplete = true))
        assertEquals("NOT ORDERED", toOrderStatusLabel(isComplete = false))
    }

    @Test
    fun defaultToOrderDate_usesMonthDayOnly() {
        assertEquals("07-01", defaultToOrderDate(LocalDate.of(2026, 7, 1)))
    }

    private fun group(folderName: String, vararg items: SpecialtyResolvedItem): ToOrderGroup {
        return ToOrderGroup(
            folderName = folderName,
            jobNumber = folderName.removePrefix("job-"),
            jobName = "Job $folderName",
            items = items.toList()
        )
    }

    private fun item(id: String, complete: Boolean): SpecialtyResolvedItem {
        return SpecialtyResolvedItem(
            item = SpecialtyItem(
                id = id,
                name = "Item $id",
                category = SpecialtyItemCategory.TO_ORDER
            ),
            isComplete = complete
        )
    }
}
