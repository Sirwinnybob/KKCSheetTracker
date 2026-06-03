package com.kkc.sheettracker.ui.specialty

import com.kkc.sheettracker.data.SpecialtyProgressStore
import com.kkc.sheettracker.data.models.SpecialtyCompletionState
import com.kkc.sheettracker.data.models.SpecialtyItem
import com.kkc.sheettracker.data.models.SpecialtyItemCategory
import com.kkc.sheettracker.data.models.SpecialtyResolvedItem
import com.kkc.sheettracker.data.models.SpecialtyStation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpecialtyJobDetailScreenLogicTest {
    @Test
    fun checklistTogglesForItem_customMultiStation_createsOneStationLabeledTogglePerStation() {
        val resolved = SpecialtyResolvedItem(
            item = SpecialtyItem(
                id = "custom-1",
                name = "Custom Item",
                category = SpecialtyItemCategory.CUSTOM,
                stations = listOf(SpecialtyStation.CNC, SpecialtyStation.EDGE_BANDER, SpecialtyStation.ASSEMBLY)
            ),
            completionByKey = mapOf(
                SpecialtyStation.CNC.name to SpecialtyCompletionState(completed = true),
                SpecialtyStation.EDGE_BANDER.name to SpecialtyCompletionState(completed = false),
                SpecialtyStation.ASSEMBLY.name to SpecialtyCompletionState(completed = false)
            )
        )

        val toggles = checklistTogglesForItem(resolved, completionOverrides = emptyMap())

        assertEquals(3, toggles.size)
        assertEquals(listOf("CNC", "EDGE_BANDER", "ASSEMBLY"), toggles.map { it.completionKey })
        assertEquals(listOf("CNC", "EDGE BANDER", "ASSEMBLY"), toggles.map { it.label })
    }

    @Test
    fun checklistTogglesForItem_toOrder_createsSingleItemCompletionToggle() {
        val resolved = SpecialtyResolvedItem(
            item = SpecialtyItem(
                id = "order-1",
                name = "Ordered Part",
                category = SpecialtyItemCategory.TO_ORDER,
                stations = listOf(SpecialtyStation.CNC, SpecialtyStation.SAW)
            ),
            completionByKey = mapOf(
                SpecialtyProgressStore.ITEM_COMPLETION_KEY to SpecialtyCompletionState(completed = true)
            )
        )

        val toggles = checklistTogglesForItem(resolved, completionOverrides = emptyMap())

        assertEquals(1, toggles.size)
        assertEquals(SpecialtyProgressStore.ITEM_COMPLETION_KEY, toggles.first().completionKey)
        assertNull(toggles.first().label)
        assertTrue(toggles.first().checked)
    }

    @Test
    fun isChecklistItemComplete_customMultiStation_requiresAllStationsChecked() {
        val resolved = SpecialtyResolvedItem(
            item = SpecialtyItem(
                id = "custom-2",
                name = "Custom Item",
                category = SpecialtyItemCategory.CUSTOM,
                stations = listOf(SpecialtyStation.CNC, SpecialtyStation.SAW)
            ),
            completionByKey = mapOf(
                SpecialtyStation.CNC.name to SpecialtyCompletionState(completed = true),
                SpecialtyStation.SAW.name to SpecialtyCompletionState(completed = false)
            )
        )

        assertFalse(isChecklistItemComplete(resolved, completionOverrides = emptyMap()))

        val overrides = mapOf("custom-2::SAW" to true)
        assertTrue(isChecklistItemComplete(resolved, completionOverrides = overrides))
    }

    @Test
    fun inFlightUpdates_concurrentToggles_remainDisabledUntilEachWriteCompletes() {
        val inFlight = mutableMapOf<String, Boolean>()
        val cncControl = "custom-2::CNC"
        val sawControl = "custom-2::SAW"

        startInFlightUpdate(inFlight, cncControl)
        startInFlightUpdate(inFlight, sawControl)
        assertFalse(isToggleEnabled(cncControl, inFlight))
        assertFalse(isToggleEnabled(sawControl, inFlight))

        finishInFlightUpdate(inFlight, cncControl)
        assertTrue(isToggleEnabled(cncControl, inFlight))
        assertFalse(isToggleEnabled(sawControl, inFlight))

        finishInFlightUpdate(inFlight, sawControl)
        assertTrue(isToggleEnabled(cncControl, inFlight))
        assertTrue(isToggleEnabled(sawControl, inFlight))
    }
}
