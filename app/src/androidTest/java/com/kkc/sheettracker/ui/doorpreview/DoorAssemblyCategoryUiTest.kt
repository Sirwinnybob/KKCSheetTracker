package com.kkc.sheettracker.ui.doorpreview

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.test.platform.app.InstrumentationRegistry
import com.kkc.sheettracker.data.cvjob.CvJobDoorPreviews
import com.kkc.sheettracker.data.cvjob.parseCvJobDoorPreviews
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class DoorAssemblyCategoryUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun previews(): CvJobDoorPreviews.Loaded = parseCvJobDoorPreviews("""
        {"doors":[
          {"id":"door","cabinet":"1","label":"Normal door","doorType":"S"},
          {"id":"drawer","cabinet":"1","label":"Drawer front","doorType":"DF"},
          {"id":"end","cabinet":"2","label":"Applied end","doorType":"BE"},
          {"id":"wing","cabinet":"3","label":"Wing panel","doorType":"BE"}],
         "doorGeometry":[
          {"doorId":"door","width":"18","height":"30"},
          {"doorId":"drawer","width":"30","height":"6"},
          {"doorId":"end","width":"24","height":"35"},
          {"doorId":"wing","width":"35","height":"35"}]}
    """.trimIndent()) as CvJobDoorPreviews.Loaded

    private fun category(label: String) = compose.onNode(hasText(label) and hasClickAction())

    @Test fun categoryToggleFiltersTheListKeepsSortAndRestoresItsSelection() {
        val loaded = previews()
        val restoration = StateRestorationTester(compose)
        restoration.setContent { MaterialTheme { DoorAssemblyList(loaded, "category-test") } }
        compose.onNodeWithText("Normal door").assertIsDisplayed()
        compose.onNodeWithText("Drawer front").assertIsDisplayed()
        compose.onNodeWithText("Applied end").assertDoesNotExist()
        val toggle = category("Paneled Ends and Misc.")
        val sort = compose.onNodeWithText("Sort: Cabinet #")
        assertTrue(toggle.fetchSemanticsNode().boundsInRoot.right <= sort.fetchSemanticsNode().boundsInRoot.left)
        toggle.performClick()
        compose.onNodeWithText("Applied end").assertIsDisplayed()
        compose.onNodeWithText("Wing panel").assertIsDisplayed()
        compose.onNodeWithText("Normal door").assertDoesNotExist()
        compose.onNodeWithText("Sort: Cabinet #").performClick()
        compose.onNodeWithText("Smallest first").performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Sort: Smallest first").assertIsDisplayed()
        compose.onNodeWithText("Applied end").assertIsDisplayed()
        compose.onNodeWithText("Normal door").assertDoesNotExist()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val image = instrumentation.uiAutomation.takeScreenshot()
        File(instrumentation.targetContext.getExternalFilesDir(null), "door-assembly-categories.png")
            .outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
        category("Doors/Drawers").performClick()
        compose.onNodeWithText("Normal door").assertIsDisplayed()
        compose.onNodeWithText("Sort: Smallest first").assertIsDisplayed()
    }

    @Test fun anEmptyCategoryKeepsTheToggleAvailableAndSearchStaysWithinItsCategory() {
        val loaded = previews()
        val query = mutableStateOf("Wing")
        compose.setContent { MaterialTheme { DoorAssemblyList(loaded, "category-empty", query = query.value) } }
        compose.onNodeWithText("No doors or drawer fronts match \"Wing\".").assertIsDisplayed()
        category("Paneled Ends and Misc.").performClick()
        compose.onNodeWithText("Wing panel").assertIsDisplayed()
        compose.onNodeWithText("Applied end").assertDoesNotExist()
        compose.runOnIdle { query.value = "" }
        compose.onNodeWithText("Applied end").assertIsDisplayed()
    }
}
