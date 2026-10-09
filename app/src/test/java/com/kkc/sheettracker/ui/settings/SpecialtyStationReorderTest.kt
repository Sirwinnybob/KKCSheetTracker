package com.kkc.sheettracker.ui.settings

import com.kkc.sheettracker.data.models.SpecialtyStation
import com.kkc.sheettracker.testutil.SourceFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpecialtyStationReorderTest {

    private val order = listOf(SpecialtyStation.CNC, SpecialtyStation.SAW, SpecialtyStation.ASSEMBLY, SpecialtyStation.DELIVERY)

    @Test
    fun stationKeyRoundTrips() {
        SpecialtyStation.entries.forEach { assertEquals(it, stationFromKey(stationKey(it))) }
    }

    @Test
    fun nonStationKeysAreIgnored() {
        assertNull(stationFromKey("section-cnc"))
        assertNull(stationFromKey("station-"))
        assertNull(stationFromKey("station-NOT_A_STATION"))
        assertNull(stationFromKey(42))
    }

    @Test
    fun movingDownPlacesItemAtTargetSlot() {
        assertEquals(
            listOf(SpecialtyStation.SAW, SpecialtyStation.ASSEMBLY, SpecialtyStation.CNC, SpecialtyStation.DELIVERY),
            moveStation(order, SpecialtyStation.CNC, SpecialtyStation.ASSEMBLY)
        )
    }

    @Test
    fun movingUpPlacesItemAtTargetSlot() {
        assertEquals(
            listOf(SpecialtyStation.DELIVERY, SpecialtyStation.CNC, SpecialtyStation.SAW, SpecialtyStation.ASSEMBLY),
            moveStation(order, SpecialtyStation.DELIVERY, SpecialtyStation.CNC)
        )
    }

    @Test
    fun unknownOrSameStationLeavesOrderUnchanged() {
        assertEquals(order, moveStation(order, SpecialtyStation.CNC, SpecialtyStation.CNC))
        assertEquals(order, moveStation(order, SpecialtyStation.HARDWOODS, SpecialtyStation.CNC))
    }

    @Test
    fun screenUsesDragAndDropInsteadOfArrows() {
        val screen = SourceFiles.mainSource("com/kkc/sheettracker/ui/settings/SpecialtyViewerDefaultsScreen.kt").readText()
        assertTrue(screen.contains("rememberReorderableLazyListState("))
        assertTrue("saves when the drag ends", screen.contains("onDragStopped"))
        assertFalse(screen.contains("ArrowUpward"))
        assertFalse(screen.contains("ArrowDownward"))
        assertFalse("one drag handle per row", screen.contains("longPressDraggableHandle"))
        assertTrue("TalkBack can still reorder", screen.contains("CustomAccessibilityAction("))
    }
}
