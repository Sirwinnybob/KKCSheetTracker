package com.kkc.sheettracker.data.cvjob

import com.kkc.sheettracker.data.models.SpecialtyItem
import com.kkc.sheettracker.data.models.SpecialtyItemCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WingGeometryResolverTest {
    private fun wings(): CvJobWings.Loaded = parseCvJobWings(
        requireNotNull(javaClass.classLoader?.getResource("cvjob/verified-wing-export.json")).readText()
    ) as CvJobWings.Loaded

    @Test
    fun specialtyResolution_usesCustomCabinetLinksOnlyAndKeepsExportOrder() {
        val resolved = resolveSpecialtyWings(
            SpecialtyItem(category = SpecialtyItemCategory.CUSTOM, cabinetNumbers = listOf("4", "3")),
            wings()
        )

        assertEquals(listOf("3", "4"), resolved.map { it.cabinet })
        assertTrue(resolveSpecialtyWings(SpecialtyItem(category = SpecialtyItemCategory.TO_ORDER, cabinetNumbers = listOf("3")), wings()).isEmpty())
        assertTrue(resolveSpecialtyWings(SpecialtyItem(category = SpecialtyItemCategory.CUSTOM), wings()).isEmpty())
    }

    @Test
    fun paneledEndResolution_requiresOnePublishedDoorLink() {
        val json = requireNotNull(javaClass.classLoader?.getResource("cvjob/verified-wing-export.json")).readText()
        val door = (parseCvJobDoorPreviews(json) as CvJobDoorPreviews.Loaded).forDoor("53738")!!

        assertEquals("3", resolvePaneledEndWing(door, wings())?.cabinet)
        assertNull(resolvePaneledEndWing(door.copy(geometry = door.geometry.copy(doorId = "missing")), wings()))

        val duplicate = wings().wings.first { it.cabinet == "3" }.copy(cabinet = "99")
        assertNull(resolvePaneledEndWing(door, CvJobWings.Loaded(wings().wings + duplicate)))
    }
}
