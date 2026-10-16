package com.kkc.sheettracker.ui.doorpreview

import com.kkc.sheettracker.data.cvjob.CvJobDoorPreviews
import com.kkc.sheettracker.data.cvjob.DoorConstruction
import com.kkc.sheettracker.data.cvjob.DoorGeometry
import com.kkc.sheettracker.data.cvjob.DoorHingeSide
import com.kkc.sheettracker.data.cvjob.DoorHinges
import com.kkc.sheettracker.data.cvjob.DoorPart
import com.kkc.sheettracker.data.cvjob.DoorPoint
import com.kkc.sheettracker.data.cvjob.DoorPreviewData
import com.kkc.sheettracker.data.cvjob.parseCvJobDoorPreviews
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DoorAssemblyListTest {
    @Test
    fun v2PartCountsUseVisibleEditedQuantitiesAndExcludeHardware() {
        val json = requireNotNull(javaClass.classLoader?.getResource("cvjob/job_data_v2_visibility.json")).readText()
        val doors = doorAssemblyDoors(parseCvJobDoorPreviews(json))
        assertEquals(listOf("A"), doors.map { it.doorId })
        assertEquals(5, doors.single().partCount)
        val lines = groupDoorParts(doors.single().parts)

        assertEquals(listOf("panel", "panel", "hinge"), lines.map { it.part.role })
        assertEquals(listOf(2, 3, 3), lines.map { it.count })
        assertEquals(8, lines.sumOf { it.count })
    }

    private fun door(id: String, cabinet: String, label: String, hinges: DoorHinges = noHinges()) = DoorPreviewData(
        geometry = DoorGeometry(id, DoorConstruction.SLAB, 12.25, 30.0, 0.75, null, null, hinges, emptyList()),
        label = label,
        style = "Slab",
        cabinet = cabinet
    )

    private fun noHinges() = DoorHinges(DoorHingeSide.NONE, "N", 0, emptyList(), null, emptyList())

    private fun part(role: String, description: String = role, material: String = "", width: String = "1", length: String = "2") =
        DoorPart("id-$role-$description", role, description, material, width, length)

    @Test
    fun doorsAreInCabinetThenLabelOrderAndOnlyFromLoadedData() {
        val loaded = CvJobDoorPreviews.Loaded(
            listOf(door("5", "10", "1A"), door("4", "2", "2A"), door("3", "2", "1A"), door("2", "", "X"), door("1", "1", "1B"))
        )
        assertEquals(listOf("1", "3", "4", "5", "2"), doorAssemblyDoors(loaded).map { it.doorId })
        assertTrue(doorAssemblyDoors(null).isEmpty())
        assertTrue(doorAssemblyDoors(CvJobDoorPreviews.NoDoorGeometry).isEmpty())
    }

    @Test
    fun defaultCabinetSortUsesNumericOrderIncludingSuffixesAndDoorNames() {
        val loaded = CvJobDoorPreviews.Loaded(listOf(
            door("5", "10", "1A"), door("4", "2A", "1A"),
            door("3", "2", "10A-R"), door("2", "2", "2A-R"),
            door("1", "1", "1A-R"), door("6", "", "Spare")
        ))
        assertEquals(listOf("1", "2", "3", "4", "5", "6"), doorAssemblyDoors(loaded).map { it.doorId })
    }

    @Test
    fun doorNameSortUsesCabinetAsATieBreakerAndStillSupportsSearch() {
        val loaded = CvJobDoorPreviews.Loaded(listOf(
            door("4", "1", "10A-R"), door("3", "10", "2A-R"),
            door("2", "2", "2A-R"), door("1", "3", "1A-L")
        ))
        val sorted = doorAssemblyDoors(loaded, DoorAssemblySort.DOOR_NAME)
        assertEquals(listOf("1", "2", "3", "4"), sorted.map { it.doorId })
        assertEquals(listOf("2", "3"), filterDoorAssemblyDoors(sorted, "2a-r").map { it.doorId })
    }

    @Test
    fun sizeSortUsesWidthThenHeightWithCabinetOrderForEqualSizes() {
        fun sized(id: String, cabinet: String, width: Double, height: Double) =
            door(id, cabinet, "1A").let { it.copy(geometry = it.geometry.copy(width = width, height = height)) }
        val loaded = CvJobDoorPreviews.Loaded(listOf(
            sized("4", "10", 20.0, 30.0), sized("2", "2", 20.0, 30.0),
            sized("3", "3", 20.0, 40.0), sized("1", "1", 9.5, 50.0)
        ))
        assertEquals(listOf("3", "2", "4", "1"), doorAssemblyDoors(loaded, DoorAssemblySort.LARGEST_FIRST).map { it.doorId })
        assertEquals(listOf("1", "2", "4", "3"), doorAssemblyDoors(loaded, DoorAssemblySort.SMALLEST_FIRST).map { it.doorId })
        assertTrue(doorAssemblyDoors(null, DoorAssemblySort.LARGEST_FIRST).isEmpty())
    }

    @Test
    fun partsAreFrameFirstThenPanelThenHardwareWithIdenticalPartsMerged() {
        val parts = listOf(
            part("pull", "Door Pull"),
            part("hinge", "Hinge", "110 Degree Blum"),
            part("hinge", "Hinge", "110 Degree Blum"),
            part("bumper", "Door Bumpers"),
            part("bumper", "Door Bumpers"),
            part("leftStile", "Door Left Stile"),
            part("rightStile", "Door Right Stile"),
            part("topRail", "Door Top Rail"),
            part("bottomRail", "Door Bottom Rail"),
            part("panel", "Door Flat Panel 1A-L"),
            part("hingePlate", "Hinge Plate"),
            part("mystery", "Odd part")
        )
        val lines = groupDoorParts(parts)
        assertEquals(
            listOf("leftStile", "rightStile", "topRail", "bottomRail", "panel", "hinge", "hingePlate", "pull", "bumper", "mystery"),
            lines.map { it.part.role }
        )
        assertEquals(2, lines.first { it.part.role == "hinge" }.count)
        assertEquals(2, lines.first { it.part.role == "bumper" }.count)
        assertEquals(1, lines.first { it.part.role == "panel" }.count)
        // Same role but a different size stays a separate line.
        val twoSizes = groupDoorParts(listOf(part("panel", width = "10"), part("panel", width = "11")))
        assertEquals(2, twoSizes.size)
    }

    @Test
    fun partTextShowsCutSizesForWoodOnlyAndPlainRoleNames() {
        assertEquals("2.2813 x 31.5625", doorPartSizeText(DoorPart("1", "leftStile", "Door Left Stile", "3/4 White Oak", "2.2813", "31.5625")))
        assertEquals("", doorPartSizeText(DoorPart("2", "hinge", "Hinge", "110 Degree Blum", "1.4567", "1.8504")))
        assertEquals("", doorPartSizeText(DoorPart("3", "panel", "Panel", "", "", "27.9")))
        assertEquals("Left stile", doorRoleLabel("leftStile"))
        assertEquals("Hinge plate", doorRoleLabel("hingePlate"))
        assertEquals("Other", doorRoleLabel("something"))
        assertTrue(isDoorHardware("bumper"))
        assertTrue(!isDoorHardware("slab"))
    }

    @Test
    fun headerSubtitleSaysHowTheDoorIsHung() {
        val hinged = DoorHinges(DoorHingeSide.RIGHT, "R", 2, listOf(3.1496, 26.85), 0.8071, listOf(DoorPoint(11.4429, 3.1496), DoorPoint(11.4429, 26.85)))
        assertEquals("12.25 × 30 · Slab · Hinged right", doorHeaderSubtitle(door("1", "1", "1A", hinged)))
        // Hinge side without drawable cups (no points) reads as no hinges.
        assertEquals("12.25 × 30 · Slab · No hinges", doorHeaderSubtitle(door("1", "1", "1A", hinged.copy(points = emptyList()))))
        assertEquals("12.25 × 30 · Slab · No hinges", doorHeaderSubtitle(door("1", "1", "1A")))
    }

    @Test
    fun job688FixtureDoorsListWithTheirParts() {
        val json = requireNotNull(javaClass.classLoader?.getResource("cvjob/job_data_688_doors.json")).readText()
        val doors = doorAssemblyDoors(parseCvJobDoorPreviews(json))
        assertEquals(5, doors.size)
        val first = doors.first { it.doorId == "53723" }
        assertEquals("1A-L", first.label)
        assertEquals(5, first.partCount)
        val lines = groupDoorParts(first.parts)
        assertEquals("leftStile", lines.first().part.role)
        assertEquals(listOf(2, 2, 2), lines.filter { it.part.role in setOf("hinge", "hingePlate", "bumper") }.map { it.count })
        assertEquals("16.625 × 31.5 · 2 1/4\" Flat Panel · Hinged left", doorHeaderSubtitle(first))
    }
}
