package com.kkc.sheettracker.ui.viewer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.kkc.sheettracker.data.models.OcrBoxMetadata
import com.kkc.sheettracker.data.models.Part
import com.kkc.sheettracker.ui.theme.KKCTheme
import com.kkc.sheettracker.ui.theme.BuiltInKKCThemeTokens
import com.kkc.sheettracker.ui.theme.kkcFrostedContentColor
import com.kkc.sheettracker.ui.theme.toColorScheme
import com.kkc.sheettracker.ui.components.LocalLowEndMode
import com.kkc.sheettracker.ui.components.LowEndModeFlags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

class SheetPartInfoPopupUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val marked = Part(number = 1, cabNumber = 6, name = "Unfinished Right End",
        width = 12.875, length = 27.5, room = "PANTRY", rotated = true, banding = "2WD2LD")
    private val plain = Part(number = 2, cabNumber = 8, name = "Shelf", width = 20.0, length = 24.0)
    private val selected = mutableStateOf<Int?>(null)
    private val dark = mutableStateOf(false)
    private val tokens = mutableStateOf(BuiltInKKCThemeTokens)
    private val lowEnd = mutableStateOf(LowEndModeFlags(false, true, true, true, true))

    private fun showViewer(withGeometry: Boolean = true) {
        val boxes = mapOf(1 to listOf(OcrBoxMetadata(400, 300, 750, 700)),
            2 to listOf(OcrBoxMetadata(100, 150, 350, 400)))
        val bitmap = Bitmap.createBitmap(1000, 1000, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
            val canvas = Canvas(this)
            val paint = Paint().apply { color = Color.rgb(200, 180, 150) }
            boxes.values.flatten().forEach { canvas.drawRect(it.left.toFloat(), it.top.toFloat(),
                it.right.toFloat(), it.bottom.toFloat(), paint) }
        }
        compose.setContent {
            KKCTheme(darkTheme = dark.value, themeTokens = tokens.value) {
                CompositionLocalProvider(LocalLowEndMode provides lowEnd.value) {
                Column {
                    DiagramView(bitmap = bitmap, parts = listOf(marked, plain),
                        selectedPartNumber = selected.value, diagramBboxes = emptyMap(),
                        partOutlines = if (withGeometry) boxes else emptyMap(), resetZoomTrigger = 0,
                        onTapPart = { selected.value = if (selected.value == it) null else it },
                        onLongPressPart = {}, modifier = Modifier.fillMaxWidth().height(440.dp))
                    Button(onClick = { selected.value = marked.number }) { Text("Select list part") }
                    Button(onClick = { selected.value = plain.number }) { Text("Select plain part") }
                }
                }
            }
        }
    }

    @Test fun diagramTapShowsDetailsAndIndicatorsAndSecondTapDismisses() {
        showViewer()
        compose.onNodeWithText(sheetPartInfoText(marked)).assertDoesNotExist()
        val diagram = compose.onNodeWithContentDescription("Sheet diagram")
        diagram.performTouchInput { click(Offset(width * 0.55f, height * 0.55f)) }
        compose.onNodeWithText(sheetPartInfoText(marked)).assertIsDisplayed()
        compose.onNodeWithContentDescription("Rotated", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Edge banding", useUnmergedTree = true).assertIsDisplayed()
        diagram.performTouchInput { click(Offset(width * 0.55f, height * 0.55f)) }
        compose.onNodeWithText(sheetPartInfoText(marked)).assertDoesNotExist()
        compose.runOnIdle { assertEquals(null, selected.value) }
    }

    @Test fun listSelectionUpdatesPopupAndKeepsItReadableAtZoomInBothThemes() {
        showViewer()
        compose.onNodeWithText("Select list part").performClick()
        val details = compose.onNodeWithText(sheetPartInfoText(marked))
        details.assertIsDisplayed()
        details.performTouchInput { click() }
        compose.runOnIdle { assertEquals(marked.number, selected.value) }
        saveScreenshot("sheet-part-popup-light.png")
        val originalSize = details.fetchSemanticsNode().boundsInRoot.size
        compose.onNodeWithContentDescription("Sheet diagram").performTouchInput {
            pinch(Offset(width * 0.4f, height * 0.4f), Offset(width * 0.6f, height * 0.6f),
                Offset(width * 0.2f, height * 0.2f), Offset(width * 0.8f, height * 0.8f))
        }
        details.assertIsDisplayed()
        assertEquals(originalSize, details.fetchSemanticsNode().boundsInRoot.size)
        val diagramBounds = compose.onNodeWithContentDescription("Sheet diagram").fetchSemanticsNode().boundsInRoot
        val popupBounds = details.fetchSemanticsNode().boundsInRoot
        assertTrue(popupBounds.left >= diagramBounds.left && popupBounds.right <= diagramBounds.right)
        assertTrue(popupBounds.top >= diagramBounds.top && popupBounds.bottom <= diagramBounds.bottom)
        compose.runOnIdle { dark.value = true }
        details.assertIsDisplayed()
        saveScreenshot("sheet-part-popup-dark.png")
        compose.onNodeWithText("Select plain part").performClick()
        compose.onNodeWithText(sheetPartInfoText(plain)).assertIsDisplayed()
        compose.onNodeWithContentDescription("Rotated", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithContentDescription("Edge banding", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun listSelectionStillShowsDetailsForOlderSheetsWithoutGeometry() {
        showViewer(withGeometry = false)
        compose.onNodeWithText("Select list part").performClick()
        compose.onNodeWithText(sheetPartInfoText(marked)).assertIsDisplayed()
        compose.runOnIdle { selected.value = null }
        compose.onNodeWithText(sheetPartInfoText(marked)).assertDoesNotExist()
    }

    @Test fun customBoldThemeAndDisabledEffectsKeepDetailsAndMarkersVisible() {
        tokens.value = BuiltInKKCThemeTokens.copy(boldMode = true,
            light = BuiltInKKCThemeTokens.light.copy(primary = androidx.compose.ui.graphics.Color(0xFF6A1B9A),
                secondary = androidx.compose.ui.graphics.Color(0xFFAB47BC)))
        showViewer()
        compose.onNodeWithText("Select list part").performClick()
        compose.onNodeWithText(sheetPartInfoText(marked)).assertIsDisplayed()
        assertMarkerColor("Rotated", androidx.compose.ui.graphics.Color.Red)
        assertMarkerColor("Edge banding", tokens.value.light.primary)
        saveScreenshot("sheet-part-popup-bold.png")
        compose.runOnIdle { lowEnd.value = LowEndModeFlags(true, false, false, false, true) }
        compose.onNodeWithText(sheetPartInfoText(marked)).assertIsDisplayed()
        compose.onNodeWithContentDescription("Rotated", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Edge banding", useUnmergedTree = true).assertIsDisplayed()
    }

    private fun assertMarkerColor(description: String, expected: androidx.compose.ui.graphics.Color) {
        val pixels = compose.onNodeWithContentDescription(description, useUnmergedTree = true)
            .captureToImage().toPixelMap()
        val containsColor = (0 until pixels.height).any { y ->
            (0 until pixels.width).any { x ->
                val actual = pixels[x, y]
                kotlin.math.abs(actual.red - expected.red) < 0.03f &&
                    kotlin.math.abs(actual.green - expected.green) < 0.03f &&
                    kotlin.math.abs(actual.blue - expected.blue) < 0.03f
            }
        }
        assertTrue("$description does not use its expected color", containsColor)
    }

    @Test fun redGlassUsesContrastingStarAndNeutralPopupKeepsRedStarInSameTheme() {
        val red = androidx.compose.ui.graphics.Color(0xFFBA0C2F)
        tokens.value = BuiltInKKCThemeTokens.copy(boldMode = true,
            light = BuiltInKKCThemeTokens.light.copy(primary = red, secondary = red))
        showViewer()
        compose.onNodeWithText("Select list part").performClick()
        assertMarkerColor("Rotated", kkcFrostedContentColor(tokens.value, tokens.value.toColorScheme(false), false))
        saveScreenshot("sheet-part-popup-red.png")
        compose.runOnIdle { tokens.value = tokens.value.copy(boldMode = false) }
        assertMarkerColor("Rotated", androidx.compose.ui.graphics.Color.Red)
        assertMarkerColor("Edge banding", red)
    }

    private fun saveScreenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val image = instrumentation.uiAutomation.takeScreenshot()
        File(instrumentation.targetContext.getExternalFilesDir(null), name).outputStream().use {
            image.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        image.recycle()
    }
}
