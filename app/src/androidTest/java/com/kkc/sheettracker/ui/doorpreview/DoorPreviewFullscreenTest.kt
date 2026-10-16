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

class DoorPreviewFullscreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

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
