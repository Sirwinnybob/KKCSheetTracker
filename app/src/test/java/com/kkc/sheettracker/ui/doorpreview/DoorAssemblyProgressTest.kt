package com.kkc.sheettracker.ui.doorpreview

import com.kkc.sheettracker.data.cvjob.CvJobDoorPreviews
import com.kkc.sheettracker.data.cvjob.DOOR_ASSEMBLY_PROGRESS_DOC_TYPE
import com.kkc.sheettracker.data.cvjob.DoorConstruction
import com.kkc.sheettracker.data.cvjob.DoorGeometry
import com.kkc.sheettracker.data.cvjob.DoorHingeSide
import com.kkc.sheettracker.data.cvjob.DoorHinges
import com.kkc.sheettracker.data.cvjob.DoorPreviewData
import com.kkc.sheettracker.data.models.HardwoodRowProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DoorAssemblyProgressTest {
    private fun door(id: String, cabinet: String, label: String, style: String = "Slab") = DoorPreviewData(
        geometry = DoorGeometry(
            id, DoorConstruction.SLAB, 12.25, 30.0, 0.75, null, null,
            DoorHinges(DoorHingeSide.NONE, "N", 0, emptyList(), null, emptyList()), emptyList()
        ),
        label = label,
        style = style,
        cabinet = cabinet
    )

    private val doors = listOf(
        door("1", "1", "1A", "2 1/4\" Flat Panel"),
        door("2", "12", "2B", "Slab"),
        door("3", "2", "1A", "Shaker"),
        door("4", "", "Spare", "Slab")
    )

    @Test
    fun blankQueryKeepsEveryDoorInOrder() {
        assertEquals(doors, filterDoorAssemblyDoors(doors, ""))
        assertEquals(doors, filterDoorAssemblyDoors(doors, "   "))
    }

    @Test
    fun cabinetQueryMatchesNumberPrefix() {
        assertEquals(listOf("2"), filterDoorAssemblyDoors(doors, "12").map { it.doorId })
        assertEquals(listOf("2"), filterDoorAssemblyDoors(doors, " 12 ").map { it.doorId })
        assertTrue(filterDoorAssemblyDoors(doors, "99").isEmpty())
    }

    @Test
    fun queryAlsoMatchesLabelAndStyleCaseInsensitively() {
        assertEquals(listOf("4"), filterDoorAssemblyDoors(doors, "spare").map { it.doorId })
        assertEquals(listOf("3"), filterDoorAssemblyDoors(doors, "SHAKER").map { it.doorId })
        assertEquals(listOf("1", "3"), filterDoorAssemblyDoors(doors, "1a").map { it.doorId })
    }

    @Test
    fun doneIsOneCheckPerDoorStoredUnderTheDoorAssemblyDocType() {
        val progress = mapOf(
            (DOOR_ASSEMBLY_PROGRESS_DOC_TYPE to "1") to HardwoodRowProgress(doneCount = 1),
            (DOOR_ASSEMBLY_PROGRESS_DOC_TYPE to "2") to HardwoodRowProgress(doneCount = 0),
            ("DOOR_CUT_LIST" to "3") to HardwoodRowProgress(doneCount = 1)
        )
        assertTrue(isDoorAssemblyDone(progress, "1"))
        assertFalse(isDoorAssemblyDone(progress, "2"))
        assertFalse("other doc types must not leak in", isDoorAssemblyDone(progress, "3"))
        assertFalse(isDoorAssemblyDone(emptyMap(), "1"))
    }

    @Test
    fun countsOnlyDoorsThatStillExist() {
        val progress = mapOf(
            (DOOR_ASSEMBLY_PROGRESS_DOC_TYPE to "1") to HardwoodRowProgress(doneCount = 1),
            (DOOR_ASSEMBLY_PROGRESS_DOC_TYPE to "gone") to HardwoodRowProgress(doneCount = 1)
        )
        assertEquals(1 to 4, doorAssemblyDoneCounts(doors, progress))
        assertEquals(0 to 0, doorAssemblyDoneCounts(emptyList(), progress))
    }

    @Test
    fun loadedDoorsFeedCountsThroughTheSameOrdering() {
        val progress = mapOf((DOOR_ASSEMBLY_PROGRESS_DOC_TYPE to "2") to HardwoodRowProgress(doneCount = 1))
        val ordered = doorAssemblyDoors(CvJobDoorPreviews.Loaded(doors))
        assertEquals(1 to 4, doorAssemblyDoneCounts(ordered, progress))
    }

    @Test
    fun skippedDoorsDoNotContributeToDoneCountsOrTheTarget() {
        val progress = mapOf(
            (DOOR_ASSEMBLY_PROGRESS_DOC_TYPE to "1") to HardwoodRowProgress(doneCount = 1),
            (DOOR_ASSEMBLY_PROGRESS_DOC_TYPE to "2") to HardwoodRowProgress(doneCount = 1, skipped = true),
            (DOOR_ASSEMBLY_PROGRESS_DOC_TYPE to "3") to HardwoodRowProgress(skipped = true)
        )
        assertEquals(1 to 2, doorAssemblyDoneCounts(doors, progress))
        assertFalse(isDoorAssemblyDone(progress, "2"))
    }
}
