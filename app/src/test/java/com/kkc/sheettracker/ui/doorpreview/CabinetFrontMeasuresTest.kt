package com.kkc.sheettracker.ui.doorpreview

import com.kkc.sheettracker.data.cvjob.*
import org.junit.Assert.*
import org.junit.Test

class CabinetFrontMeasuresTest {
    private fun layout(w:Double,h:Double,vararg rects:DoorRect)=CabinetFrontLayout(w,h,emptyList(),rects.mapIndexed { i,r->CabinetFront("$i","$i",r) },emptyList())
    private fun measure(l:CabinetFrontLayout,i:Int=0)=frontMeasures(l,l.fronts[i],FrontShape.Rect(l.fronts[i].rect))
    @Test fun framelessPairHasEighthInchReveals() {
        val l=layout(32.0,36.0,DoorRect(0.125,-0.875,15.8438,36.875),DoorRect(16.0938,-0.875,15.8438,36.875))
        val m=measure(l)
        assertFalse(m.framed); assertEquals(0.125,m.left!!,0.0);assertEquals(0.125,m.toNeighborAcross!!,0.0)
        assertEquals(0.0625,measure(l,1).right!!,0.0);assertEquals(0.0,m.top!!,0.0);assertEquals(-0.875,m.bottom!!,0.0)
        assertTrue(frontMeasureLines(m).contains("Bottom overhang 0.875"))
    }
    @Test fun framelessMirrorHasTheOtherSideSmall() {
        val l=layout(32.0,36.0,DoorRect(0.0625,-0.875,15.8438,36.875),DoorRect(16.0313,-0.875,15.8438,36.875))
        assertEquals(0.0625,measure(l).left!!,0.0);assertEquals(0.125,measure(l,1).right!!,0.0)
    }
    @Test fun drawerBankShowsTopAndVerticalGaps() {
        val l=layout(32.0,37.25,DoorRect(0.855,31.0,15.4475,6.0),DoorRect(16.4275,31.0,15.51,6.0),
            DoorRect(0.855,20.6875,31.0825,10.1875),DoorRect(0.855,10.375,31.0825,10.1875),DoorRect(0.855,0.0,31.0825,10.25))
        assertEquals(0.25,measure(l).top!!,0.0);assertEquals(0.125,measure(l,2).toNeighborAbove!!,0.0)
        assertEquals(0.855,measure(l,2).left!!,0.0);assertEquals(0.0625,measure(l,2).right!!,0.0)
        assertTrue(frontMeasureLines(measure(l,2)).contains("Left reveal 0.855"))
    }
    @Test fun doorOnlyCabinetOverhangs() {
        val m=measure(layout(29.5,9.625,DoorRect(0.0,0.0,29.5625,9.625)))
        assertEquals(-0.0625,m.right!!,0.0);assertTrue(frontMeasureLines(m).contains("Right overhang 0.0625"))
    }
    @Test fun faceFrameShowsOverlayNotReveal() {
        val doors=parseCvJobDoorPreviews(javaClass.getResource("/cvjob/job_data_690_cabinet9_layout.json")!!.readText()) as CvJobDoorPreviews.Loaded
        val l=doors.forDoor("53737")!!.cabinetLayout!!;val f=l.fronts.single { it.doorId=="53737" }
        val m=frontMeasures(l,f,FrontShape.Rect(f.rect))
        assertTrue(m.framed);assertEquals(0.6875,m.left!!,0.0);assertEquals(1.9375,m.bottom!!,0.0)
        assertTrue(frontMeasureLines(m).contains("Left overlay 0.6875"));assertTrue(frontMeasureLines(m).contains("Bottom overlay 1.9375"))
    }
    @Test fun angledDoorIsNotMeasured() {
        val l=layout(30.0,30.0,DoorRect(0.0,0.0,20.0,20.0))
        for (shape in listOf(FrontShape.Blank,FrontShape.Outline(listOf(DoorPoint(0.0,0.0),DoorPoint(20.0,0.0),DoorPoint(20.0,10.0),DoorPoint(0.0,20.0))))) {
            val m=frontMeasures(l,l.fronts.first(),shape)
            assertNull(m.left);assertNull(m.right);assertNull(m.top);assertNull(m.bottom);assertNull(m.toNeighborAcross);assertNull(m.toNeighborAbove)
            assertEquals(listOf("Angled door: reveals not measured"),frontMeasureLines(m))
        }
    }
    @Test fun snapsToSixtyFourths() {
        val l=layout(30.0,30.0,DoorRect(0.0624,0.855,20.0,20.0))
        assertEquals(0.0625,measure(l).left!!,0.0);assertEquals(0.855,measure(l).bottom!!,0.0)
    }
    @Test fun diagonalFrontDoesNotCountAsANeighbor() {
        val l=layout(30.0,30.0,DoorRect(0.0,0.0,5.0,5.0),DoorRect(6.0,6.0,5.0,5.0))
        assertNull(measure(l).toNeighborAbove);assertNull(measure(l).toNeighborAcross)
    }
}
