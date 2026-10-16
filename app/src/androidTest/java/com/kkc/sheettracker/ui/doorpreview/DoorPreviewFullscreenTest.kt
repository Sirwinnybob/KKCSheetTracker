package com.kkc.sheettracker.ui.doorpreview

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.kkc.sheettracker.data.cvjob.CvJobDoorPreviews
import com.kkc.sheettracker.data.cvjob.parseCvJobDoorPreviews
import java.io.File
import org.junit.Rule
import org.junit.Test
import androidx.compose.runtime.*

class DoorPreviewFullscreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun cabinetDoors(cabinet: Int = 9): CvJobDoorPreviews.Loaded {
        val json = InstrumentationRegistry.getInstrumentation().context.assets
            .open("job_data_690_cabinet${cabinet}_layout.json").bufferedReader().use { it.readText() }
        return parseCvJobDoorPreviews(json) as CvJobDoorPreviews.Loaded
    }
    @Test fun cabinetToggleShownOnlyWithALayout() {
        val doors=cabinetDoors(); val door=doors.forDoor("53737")!!
        var withLayout by mutableStateOf(true)
        compose.setContent { MaterialTheme { DoorPreviewFullscreen(if (withLayout) door else door.copy(cabinetLayout=null), {}) } }
        compose.onNodeWithText("Cabinet",useUnmergedTree=true).assertIsDisplayed()
        compose.onNodeWithText("Door only",useUnmergedTree=true).assertIsDisplayed()
        compose.runOnIdle { withLayout=false }
        compose.onNodeWithText("Cabinet",useUnmergedTree=true).assertDoesNotExist()
        compose.onNodeWithText("Door only",useUnmergedTree=true).assertDoesNotExist()
    }
    @Test fun doorOnlyReturnsToTheDoorHint() {
        val door=cabinetDoors().forDoor("53737")!!
        compose.setContent { MaterialTheme { DoorPreviewFullscreen(door,{}) } }
        compose.onNodeWithText("Bottom overlay 1.9375",substring=true).assertIsDisplayed()
        compose.onNodeWithText("Door only").performClick()
        compose.onNodeWithText("Tap a part to inspect it").assertIsDisplayed()
        compose.onNodeWithText("Pinch to zoom · Double-tap to reset").assertIsDisplayed()
    }
    @Test fun frontTapSwitchesTheSelectedDoor() {
        val loaded=cabinetDoors()
        var selected by mutableStateOf("53737")
        compose.setContent {
            MaterialTheme { DoorPreviewFullscreen(loaded.forDoor(selected)!!,{},onSelectDoor={selected=it;true},doorsById=loaded.allDoors.associateBy { it.doorId }) }
        }
        val canvas=compose.onNodeWithContentDescription("Full screen cabinet drawing")
        canvas.performTouchInput { click(Offset(width * 0.5f,height * 0.5f)) }
        compose.waitUntil(2_000) { selected=="53738" }
        compose.runOnIdle { org.junit.Assert.assertEquals("53738",selected) }
        canvas.performTouchInput { click(Offset(width * 0.5f,height * 0.5f)) }
        compose.waitUntil(2_000) { compose.onAllNodesWithText("Slab").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Cut size 18.375 × 36.125 in").assertIsDisplayed()
    }
    private fun saveCabinetScreen(name:String) {
        compose.waitForIdle()
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val image=instrumentation.uiAutomation.takeScreenshot()
        File(instrumentation.targetContext.getExternalFilesDir(null),name).outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) }
        image.recycle()
    }
    @Test fun selectedDoorPartsAreInspectableWithinTheSlopedCabinet() {
        val loaded=cabinetDoors(15);val door=loaded.forDoor("53751")!!;val l=door.cabinetLayout!!
        compose.setContent { MaterialTheme { DoorPreviewFullscreen(door,{},doorsById=loaded.allDoors.associateBy { it.doorId }) } }
        val canvas=compose.onNodeWithContentDescription("Full screen cabinet drawing")
        val density=InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        val b=canvas.fetchSemanticsNode().boundsInRoot
        val fit=cabinetFit(l,b.width,b.height,24*density)
        canvas.performTouchInput { click(Offset(fit.x(1.3),fit.y(42.0))) }
        compose.waitUntil(2_000) { compose.onAllNodesWithText("Door Left Stile").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Cut size 2.2813 × 24.6314 in").assertIsDisplayed()
        compose.onNodeWithText("Final size",substring=true).assertDoesNotExist()
        saveCabinetScreen("cabinet15-parts-textures.png")
    }
    @Test fun cabinetMemberTapShowsCutAndFinalSizes() {
        val loaded=cabinetDoors();val door=loaded.forDoor("53737")!!;val l=door.cabinetLayout!!
        compose.setContent { MaterialTheme { DoorPreviewFullscreen(door,{},doorsById=loaded.allDoors.associateBy { it.doorId }) } }
        val canvas=compose.onNodeWithContentDescription("Full screen cabinet drawing")
        val density=InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        val b=canvas.fetchSemanticsNode().boundsInRoot
        val fit=cabinetFit(l,b.width,b.height,24*density)
        canvas.performTouchInput { click(Offset(fit.x(0.25),fit.y(20.0))) }
        compose.waitUntil(2_000) { compose.onAllNodesWithText("Left Stile").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Cut size 1.5625 × 38.75 in").assertIsDisplayed()
        compose.onNodeWithText("Final size 1.5 × 38.75 in").assertIsDisplayed()
        saveCabinetScreen("cabinet9-preview.png")
    }
    @Test fun slopedCabinetKeepsItsShapeAndCutOffStileIsNotTappable() {
        val loaded=cabinetDoors(15);val door=loaded.forDoor("53751")!!;val l=door.cabinetLayout!!
        compose.setContent { MaterialTheme { DoorPreviewFullscreen(door,{},doorsById=loaded.allDoors.associateBy { it.doorId }) } }
        val canvas=compose.onNodeWithContentDescription("Full screen cabinet drawing")
        val density=InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        val b=canvas.fetchSemanticsNode().boundsInRoot
        val fit=cabinetFit(l,b.width,b.height,24*density)
        canvas.performTouchInput { click(Offset(fit.x(24.1),fit.y(39.9))) }
        compose.waitUntil(2_000) { compose.onAllNodesWithText("Right Stile").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Final size",substring=true).assertDoesNotExist()
        canvas.performTouchInput { click(Offset(fit.x(25.4),fit.y(40.0))) }
        compose.waitUntil(2_000) { compose.onAllNodesWithText("Right Stile").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("1A").assertIsDisplayed()
        saveCabinetScreen("cabinet15-preview.png")
    }

    private fun showDoors() {
        val json = InstrumentationRegistry.getInstrumentation().context.assets
            .open("job_data_302_cab11_layout.json").bufferedReader().use { it.readText() }
        val previews = parseCvJobDoorPreviews(json) as CvJobDoorPreviews.Loaded
        compose.setContent {
            MaterialTheme { DoorAssemblyList(previews = previews, stateKey = "fullscreen-test") }
        }
    }

    @Test fun thumbnailTapOpensFullscreenAndCloseLeavesThePartsCollapsed() {
        showDoors()
        compose.onNodeWithContentDescription("Open door thumbnail full screen")
            .performTouchInput { click() }
        compose.onNodeWithContentDescription("Close door preview").assertIsDisplayed()
        compose.onNodeWithText("PARTS").assertDoesNotExist()
        // One real-device visual checkpoint of the full-screen wide multi-panel door.
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val image = instrumentation.uiAutomation.takeScreenshot()
        File(instrumentation.targetContext.cacheDir, "door-preview-fullscreen.png").outputStream().use {
            image.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        image.recycle()
        val canvas = compose.onNodeWithContentDescription("Full screen door drawing")
        canvas.performTouchInput {
            pinch(center - Offset(40f, 0f), center - Offset(120f, 0f),
                center + Offset(40f, 0f), center + Offset(120f, 0f))
        }
        canvas.assert(SemanticsMatcher("zoomed in") {
            val percent = it.config.getOrNull(SemanticsProperties.StateDescription)
                ?.substringBefore('%')?.toIntOrNull()
            percent != null && percent > 100
        })
        canvas.performTouchInput { doubleClick() }
        canvas.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "100% zoom"))
        compose.onNodeWithContentDescription("Close door preview").performClick()
        compose.onNodeWithContentDescription("Close door preview").assertDoesNotExist()
        compose.onNodeWithText("PARTS").assertDoesNotExist()
        compose.onNodeWithContentDescription("Open door thumbnail full screen").assertIsDisplayed()
    }

    @Test fun tappingAMidStileShowsCutSizeAndStillWorksAfterZoomAndReset() {
        showDoors()
        compose.onNodeWithContentDescription("Open door thumbnail full screen").performClick()
        val canvas = compose.onNodeWithContentDescription("Full screen door drawing")
        canvas.performTouchInput { click(center) }
        compose.waitUntil(2_000) { compose.onAllNodesWithText("Door Mid Stile").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Door Mid Stile").assertIsDisplayed()
        compose.onNodeWithText("Cut size 3 × 24.9555 in").assertIsDisplayed()
        compose.onNodeWithText("3/4 Paint Grade Wood").assertIsDisplayed()
        canvas.performTouchInput {
            pinch(center - Offset(40f, 0f), center - Offset(100f, 0f),
                center + Offset(40f, 0f), center + Offset(100f, 0f))
        }
        canvas.performTouchInput { click(center) }
        compose.waitUntil(2_000) { compose.onAllNodesWithText("Door Mid Stile").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Door Mid Stile").assertIsDisplayed()
        canvas.performTouchInput { doubleClick() }
        canvas.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "100% zoom"))
        compose.onNodeWithText("Door Mid Stile").assertIsDisplayed()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        android.os.SystemClock.sleep(300) // Let the platform dialog animation settle for the visual checkpoint.
        val image = instrumentation.uiAutomation.takeScreenshot()
        File(instrumentation.targetContext.cacheDir, "door-part-inspection.png").outputStream().use {
            image.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        image.recycle()
        canvas.performTouchInput { click(Offset(2f, 2f)) }
        compose.waitUntil(2_000) { compose.onAllNodesWithText("Tap a part to inspect it").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Door Mid Stile").assertDoesNotExist()
        compose.onNodeWithText("Tap a part to inspect it").assertIsDisplayed()
    }

    @Test fun drawingTapOpensFullscreenAndBackReturnsToExpandedParts() {
        showDoors()
        compose.onNodeWithText("Cab 11").performTouchInput { click() }
        compose.onNodeWithText("PARTS").assertIsDisplayed()
        compose.onNodeWithText("11 parts").assertIsDisplayed()
        compose.onNodeWithText("Door Bumpers").assertExists()
        compose.onNodeWithContentDescription("Open door drawing full screen")
            .performTouchInput { click() }
        compose.onNodeWithContentDescription("Close door preview").assertIsDisplayed()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        compose.onNodeWithContentDescription("Close door preview").assertDoesNotExist()
        compose.onNodeWithText("PARTS").assertIsDisplayed()
    }
}
