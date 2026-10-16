package com.kkc.sheettracker.ui.doorpreview

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.kkc.sheettracker.data.cvjob.*
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class DoorMaterialDrawingTest {
    private fun doors(fixture: String): CvJobDoorPreviews.Loaded = parseCvJobDoorPreviews(
        requireNotNull(javaClass.classLoader?.getResource("cvjob/$fixture.json")).readText()
    ) as CvJobDoorPreviews.Loaded

    @Test fun publishedPartMaterialIdsReachSlabsAndEachFrameAndPanelFace() {
        var checkedFaces = 0
        for (fixture in listOf("job_data_688_doors", "job_data_302_cab11_layout")) for (door in doors(fixture).allDoors) {
            val drawing = buildDoorDrawing(door)
            val regions = buildDoorPartRegions(door, drawing)
            for ((index, shape) in drawing.shapes.withIndex()) {
                if (shape !is DoorShape.Rect || shape.role !in setOf(DoorShapeRole.STILE, DoorShapeRole.RAIL, DoorShapeRole.PANEL)) continue
                val part = regions.singleOrNull { it.rect == shape.rect }?.part ?: continue
                assertNotNull(part.materialId)
                assertEquals(part.materialId, drawing.surfaceMaterials[index]?.id)
                assertEquals(part.material, drawing.surfaceMaterials[index]?.name)
                checkedFaces++
            }
            if (door.geometry.construction == DoorConstruction.SLAB) {
                assertEquals(door.parts.single { it.role == "slab" }.materialId, drawing.faceMaterial.id)
            }
        }
        assertTrue("Exercise actual frame and multi-panel part joins", checkedFaces >= 15)
        val json = JsonParser.parseString(requireNotNull(javaClass.classLoader?.getResource(
            "cvjob/job_data_690_cabinet9_layout.json")).readText()).asJsonObject
        json.getAsJsonArray("parts").forEach { it.asJsonObject.addProperty("materialId", "1185") }
        val slab = (parseCvJobDoorPreviews(json.toString()) as CvJobDoorPreviews.Loaded).doors.first()
        assertEquals("1185", buildDoorDrawing(slab).faceMaterial.id)
    }

    @Test fun textureRepeatUsesInchesAndRotatesTheImageGrainForRailsAndSlopedRails() {
        val bounds = Rect(10f, 20f, 210f, 420f)
        val vertical = doorTextureMatrix(100, 200, bounds, Offset(0f, 1f), 10f, 20.0, 40.0, true)
        assertEquals(2f, vertical[0], 0.0001f)
        assertEquals(2f, vertical[4], 0.0001f)
        val horizontal = doorTextureMatrix(100, 200, bounds, Offset(1f, 0f), 10f, 20.0, 40.0, true)
        assertEquals(0f, horizontal[0], 0.0001f)
        assertEquals(2f, horizontal[1], 0.0001f)
        assertEquals(-2f, horizontal[3], 0.0001f)
        val along = Offset(0.8f, -0.6f)
        val slope = doorTextureMatrix(100, 200, bounds, along, 10f, 20.0, 40.0, true)
        assertEquals(along.x, slope[1] / 2, 0.0001f)
        assertEquals(along.y, slope[4] / 2, 0.0001f)
    }

    @Test fun untiledOrUnspecifiedRepeatFitsTheFaceInsteadOfInventingAPhysicalScale() {
        val bounds = Rect(10f, 20f, 210f, 420f)
        for (tile in listOf(true, false)) {
            val matrix = doorTextureMatrix(100, 200, bounds, Offset(0f, 1f), 10f,
                if (tile) null else 999.0, null, tile)
            assertEquals(2f, matrix[0], 0.0001f)
            assertEquals(2f, matrix[4], 0.0001f)
            assertEquals(10f, matrix[2], 0.0001f)
            assertEquals(20f, matrix[5], 0.0001f)
        }
    }
}
