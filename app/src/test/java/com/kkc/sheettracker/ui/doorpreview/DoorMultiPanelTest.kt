package com.kkc.sheettracker.ui.doorpreview

import com.google.gson.JsonParser
import com.kkc.sheettracker.data.cvjob.*
import org.junit.Assert.*
import org.junit.Test

class DoorMultiPanelTest {
    private fun json() = JsonParser.parseString(requireNotNull(javaClass.classLoader?.getResource(
        "cvjob/job_data_302_cab11_layout.json")).readText()).asJsonObject
    private fun drawing(text: String): DoorDrawing {
        val loaded = parseCvJobDoorPreviews(text) as CvJobDoorPreviews.Loaded
        return buildDoorDrawing(loaded.doors.single().geometry)
    }
    private fun DoorDrawing.rects(role: DoorShapeRole) = shapes.filterIsInstance<DoorShape.Rect>()
        .filter { it.role == role }.map { it.rect }

    @Test fun romanoCab11DrawsFourOpeningsAndThreeInternalStiles() {
        val d = drawing(json().toString())
        assertEquals(4, d.rects(DoorShapeRole.PANEL).size)
        assertEquals(5, d.rects(DoorShapeRole.STILE).size)
        assertEquals(2, d.rects(DoorShapeRole.RAIL).size)
        assertEquals(4, d.rects(DoorShapeRole.HIDDEN_PANEL_EDGE).size)
        assertEquals(10, d.rects(DoorShapeRole.TENON).size)
        val panels = d.rects(DoorShapeRole.PANEL)
        for ((i, x) in listOf(4.5, 32.0045, 59.509, 87.0135).withIndex()) {
            assertEquals(x, panels[i].x, 0.00001)
            assertEquals(24.5045, panels[i].width, 0.00001)
            assertEquals(23.9555, panels[i].height, 0.00001)
        }
        assertTrue(d.notes.isEmpty())
        assertTrue(d.warnings.isEmpty())
        assertEquals(DoorShapeRole.OUTLINE, (d.shapes.last() as DoorShape.Rect).role)
        val loaded = parseCvJobDoorPreviews(json().toString()) as CvJobDoorPreviews.Loaded
        assertTrue(doorDetailLines(loaded.doors.single(), d).contains("4 panel openings"))
    }

    @Test fun malformedOrIncompleteLayoutsFallBackAsAWhole() {
        for (change in listOf("negative", "outside", "missing", "unknown", "empty", "overlap")) {
            val j = json()
            val layout = j.getAsJsonArray("doorGeometry")[0].asJsonObject.getAsJsonObject("layout")
            val member = layout.getAsJsonArray("members")[0].asJsonObject
            when (change) {
                "negative" -> member.getAsJsonObject("rect").addProperty("width", "-1")
                "outside" -> member.getAsJsonObject("rect").addProperty("x", "200")
                "missing" -> member.remove("rect")
                "unknown" -> member.addProperty("role", "unknown")
                "empty" -> layout.add("panels", com.google.gson.JsonArray())
                "overlap" -> layout.getAsJsonArray("panels")[1].asJsonObject.add("opening",
                    layout.getAsJsonArray("panels")[0].asJsonObject.get("opening").deepCopy())
            }
            val d = drawing(j.toString())
            assertEquals(change, 1, d.shapes.size)
            assertTrue(change, d.notes.isNotEmpty())
        }
    }

    @Test fun explicitUnequalStackedPanelsKeepTheirOwnGrainGlassAndHardware() {
        val j = json()
        val g = j.getAsJsonArray("doorGeometry")[0].asJsonObject
        g.addProperty("width", "20"); g.addProperty("height", "30")
        g.add("hinges", JsonParser.parseString("""{"side":"left","code":"L","count":2,
            "edgeOffset":"0.8","points":[{"x":"0.8","y":"5"},{"x":"0.8","y":"25"}]}"""))
        g.add("layout", JsonParser.parseString("""{
          "members":[
            {"part":"489256","role":"stile","tenon":"0","rect":{"x":"0","y":"0","width":"2","height":"30"}},
            {"part":"489257","role":"stile","tenon":"0","rect":{"x":"18","y":"0","width":"2","height":"30"}},
            {"part":"489258","role":"rail","tenon":"0.5","rect":{"x":"2","y":"28","width":"16","height":"2"}},
            {"part":"489259","role":"rail","tenon":"0.5","rect":{"x":"2","y":"0","width":"16","height":"2"}},
            {"part":"489260","role":"rail","tenon":"0.5","rect":{"x":"2","y":"12","width":"16","height":"3"}}
          ],
          "panels":[
            {"part":"489263","kind":"glass","grain":false,"insetX":"0.4","insetY":"0.4",
              "opening":{"x":"2","y":"2","width":"16","height":"10"}},
            {"part":"489264","kind":"flat","grain":true,"insetX":"0.4","insetY":"0.4",
              "opening":{"x":"2","y":"15","width":"16","height":"13"}}
          ]
        }"""))
        val d = drawing(j.toString())
        assertEquals(listOf(DoorRect(2.0,2.0,16.0,10.0)),d.rects(DoorShapeRole.GLASS))
        assertEquals(listOf(DoorRect(2.0,15.0,16.0,13.0)),d.rects(DoorShapeRole.PANEL))
        assertEquals(true,d.shapes.filterIsInstance<DoorShape.Rect>().single { it.role == DoorShapeRole.PANEL }.horizontalGrain)
        assertEquals(3,d.rects(DoorShapeRole.RAIL).size)
        assertEquals(6,d.rects(DoorShapeRole.TENON).size)
        assertEquals(2,d.shapes.filterIsInstance<DoorShape.HingeCup>().size)
        assertTrue(d.notes.isEmpty())
    }

    @Test fun absentLayoutKeepsOlderOtherDoorsAsOutlines() {
        val j = json()
        j.getAsJsonArray("doorGeometry")[0].asJsonObject.remove("layout")
        assertEquals(listOf(OUTLINE_ONLY_NOTE), drawing(j.toString()).notes)
    }
}
