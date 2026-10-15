package com.kkc.sheettracker.data.cvjob

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CvJobDoorGeometryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun fixture(): String =
        requireNotNull(javaClass.classLoader?.getResource("cvjob/job_data_688_doors.json")) { "fixture missing" }.readText()

    private fun loaded(json: String): CvJobDoorPreviews.Loaded {
        val result = parseCvJobDoorPreviews(json)
        assertTrue("expected Loaded, got $result", result is CvJobDoorPreviews.Loaded)
        return result as CvJobDoorPreviews.Loaded
    }

    @Test
    fun parsesJob688DoorsJoinedWithLabelAndStyle() {
        val previews = loaded(fixture())
        assertEquals(listOf("53723", "53727", "53732", "53751", "53753"), previews.doors.map { it.doorId }.sorted())

        val door = requireNotNull(previews.forDoor("53723"))
        assertEquals("1A-L", door.label)
        assertEquals("2 1/4\" Flat Panel", door.style)
        val g = door.geometry
        assertEquals(DoorConstruction.FIVE_PIECE, g.construction)
        assertEquals(16.625, g.width, 0.0)
        assertEquals(31.5, g.height, 0.0)
        assertEquals(0.75, g.thickness!!, 0.0)
        assertEquals(DoorFrame(2.25, 2.25, 2.25, 2.25, 0.5, "3/4 White Oak"), g.frame)
        val panel = g.panel!!
        assertEquals("flat", panel.kind)
        assertEquals(DoorRect(2.25, 2.25, 12.125, 27.0), panel.opening)
        assertEquals(0.468, panel.insetX!!, 0.0)
        assertEquals(DoorHingeSide.LEFT, g.hinges.side)
        assertEquals(listOf(3.1496, 28.3504), g.hinges.positions)
        assertEquals(0.8071, g.hinges.edgeOffset!!, 0.0)
        assertEquals(listOf(DoorPoint(0.8071, 3.1496), DoorPoint(0.8071, 28.3504)), g.hinges.points)
        assertTrue(g.checks.isEmpty())

        assertTrue(previews.forDoor("53751")!!.geometry.panel!!.isGlass)
        val top = previews.forDoor("53753")!!.geometry
        assertEquals(DoorHingeSide.TOP, top.hinges.side)
        assertEquals(listOf(DoorPoint(3.1496, 17.1929), DoorPoint(28.2254, 17.1929)), top.hinges.points)
        assertEquals(DoorHingeSide.OTHER, previews.forDoor("53727")!!.geometry.hinges.side)
        val slab = previews.forDoor("53732")!!.geometry
        assertEquals(DoorConstruction.SLAB, slab.construction)
        assertNull(slab.frame)
        assertNull(slab.panel)
        assertEquals(DoorHingeSide.NONE, slab.hinges.side)
        assertNull(previews.forDoor("missing"))

        // Cabinet and room from doors[]; every component joined with its parts[] row.
        assertEquals("1", door.cabinet)
        assertEquals("1", door.room)
        assertEquals(12, door.parts.size)
        val stile = door.parts.first { it.role == "leftStile" }
        assertEquals(DoorPart("488742", "leftStile", "Door Left Stile", "3/4 White Oak", "2.2813", "31.5625"), stile)
        assertEquals(2, door.parts.count { it.role == "hinge" })
        assertEquals("110 Degree Blum 11/16\"", door.parts.first { it.role == "hinge" }.material)
    }

    @Test
    fun hiddenDoorsAreSkippedEvenWhenTheyHaveGeometry() {
        val previews = loaded("""
            {"schemaVersion":2,
             "doors":[
               {"id":"visible","hidden":false,"hiddenBy":["filter"]},
               {"id":"hidden","hidden":true}
             ],
             "doorGeometry":[
               {"doorId":"visible","width":"10","height":"20"},
               {"doorId":"hidden","width":"10","height":"20"}
             ]}
        """.trimIndent())

        assertEquals(listOf("visible"), previews.doors.map { it.doorId })
        assertNull(previews.forDoor("hidden"))
    }

    @Test
    fun hiddenPartsAreSkippedFromDoorComponents() {
        val previews = loaded("""
            {"schemaVersion":2,
             "doors":[{"id":"A","hidden":false}],
             "parts":[
               {"id":"visible","hidden":false,"description":"Visible panel","material":"Oak","width":"8","length":"18"},
               {"id":"hidden","hidden":true,"description":"Hidden hinge"}
             ],
             "doorGeometry":[
               {"doorId":"A","width":"10","height":"20","components":[
                 {"part":"visible","role":"panel"},
                 {"part":"hidden","role":"hinge","description":"Hidden component"},
                 {"part":"missing","role":"hinge"}
               ]}
             ]}
        """.trimIndent())

        assertEquals(listOf("A"), previews.doors.map { it.doorId })
        assertEquals(
            listOf(
                DoorPart("visible", "panel", "Visible panel", "Oak", "8", "18"),
                DoorPart("missing", "hinge", "", "", "", "")
            ),
            previews.doors.single().parts
        )
    }

    @Test
    fun v1DoorsAndPartsWithoutHiddenFieldsRemainVisible() {
        val previews = loaded("""
            {"schemaVersion":1,
             "doors":[{"id":"A","label":"1A"}],
             "parts":[{"id":"panel","description":"Door panel","material":"Oak","width":"8","length":"18"}],
             "doorGeometry":[
               {"doorId":"A","width":"10","height":"20","components":[{"part":"panel","role":"panel"}]}
             ]}
        """.trimIndent())

        assertEquals(listOf("A"), previews.doors.map { it.doorId })
        val door = previews.doors.single()
        assertEquals("1A", door.label)
        assertEquals(listOf(DoorPart("panel", "panel", "Door panel", "Oak", "8", "18")), door.parts)
    }

    @Test
    fun olderJobDataWithoutDoorGeometryIsReportedNotFailed() {
        val json = """{"schemaVersion":1,"doors":[{"id":"1","label":"A"}],"parts":[]}"""
        assertEquals(CvJobDoorPreviews.NoDoorGeometry, parseCvJobDoorPreviews(json))
        // A null field is the same as an absent one.
        assertEquals(CvJobDoorPreviews.NoDoorGeometry, parseCvJobDoorPreviews("""{"doorGeometry":null}"""))
    }

    @Test
    fun unreadableJsonIsReportedNotThrown() {
        assertTrue(parseCvJobDoorPreviews("{not json") is CvJobDoorPreviews.Unreadable)
        assertTrue(parseCvJobDoorPreviews("[1,2]") is CvJobDoorPreviews.Unreadable)
        assertTrue(parseCvJobDoorPreviews("") is CvJobDoorPreviews.Unreadable)
    }

    @Test
    fun entriesWithoutIdOrSizeAreSkippedAndBadFieldsTolerated() {
        val json = """
            {"doors":[{"id":"7","label":"7A","style":"Shaker"}],
             "doorGeometry":[
               {"doorId":"","width":"10","height":"20"},
               {"doorId":"5","width":"0","height":"20"},
               {"doorId":"6","width":"abc","height":"20"},
               null, 3,
               {"doorId":"7","construction":"mystery","width":"10","height":"20",
                "frame":{"leftStile":"2"},
                "panel":{"kind":"flat","opening":{"x":"1","y":"1","width":"-1","height":"5"}},
                "hinges":{"side":"left","count":"x","positions":["9","bad","3"],"points":[{"x":"1"}]},
                "pulls":[{"x":"1"},{"x":"2","y":"3"}],
                "components":[{"part":"p1","role":"panel","description":"Door Flat Panel 7A"},{"role":"hinge"},{"part":"p9","role":"hinge"}],
                "checks":["", "Frame does not add up", 4]}
             ]}
        """.trimIndent()
        val previews = loaded(json)
        assertEquals(listOf("7"), previews.doors.map { it.doorId })
        val door = previews.doors.single()
        assertEquals("7A", door.label)
        assertEquals("Shaker", door.style)
        val g = door.geometry
        assertEquals(DoorConstruction.OTHER, g.construction)
        assertNull("frame with missing members", g.frame)
        assertNull("non-positive opening", g.panel!!.opening)
        assertEquals(0, g.hinges.count)
        assertEquals(listOf(3.0, 9.0), g.hinges.positions)
        assertTrue(g.hinges.points.isEmpty())
        assertNull(g.hinges.edgeOffset)
        assertEquals(listOf("Frame does not add up", "4"), g.checks)
        // A component without a part id is dropped; one whose part is missing keeps blank sizes.
        assertEquals(
            listOf(
                DoorPart("p1", "panel", "Door Flat Panel 7A", "", "", ""),
                DoorPart("p9", "hinge", "", "", "", "")
            ),
            door.parts
        )
    }

    @Test
    fun loaderReadsOnlyTheJobDataFileAndNeverWrites() {
        val base = tmp.root
        assertEquals(CvJobDoorPreviews.NoJobData, loadCvJobDoorPreviews(base.absolutePath, "688 - TEST"))
        assertEquals(CvJobDoorPreviews.NoJobData, loadCvJobDoorPreviews("", "688 - TEST"))

        val dir = File(base, "688 - TEST/.metadata/cv_job").apply { mkdirs() }
        // Temp and conflict copies are not the file; only job_data.json counts.
        File(dir, ".job_data-1234.tmp").writeText(fixture())
        File(dir, "job_data.sync-conflict-20261015-120000-ABC.json").writeText(fixture())
        assertEquals(CvJobDoorPreviews.NoJobData, loadCvJobDoorPreviews(base.absolutePath, "688 - TEST"))

        File(dir, "job_data.json").writeText(fixture())
        val before = dir.listFiles()!!.associate { it.name to it.lastModified() }
        val result = loadCvJobDoorPreviews(base.absolutePath, "688 - TEST")
        assertTrue(result is CvJobDoorPreviews.Loaded)
        assertEquals(5, (result as CvJobDoorPreviews.Loaded).doors.size)
        assertEquals(before, dir.listFiles()!!.associate { it.name to it.lastModified() })
        assertEquals(cvJobDataFile(base.absolutePath, "688 - TEST"), File(dir, "job_data.json"))
    }
}
