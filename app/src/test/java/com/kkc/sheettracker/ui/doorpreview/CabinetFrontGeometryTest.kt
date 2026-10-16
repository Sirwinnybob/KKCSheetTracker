package com.kkc.sheettracker.ui.doorpreview

import com.kkc.sheettracker.data.cvjob.*
import org.junit.Assert.*
import org.junit.Test
import androidx.compose.ui.graphics.Color

class CabinetFrontGeometryTest {
    @Test fun boundsIncludeStilesExtendingThroughTheToe() {
        val l=layout(); val member=l.frameMembers.first { it.code=="LS" }
        val extended=l.copy(frameMembers=listOf(member.copy(rect=DoorRect(0.0,-73.0,1.5,119.5))))
        assertEquals(-73.0,cabinetBounds(extended).y,0.0)
        assertEquals(119.5,cabinetBounds(extended).height,0.0)
    }
    @Test fun ghostColorKeepsHueAndSetsAlpha() {
        val base=Color(0xFF808080); val ghost=ghostColor(base)
        assertEquals(0.45f,ghost.alpha,0.001f)
        assertEquals(base.red,ghost.red,0.0f);assertEquals(base.green,ghost.green,0.0f);assertEquals(base.blue,ghost.blue,0.0f)
    }
    @Test fun faceOutlineUsesTheProvenSlopedOuterCorners() {
        val l=data(15).doors.first().cabinetLayout!!
        val outline=cabinetFaceOutline(l)
        assertEquals(DoorPoint(0.0,58.0),outline.last())
        assertEquals(38.9999,outline[2].y,0.002)
        assertEquals(25.5,outline[2].x,0.0)
    }
    private fun data(cab: Int = 9) = parseCvJobDoorPreviews(javaClass.getResource("/cvjob/job_data_690_cabinet${cab}_layout.json")!!.readText()) as CvJobDoorPreviews.Loaded
    private fun layout() = data().doors.first().cabinetLayout!!
    @Test fun boundsIncludeAnOverhangingFront() {
        val l=layout(); val shifted=l.copy(fronts=l.fronts+CabinetFront("extra",null,DoorRect(0.0,-1.9375,10.0,10.0)))
        assertEquals(DoorRect(0.0,-1.9375,57.0,40.6875),cabinetBounds(shifted))
    }
    @Test fun fitCentersAndKeepsAspect() {
        val fit=cabinetFit(layout(),570f,600f,0f)
        assertEquals(10f,fit.scale,0.001f)
        assertArrayEquals(floatArrayOf(0f,106.25f,570f,493.75f),fit.rect(DoorRect(0.0,0.0,57.0,38.75)),0.001f)
    }
    private fun hit(l:CabinetFrontLayout,x:Double,y:Double,shape:(CabinetFront)->FrontShape={FrontShape.Rect(it.rect)}):CabinetTarget? {
        val fit=cabinetFit(l,570f,600f,0f,shape)
        val px=fit.offsetX+((x-fit.boundsLeft)*fit.scale).toFloat()
        val py=fit.offsetY+((fit.boundsTop-y)*fit.scale).toFloat()
        return hitCabinetTarget(l,fit,570f,600f,px,py,frontShape=shape)
    }
    @Test fun frontsWinOverTheFrameWhereTheyOverlap() {
        val target=hit(layout(),1.0,10.0) as CabinetTarget.Front
        assertEquals("53737",target.front.doorId)
    }
    @Test fun tapOnFrameMemberReturnsMember() {
        val target=hit(layout(),0.25,10.0) as CabinetTarget.Member
        assertEquals("LS",target.member.code)
    }
    @Test fun tapInAVoidReturnsNull() {
        val l=data(15).doors.first().cabinetLayout!!
        assertNull(hit(l,0.75,57.9)) // Bounding face above the cut left stile.
        val plain=layout().copy(fronts=emptyList(),frameMembers=emptyList())
        assertNull(hit(plain,20.0,10.0))
    }
    @Test fun slopedMemberUsesItsPolygonForHits() {
        val l=data(15).doors.first().cabinetLayout!!.copy(fronts=emptyList())
        assertNull(hit(l,25.4,40.0))
        assertEquals("RS",(hit(l,24.1,39.9) as CabinetTarget.Member).member.code)
    }
    @Test fun slopedFrontHitUsesTheDoorOutline() {
        val d=data(15);val l=d.doors.first().cabinetLayout!!.copy(frameMembers=emptyList())
        val shapes: (CabinetFront)->FrontShape={ frontShape(l,it,d.allDoors.associateBy { door->door.doorId }) }
        assertNull(hit(l,23.0,55.0,shapes))
        assertEquals("53751",(hit(l,10.0,36.0,shapes) as CabinetTarget.Front).front.doorId)
    }
    @Test fun boundsUseTheSlopedOutline() {
        val l=layout().copy(fronts=listOf(CabinetFront("a","a",DoorRect(0.0,0.0,1.0,1.0))))
        val b=cabinetBounds(l) { FrontShape.Outline(listOf(DoorPoint(-2.0,0.0),DoorPoint(60.0,0.0),DoorPoint(60.0,20.0),DoorPoint(-2.0,40.0))) }
        assertEquals(DoorRect(-2.0,0.0,62.0,40.0),b)
    }
    @Test fun blankFrontDoesNotInterceptTheFrame() {
        assertEquals("LS",(hit(layout(),1.0,10.0) { FrontShape.Blank } as CabinetTarget.Member).member.code)
    }
    @Test fun zoomAndPanAreReversedBeforeHitTesting() {
        val l=layout();val fit=cabinetFit(l,570f,600f,0f)
        // Front at (20,20) maps to (200,293.75), then a 2x centre zoom and (15,10) pan.
        val target=hitCabinetTarget(l,fit,570f,600f,130f,297.5f,2f,15f,10f) as CabinetTarget.Front
        assertEquals("53738",target.front.doorId)
    }
    @Test fun nonFiniteInputReturnsNull() {
        val l=layout();val fit=cabinetFit(l,570f,600f,0f)
        assertNull(hitCabinetTarget(l,fit,570f,600f,Float.NaN,20f))
        assertNull(hitCabinetTarget(l,fit,570f,600f,20f,20f,zoom=Float.POSITIVE_INFINITY))
        assertNull(hitCabinetTarget(l,fit,570f,600f,-1f,20f))
    }
}
