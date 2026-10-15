package com.kkc.sheettracker.ui.doorpreview

import com.kkc.sheettracker.data.cvjob.DoorConstruction
import com.kkc.sheettracker.data.cvjob.DoorFrame
import com.kkc.sheettracker.data.cvjob.DoorGeometry
import com.kkc.sheettracker.data.cvjob.DoorHingeSide
import com.kkc.sheettracker.data.cvjob.DoorHinges
import com.kkc.sheettracker.data.cvjob.DoorPanel
import com.kkc.sheettracker.data.cvjob.DoorPoint
import com.kkc.sheettracker.data.cvjob.DoorRect
import com.kkc.sheettracker.data.cvjob.CvJobDoorPreviews
import com.kkc.sheettracker.data.cvjob.parseCvJobDoorPreviews
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DoorPreviewLayoutTest {
    private val eps = 1e-9

    @Test
    fun publishedHorizontalGrainAppliesToPanelsAndSlabs() {
        for (construction in listOf("fivePiece", "slab")) {
            val previews = parseCvJobDoorPreviews("""
                {"schemaVersion":2,
                 "doors":[{"id":"A","grain":true}],
                 "doorGeometry":[{"doorId":"A","construction":"$construction","width":"10","height":"20",
                   "frame":{"leftStile":"2","rightStile":"2","topRail":"2","bottomRail":"2"},
                   "panel":{"kind":"flat","opening":{"x":"2","y":"2","width":"6","height":"16"}}
                 }]}
            """.trimIndent()) as CvJobDoorPreviews.Loaded
            val drawing = buildDoorDrawing(previews.doors.single().geometry)

            assertTrue(drawing.showFaceGrain)
            assertEquals("$construction should draw horizontal face grain", false, drawing.panelGrainVertical)
            if (construction == "fivePiece") {
                assertEquals(2, drawing.rects(DoorShapeRole.STILE).size)
                assertEquals(2, drawing.rects(DoorShapeRole.RAIL).size)
                assertEquals(1, drawing.rects(DoorShapeRole.PANEL).size)
            }
        }
    }

    @Test
    fun falseGrainFlagDrawsVerticalPanelAndSlabFaceGrain() {
        for (construction in listOf("fivePiece", "slab")) {
            val previews = parseCvJobDoorPreviews("""
                {"schemaVersion":2,
                 "doors":[{"id":"A","grain":false}],
                 "doorGeometry":[{"doorId":"A","construction":"$construction","width":"10","height":"20"}]}
            """.trimIndent()) as CvJobDoorPreviews.Loaded

            val drawing = buildDoorDrawing(previews.doors.single().geometry)
            assertTrue("$construction should draw vertical grain", drawing.showFaceGrain)
            assertTrue(drawing.panelGrainVertical)
        }
    }

    @Test
    fun componentGrainOverridesConflictingDoorFlag() {
        for (construction in listOf("fivePiece", "slab")) {
            for (horizontal in listOf(false, true)) {
                val role = if (construction == "slab") "slab" else "panel"
                for (source in listOf("component", "part", "panel")) {
                    if (source == "panel" && construction == "slab") continue
                    val componentFlag = if (source == "component") "\"grain\":$horizontal," else "\"grain\":null,"
                    val partFlag = if (source == "part") "\"grain\":$horizontal," else ""
                    val panel = if (source == "panel") ",\"panel\":{\"kind\":\"flat\",\"grain\":$horizontal}" else ""
                    val previews = parseCvJobDoorPreviews("""
                        {"schemaVersion":2,
                         "doors":[{"id":"A","grain":${!horizontal}}],
                         "parts":[{$partFlag"id":"p"}],
                         "doorGeometry":[{"doorId":"A","construction":"$construction","width":"10","height":"20"$panel,
                           "components":[{$componentFlag"part":"p","role":"$role"}]}]}
                    """.trimIndent()) as CvJobDoorPreviews.Loaded
                    val drawing = buildDoorDrawing(previews.doors.single().geometry)
                    assertTrue(drawing.showFaceGrain)
                    assertEquals("$construction/$source", !horizontal, drawing.panelGrainVertical)
                }
            }
        }
    }

    @Test
    fun missingGrainFlagKeepsLegacyVerticalPanelAndSlabGrain() {
        for (construction in listOf("fivePiece", "slab")) {
            val previews = parseCvJobDoorPreviews("""
                {"schemaVersion":1,
                 "doors":[{"id":"A"}],
                 "doorGeometry":[{"doorId":"A","construction":"$construction","width":"10","height":"20"}]}
            """.trimIndent()) as CvJobDoorPreviews.Loaded

            val drawing = buildDoorDrawing(previews.doors.single().geometry)
            assertTrue(drawing.showFaceGrain)
            assertTrue(drawing.panelGrainVertical)
        }
    }

    private fun hinges(
        side: DoorHingeSide,
        positions: List<Double> = emptyList(),
        edgeOffset: Double? = 0.8071,
        points: List<DoorPoint> = emptyList(),
        code: String = ""
    ) = DoorHinges(side, code, positions.size, positions, edgeOffset, points)

    /** Job 688 door 53723 (1A-L): 16 5/8 x 31 1/2, 2 1/4 members, 1/2 tenons, 0.468 insets. */
    private fun fivePiece(
        panelKind: String = "flat",
        hinges: DoorHinges = hinges(
            DoorHingeSide.LEFT, listOf(3.1496, 28.3504),
            points = listOf(DoorPoint(0.8071, 3.1496), DoorPoint(0.8071, 28.3504))
        ),
        opening: DoorRect? = DoorRect(2.25, 2.25, 12.125, 27.0),
        checks: List<String> = emptyList()
    ) = DoorGeometry(
        doorId = "53723",
        construction = DoorConstruction.FIVE_PIECE,
        width = 16.625,
        height = 31.5,
        thickness = 0.75,
        frame = DoorFrame(2.25, 2.25, 2.25, 2.25, 0.5, "3/4 Solid White Oak"),
        panel = DoorPanel(panelKind, 0.468, 0.468, opening, "1/4 2s White Oak"),
        hinges = hinges,
        checks = checks
    )

    private fun slab(hinges: DoorHinges) = DoorGeometry(
        doorId = "s1", construction = DoorConstruction.SLAB, width = 12.25, height = 30.0, thickness = 0.75,
        frame = null, panel = null, hinges = hinges, checks = emptyList()
    )

    private fun DoorDrawing.rects(role: DoorShapeRole) =
        shapes.filterIsInstance<DoorShape.Rect>().filter { it.role == role }.map { it.rect }

    private fun DoorDrawing.cups() = shapes.filterIsInstance<DoorShape.HingeCup>()

    private fun assertPoint(expected: DoorPoint, actual: DoorPoint) {
        assertEquals("x", expected.x, actual.x, 1e-6)
        assertEquals("y", expected.y, actual.y, 1e-6)
    }

    @Test
    fun fivePieceMapsMembersOpeningHiddenPanelEdgeAndTenons() {
        val d = buildDoorDrawing(fivePiece())
        assertEquals(16.625, d.width, eps)
        assertEquals(31.5, d.height, eps)
        assertEquals(listOf(DoorRect(0.0, 0.0, 16.625, 31.5)), d.rects(DoorShapeRole.OUTLINE))
        assertEquals(
            listOf(DoorRect(0.0, 0.0, 2.25, 31.5), DoorRect(14.375, 0.0, 2.25, 31.5)),
            d.rects(DoorShapeRole.STILE)
        )
        assertEquals(
            listOf(DoorRect(2.25, 29.25, 12.125, 2.25), DoorRect(2.25, 0.0, 12.125, 2.25)),
            d.rects(DoorShapeRole.RAIL)
        )
        assertEquals(listOf(DoorRect(2.25, 2.25, 12.125, 27.0)), d.rects(DoorShapeRole.PANEL))
        assertTrue(d.rects(DoorShapeRole.GLASS).isEmpty())

        val hidden = d.rects(DoorShapeRole.HIDDEN_PANEL_EDGE).single()
        // Panel cut size on job 688: 13.061 x 27.936 = opening + 2 x inset.
        assertEquals(1.782, hidden.x, 1e-9)
        assertEquals(1.782, hidden.y, 1e-9)
        assertEquals(13.061, hidden.width, 1e-9)
        assertEquals(27.936, hidden.height, 1e-9)

        assertEquals(
            listOf(
                DoorRect(1.75, 29.25, 0.5, 2.25), DoorRect(14.375, 29.25, 0.5, 2.25),
                DoorRect(1.75, 0.0, 0.5, 2.25), DoorRect(14.375, 0.0, 0.5, 2.25)
            ),
            d.rects(DoorShapeRole.TENON)
        )
        assertTrue(d.notes.isEmpty())
        assertTrue(d.warnings.isEmpty())
        // Outline paints last so the door edge is never covered.
        assertEquals(DoorShapeRole.OUTLINE, (d.shapes.last() as DoorShape.Rect).role)
        assertTrue((d.shapes.first() as DoorShape.Rect).role == DoorShapeRole.PANEL)
    }

    @Test
    fun glassPanelUsesTheGlassRoleAndMissingOpeningIsDerivedFromTheFrame() {
        val d = buildDoorDrawing(fivePiece(panelKind = "glass", opening = null))
        assertEquals(listOf(DoorRect(2.25, 2.25, 12.125, 27.0)), d.rects(DoorShapeRole.GLASS))
        assertTrue(d.rects(DoorShapeRole.PANEL).isEmpty())
    }

    @Test
    fun hingeCupsAreCvsPublishedPoints() {
        val r = HINGE_CUP_DIAMETER_IN / 2
        val left = buildDoorDrawing(fivePiece()).cups()
        assertEquals(2, left.size)
        assertPoint(DoorPoint(0.8071, 3.1496), left[0].center)
        assertPoint(DoorPoint(0.8071, 28.3504), left[1].center)
        assertEquals(r, left[0].radius, eps)

        // Job 688 door 53753 (top hinged) as published: points sit 0.8071 below the 18 in top edge.
        val topDoor = slab(hinges(DoorHingeSide.TOP, listOf(3.1496, 28.2254), points = listOf(DoorPoint(3.1496, 17.1929), DoorPoint(28.2254, 17.1929))))
            .copy(width = 31.375, height = 18.0)
        val topCups = buildDoorDrawing(topDoor).cups()
        assertPoint(DoorPoint(3.1496, 17.1929), topCups[0].center)
        assertPoint(DoorPoint(28.2254, 17.1929), topCups[1].center)

        // Points off the door are dropped instead of drawn outside it.
        val clipped = buildDoorDrawing(slab(hinges(DoorHingeSide.LEFT, points = listOf(DoorPoint(0.8071, 3.0), DoorPoint(0.8071, 45.0))))).cups()
        assertEquals(1, clipped.size)
    }

    @Test
    fun noHingesWithoutPointsOrEdgeOffset() {
        // Positions alone (older notes, or no hinge parts) never become guessed cups.
        assertTrue(buildDoorDrawing(slab(hinges(DoorHingeSide.LEFT, listOf(3.1496, 26.85)))).cups().isEmpty())
        val withPoints = listOf(DoorPoint(0.8071, 3.1496), DoorPoint(0.8071, 26.85))
        assertTrue(buildDoorDrawing(slab(hinges(DoorHingeSide.LEFT, points = withPoints, edgeOffset = null))).cups().isEmpty())
        assertEquals(2, buildDoorDrawing(slab(hinges(DoorHingeSide.LEFT, points = withPoints))).cups().size)
        // Points of a door whose side is none or unconfirmed are not drawn either.
        assertTrue(buildDoorDrawing(slab(hinges(DoorHingeSide.NONE, points = withPoints))).cups().isEmpty())
        assertTrue(buildDoorDrawing(slab(hinges(DoorHingeSide.OTHER, points = withPoints))).cups().isEmpty())
    }

    @Test
    fun noneAndUnconfirmedHingeSidesDrawNoCups() {
        assertTrue(buildDoorDrawing(slab(hinges(DoorHingeSide.NONE, listOf(3.0)))).cups().isEmpty())
        val other = buildDoorDrawing(fivePiece(hinges = hinges(DoorHingeSide.OTHER, code = "B")))
        assertTrue(other.cups().isEmpty())
        assertEquals(listOf("Hinge code B: side not confirmed, no hinges drawn"), other.notes)
    }

    @Test
    fun slabDrawsOutlineAndHingesOnly() {
        val d = buildDoorDrawing(
            slab(hinges(DoorHingeSide.RIGHT, listOf(3.1496, 26.85), points = listOf(DoorPoint(11.4429, 3.1496), DoorPoint(11.4429, 26.85))))
        )
        assertEquals(listOf(DoorRect(0.0, 0.0, 12.25, 30.0)), d.rects(DoorShapeRole.OUTLINE))
        assertEquals(1, d.shapes.filterIsInstance<DoorShape.Rect>().size)
        assertEquals(2, d.cups().size)
        assertEquals(3, d.shapes.size)
        assertTrue(d.notes.isEmpty())
    }

    @Test
    fun otherConstructionIsOutlineOnlyWithANote() {
        val door = fivePiece().copy(construction = DoorConstruction.OTHER)
        val d = buildDoorDrawing(door)
        assertEquals(listOf(DoorShape.Rect(DoorRect(0.0, 0.0, 16.625, 31.5), DoorShapeRole.OUTLINE)), d.shapes)
        assertEquals(listOf(OUTLINE_ONLY_NOTE), d.notes)
    }

    @Test
    fun fivePieceWithoutFrameFallsBackToOutlineAndChecksBecomeWarnings() {
        val d = buildDoorDrawing(fivePiece(checks = listOf("Frame does not add up")).copy(frame = null))
        assertTrue(d.rects(DoorShapeRole.STILE).isEmpty())
        assertTrue(d.rects(DoorShapeRole.PANEL).isEmpty())
        assertEquals(2, d.cups().size)
        assertEquals(listOf(FRAME_MISSING_NOTE), d.notes)
        assertEquals(listOf("Frame does not add up"), d.warnings)
    }

    @Test
    fun fitScalesToTheCanvasCentresAndFlipsY() {
        // Tall door in a square canvas: height limits the scale.
        val fit = DoorPreviewFit.of(16.625, 31.5, canvasWidth = 200f, canvasHeight = 200f, padding = 10f)
        assertEquals(180f / 31.5f, fit.scale, 1e-5f)
        val drawnW = 16.625f * fit.scale
        assertEquals((200f - drawnW) / 2, fit.offsetX, 1e-4f)
        assertEquals(10f, fit.offsetY, 1e-4f)
        // Door bottom-left is the canvas bottom-left of the drawing; door top is canvas top.
        assertEquals(fit.offsetX, fit.x(0.0), 1e-4f)
        assertEquals(190f, fit.y(0.0), 1e-4f)
        assertEquals(10f, fit.y(31.5), 1e-4f)
        // Bottom rail: left, top, right, bottom in canvas pixels.
        assertArrayEquals(
            floatArrayOf(fit.x(2.25), fit.y(2.25), fit.x(14.375), 190f),
            fit.rect(DoorRect(2.25, 0.0, 12.125, 2.25)),
            1e-4f
        )
        assertEquals(2.25f * fit.scale, fit.length(2.25), 1e-5f)

        // Wide door: width limits the scale.
        val wide = DoorPreviewFit.of(31.375, 18.0, canvasWidth = 100f, canvasHeight = 100f, padding = 0f)
        assertEquals(100f / 31.375f, wide.scale, 1e-5f)
        assertEquals(0f, wide.offsetX, 1e-4f)
    }

    @Test
    fun inchesAlwaysFormatAsDecimalsWithUpToFourPlaces() {
        assertEquals("16.625", formatDoorInches(16.625))
        assertEquals("31.5", formatDoorInches(31.5))
        assertEquals("12", formatDoorInches(12.0))
        assertEquals("2.2813", formatDoorInches(2.28125))
        assertEquals("0.4688", formatDoorInches(0.46875))
        assertEquals("0.468", formatDoorInches(0.468))
        assertEquals("3.1496", formatDoorInches(3.1496))
        assertEquals("0", formatDoorInches(-0.00000001))
    }
}
