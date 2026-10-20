package com.kkc.sheettracker.viewer3d

import com.kkc.sheettracker.data.cvjob.CvJobDoorPreviews
import com.kkc.sheettracker.data.cvjob.CvJobWings
import com.kkc.sheettracker.data.cvjob.parseCvJobDoorPreviews
import com.kkc.sheettracker.data.cvjob.parseCvJobWings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WingThumbnailProjectionTest {
    @Test
    fun projection_keepsEveryVisibleSolid_andUsesTheSameWorldExtent() {
        val json = requireNotNull(javaClass.classLoader?.getResource("cvjob/verified-wing-export.json")).readText()
        val wing = (parseCvJobWings(json) as CvJobWings.Loaded).wings.first { it.cabinet == "3" }
        val doors = ((parseCvJobDoorPreviews(json) as CvJobDoorPreviews.Loaded).allDoors).associateBy { it.doorId }

        val scene = buildWingScenePayload(wing, doors)
        val projection = projectWingThumbnail(scene)

        assertEquals(scene.parts.map { it.path }.toSet(), projection.faces.map { it.path }.toSet())
        assertEquals(scene.bounds.minX, projection.bounds.minX, 0.000001)
        assertEquals(scene.bounds.maxZ, projection.bounds.maxY, 0.000001)
        assertTrue(projection.faces.zipWithNext().all { (front, back) -> front.depth >= back.depth })
    }
}
