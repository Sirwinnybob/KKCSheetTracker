package com.kkc.sheettracker.viewer3d

import com.kkc.sheettracker.data.cvjob.CvJobWings
import com.kkc.sheettracker.data.cvjob.parseCvJobDoorPreviews
import com.kkc.sheettracker.data.cvjob.parseCvJobWings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WingScenePayloadTest {
    private val json = requireNotNull(javaClass.classLoader?.getResource("cvjob/verified-wing-export.json")).readText()
    private val wings = (parseCvJobWings(json) as CvJobWings.Loaded).wings
    private val doors = (parseCvJobDoorPreviews(json) as com.kkc.sheettracker.data.cvjob.CvJobDoorPreviews.Loaded)
        .allDoors.associateBy { it.doorId }

    @Test
    fun payload_usesParentWorldTransforms_andHidesSupportsFromAllVisibleParts() {
        val payload = buildWingScenePayload(wings.first { it.cabinet == "3" }, doors)

        assertEquals(5, payload.parts.size)
        assertTrue(payload.parts.none { it.code in setOf("TS", "DE", "CAB", "Case", "Face2", "Face4", "DOR_OPEN") })
        assertEquals("53738", payload.parts.single { it.code == "DOR" }.doorId)
        val doorFace = requireNotNull(payload.parts.single { it.code == "DOR" }.doorFace)
        assertEquals(4, doorFace.members.size)
        assertEquals(1, doorFace.panels.size)
        assertEquals(30.375, doorFace.panels.single().width, 0.001)
        assertTrue(payload.parts.filter { it.code != "DOR" }.all { it.material?.materialId?.isNotBlank() == true })
        assertEquals(35.25, payload.bounds.height, 0.001)
    }

    @Test
    fun payload_calculatesCapEngagements_withoutChangingBoardGeometry() {
        val payloads = wings.map { buildWingScenePayload(it, doors) }
        val engagements = payloads.flatMap { it.capEngagements }

        assertEquals(16, engagements.size)
        assertTrue(engagements.all { it.depth == 0.375 })
    }
}
