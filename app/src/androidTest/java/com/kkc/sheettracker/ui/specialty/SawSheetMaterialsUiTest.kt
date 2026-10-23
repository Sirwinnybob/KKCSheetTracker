package com.kkc.sheettracker.ui.specialty

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.data.SawSheetMaterial
import com.kkc.sheettracker.data.sheetUsageKey
import com.kkc.sheettracker.ui.supply.CategoryColumnLayout
import org.junit.Rule
import org.junit.Test

class SawSheetMaterialsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun expandedSheetListSupportsKanbanIntrinsicMeasurementAndManualEntry() {
        val material = "1/2 Baltic Birch Ply- Pre-Fin"
        val totals = mutableStateOf(emptyMap<String, Int>())
        compose.setContent {
            MaterialTheme {
                CategoryColumnLayout(
                    modifier = Modifier.height(800.dp),
                    header = {
                        SawSheetMaterialsDropdown(
                            materials = listOf(SawSheetMaterial(material, 2)), totals = totals.value,
                            stateKey = "intrinsic-test", readOnly = false, compact = true,
                            onAdjust = { name, delta ->
                                val key = sheetUsageKey(name)
                                totals.value = totals.value + (key to ((totals.value[key] ?: 0) + delta))
                            },
                            onSetCount = { name, count -> totals.value = totals.value + (sheetUsageKey(name) to count) },
                            onHide = { _, _ -> }
                        )
                    },
                    content = { Box(Modifier.width(300.dp).fillMaxHeight()) }
                )
            }
        }
        compose.onNodeWithText("Sheet materials").performClick()
        compose.onNodeWithText(material).assertIsDisplayed()
        compose.onNodeWithText("CNC: 2 sheets").assertIsDisplayed()
        compose.onNodeWithText("0/0").assertDoesNotExist()
        compose.onNodeWithContentDescription("Use one more $material sheet").performClick()
        compose.onNodeWithContentDescription("Enter saw sheets used for $material").performClick()
        compose.onNodeWithText("Saw sheets used").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("CNC: 2 sheets").assertIsDisplayed()
    }
}
