package com.kkc.sheettracker.ui.doorpreview

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.kkc.sheettracker.data.cvjob.*
import java.io.File
import org.junit.Rule
import org.junit.Test

class DoorHingeInspectionUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun door(side: DoorHingeSide = DoorHingeSide.LEFT): DoorPreviewData {
        val points = if (side == DoorHingeSide.TOP) listOf(DoorPoint(4.25, 29.2), DoorPoint(15.5, 29.2))
            else listOf(DoorPoint(0.8, 3.125), DoorPoint(0.8, 26.75))
        val geometry = DoorGeometry("hinge-test", DoorConstruction.SLAB, 20.0, 30.0, 0.75, null, null,
            DoorHinges(side, side.name, 2, emptyList(), 0.8, points), emptyList())
        return DoorPreviewData(geometry, "Hinge test", "Slab", parts = listOf(
            DoorPart("slab", "slab", "Door Slab", "Oak", "20", "30")))
    }

    private val density get() = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density

    private fun tapDoorPoint(point: DoorPoint) {
        val canvas = compose.onNodeWithContentDescription("Full screen door drawing")
        val bounds = canvas.fetchSemanticsNode().boundsInRoot
        val fit = DoorPreviewFit.of(20.0, 30.0, bounds.width, bounds.height, 24 * density)
        canvas.performTouchInput { click(Offset(fit.x(point.x), fit.y(point.y))) }
    }

    private fun waitForText(text: String) {
        // Single taps wait for the double-tap reset window before the inspector updates.
        compose.waitUntil(2_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(text).assertIsDisplayed()
    }

    @Test fun sideHingeTapsShowBothEndsAndPartOrEmptyTapsClearTheSelection() {
        compose.setContent { MaterialTheme { DoorPreviewFullscreen(door(), {}) } }
        tapDoorPoint(DoorPoint(0.8, 3.125))
        waitForText("Hinge 1")
        compose.onNodeWithText("26.875 in from Top").assertIsDisplayed()
        compose.onNodeWithText("3.125 in from Bottom").assertIsDisplayed()
        saveScreen("door-hinge-side.png")
        tapDoorPoint(DoorPoint(0.8, 26.75))
        waitForText("Hinge 2")
        compose.onNodeWithText("3.25 in from Top").assertIsDisplayed()
        compose.onNodeWithText("26.75 in from Bottom").assertIsDisplayed()
        tapDoorPoint(DoorPoint(10.0, 15.0))
        waitForText("Door Slab")
        compose.onNodeWithText("Measured to hinge center").assertDoesNotExist()
        tapDoorPoint(DoorPoint(0.8, 3.125))
        waitForText("Hinge 1")
        compose.onNodeWithContentDescription("Full screen door drawing").performTouchInput { click(Offset(1f, 1f)) }
        waitForText("Tap a part or hinge to inspect it")
        compose.onNodeWithText("Hinge 1").assertDoesNotExist()
        compose.onNodeWithText("Tap a part or hinge to inspect it").assertIsDisplayed()
    }

    @Test fun topHingeShowsLeftAndRightDistancesInDarkTheme() {
        compose.setContent { MaterialTheme(colorScheme = darkColorScheme()) { DoorPreviewFullscreen(door(DoorHingeSide.TOP), {}) } }
        tapDoorPoint(DoorPoint(4.25, 29.2))
        waitForText("4.25 in from Left")
        compose.onNodeWithText("15.75 in from Right").assertIsDisplayed()
        compose.onNodeWithText("Measured to hinge center").assertIsDisplayed()
        saveScreen("door-hinge-top-dark.png")
    }

    @Test fun cabinetHingeIsSelectableAndSwitchingViewsClearsIt() {
        val door = door().let { d -> d.copy(cabinetLayout = CabinetFrontLayout(24.0, 40.0, emptyList(),
            listOf(CabinetFront("opening", d.doorId, DoorRect(2.0, 5.0, 20.0, 30.0))), emptyList())) }
        compose.setContent { MaterialTheme { DoorPreviewFullscreen(door, {}) } }
        val canvas = compose.onNodeWithContentDescription("Full screen cabinet drawing")
        val bounds = canvas.fetchSemanticsNode().boundsInRoot
        val fit = cabinetFit(door.cabinetLayout!!, bounds.width, bounds.height, 24 * density)
        canvas.performTouchInput { click(Offset(fit.x(2.8), fit.y(8.125))) }
        waitForText("26.875 in from Top")
        compose.onNodeWithText("3.125 in from Bottom").assertIsDisplayed()
        saveScreen("cabinet-hinge.png")
        compose.onNodeWithText("Door only").performClick()
        compose.onNodeWithText("Hinge 1").assertDoesNotExist()
        // Screen-reader actions expose the same per-hinge inspection as touch.
        val actions = compose.onNodeWithContentDescription("Full screen door drawing")
            .fetchSemanticsNode().config[SemanticsActions.CustomActions]
        compose.runOnIdle { actions[1].action() }
        compose.onNodeWithText("Hinge 2").assertIsDisplayed()
        compose.onNodeWithText("3.25 in from Top").assertIsDisplayed()
        compose.onNodeWithText("Cabinet").performClick()
        compose.onNodeWithText("Hinge 2").assertDoesNotExist()
    }

    private fun saveScreen(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val image = instrumentation.uiAutomation.takeScreenshot()
        File(instrumentation.targetContext.getExternalFilesDir(null), name).outputStream().use {
            image.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        image.recycle()
    }
}
