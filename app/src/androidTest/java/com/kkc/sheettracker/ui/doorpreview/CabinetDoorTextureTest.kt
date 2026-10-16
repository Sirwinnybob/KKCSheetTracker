package com.kkc.sheettracker.ui.doorpreview

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.kkc.sheettracker.data.cvjob.CvJobDoorPreviews
import com.kkc.sheettracker.data.cvjob.parseCvJobDoorPreviews
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class CabinetDoorTextureTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun otherDoorsRetainGrainAndUseTranslucentWholeDoorCompositing() {
        val json = InstrumentationRegistry.getInstrumentation().context.assets
            .open("job_data_690_cabinet9_layout.json").bufferedReader().use { it.readText() }
        val loaded = parseCvJobDoorPreviews(json) as CvJobDoorPreviews.Loaded
        val door = loaded.forDoor("53737")!!
        val layout = door.cabinetLayout!!
        compose.setContent { MaterialTheme { DoorPreviewFullscreen(door, {}, doorsById = loaded.allDoors.associateBy { it.doorId }) } }
        val canvas = compose.onNodeWithContentDescription("Full screen cabinet drawing")
        val image = canvas.captureToImage()
        val pixels = image.toPixelMap()
        val density = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        val fit = cabinetFit(layout, image.width.toFloat(), image.height.toFloat(), 24 * density)
        // Blue separates the wood from this light background enough to distinguish 45%, 72%,
        // and fully opaque rendering; red alone would barely change between those cases.
        fun blues(left: Double, right: Double): List<Float> =
            (fit.x(left).toInt()..fit.x(right).toInt()).map { pixels[it, fit.y(20.0).toInt()].blue }
        val solid = blues(4.0, 15.0)
        val other = blues(23.0, 34.0)
        assertTrue("Other door's wood grain must remain visible", other.max() - other.min() > 0.02f)
        val background = pixels[2, 2].blue
        assertEquals("Whole door uses partial opacity", solid.max() * 0.72f + background * 0.28f, other.max(), 0.015f)
    }
}
