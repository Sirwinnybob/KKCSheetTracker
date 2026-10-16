package com.kkc.sheettracker.ui.doorpreview

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.kkc.sheettracker.data.cvjob.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

internal fun doorTextureTestRoot(): File {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val root = File(instrumentation.targetContext.cacheDir, "door-textures-${System.nanoTime()}").apply { mkdirs() }
    val library = File(root, CV_MATERIAL_TEXTURES_RELATIVE_PATH).apply { mkdirs() }
    instrumentation.context.assets.open("door-materials/brown-hickory.jxl").use { input ->
        File(library, "wood.jxl").outputStream().use { input.copyTo(it) }
    }
    File(library, "manifest.json").writeText("""{"schemaVersion":1,"textures":[
        {"id":1355,"name":"Brown Hickory","sourcePath":"Brown Hickory.jpg","file":"wood.jxl",
          "worldWidthMm":1016,"worldHeightMm":1016,"tile":true},
        {"id":1558,"name":"Renamed placeholder","sourcePath":"CABINETS.JPG","file":"wood.jxl",
          "worldWidthMm":889,"worldHeightMm":889,"tile":true}],
        "materials":[
          {"id":1185,"name":"Wood","layers":[{"typeId":1,"textureId":1355}]},
          {"id":1195,"name":"Panel","layers":[{"typeId":1,"textureId":1355}]},
          {"id":9000,"name":"Maple","layers":[{"typeId":1,"textureId":1355}]},
          {"id":196,"name":"3/4 Paint Grade Wood","layers":[{"typeId":1,"textureId":1558}]}]}""")
    return root
}

class DoorMaterialTextureTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun door(panelMaterial: String = "1195", glass: Boolean = false): DoorPreviewData = DoorPreviewData(
        DoorGeometry("test", DoorConstruction.FIVE_PIECE, 20.0, 30.0, 0.75,
            DoorFrame(2.0, 2.0, 2.0, 2.0, 0.0, "Different frame report name"),
            DoorPanel(if (glass) "glass" else "flat", 0.0, 0.0, DoorRect(2.0, 2.0, 16.0, 26.0), "Different panel report name"),
            DoorHinges(DoorHingeSide.NONE, "", 0, emptyList(), null, emptyList()), emptyList()),
        "Test", "", parts = listOf(
            DoorPart("ls", "leftStile", "Left", "Frame", "2", "30", materialId = "1185"),
            DoorPart("rs", "rightStile", "Right", "Frame", "2", "30", materialId = "1185"),
            DoorPart("tr", "topRail", "Top", "Frame", "2", "16", materialId = "1185"),
            DoorPart("br", "bottomRail", "Bottom", "Frame", "2", "16", materialId = "1185"),
            DoorPart("p", "panel", "Panel", "Panel", "16", "26", materialId = panelMaterial)))

    private fun colorAt(x: Double, y: Double): Color {
        val image = compose.onNodeWithTag("door").captureToImage()
        val density = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        val fit = DoorPreviewFit.of(20.0, 30.0, image.width.toFloat(), image.height.toFloat(), 6 * density)
        return image.toPixelMap()[fit.x(x).toInt(), fit.y(y).toInt()]
    }

    @Test fun realJxlUsesPartIdsAndCabinetsImageRendersWhiteWithoutGrain() {
        val root = doorTextureTestRoot()
        var current by mutableStateOf(door())
        compose.setContent {
            MaterialTheme { DoorMaterialTextureProvider(root.absolutePath) {
                DoorPreviewCanvas(buildDoorDrawing(current), Modifier.size(300.dp, 450.dp).testTag("door"))
            } }
        }
        compose.waitUntil(15_000) { colorAt(1.0, 15.0).let { it.red - it.blue > 0.12f } }
        assertTrue("Panel uses its own published material ID", colorAt(10.0, 15.0).let { it.red - it.blue > 0.12f })
        compose.runOnIdle { current = door(panelMaterial = "196") }
        compose.waitUntil(5_000) { colorAt(10.0, 15.0) == Color.White }
        for (x in 4..16) assertEquals("No invented grain in Paint Grade", Color.White, colorAt(x.toDouble(), 15.0))
        assertTrue("Frame remains wood", colorAt(1.0, 15.0).let { it.red - it.blue > 0.12f })
    }

    @Test fun noLibraryRendersPureWhiteInDarkThemeAndGlassRetainsItsGeneratedGradient() {
        var current by mutableStateOf(door())
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                DoorPreviewCanvas(buildDoorDrawing(current), Modifier.size(300.dp, 450.dp)
                    .background(Color.Black).testTag("door"))
            }
        }
        assertEquals(Color.White, colorAt(10.0, 15.0))
        assertEquals(Color.White, colorAt(1.0, 15.0))
        compose.runOnIdle { current = door(glass = true) }
        val top = colorAt(10.0, 24.0)
        val bottom = colorAt(10.0, 6.0)
        assertTrue(top.blue > top.red)
        assertNotEquals(top, bottom)
    }

    @Test fun corruptAndLateSyncedTextureFilesRecoverToWhiteAndBackToWood() {
        val root = doorTextureTestRoot()
        val file = File(root, "$CV_MATERIAL_TEXTURES_RELATIVE_PATH/wood.jxl")
        val bytes = file.readBytes()
        file.writeText("corrupt")
        compose.setContent {
            MaterialTheme { DoorMaterialTextureProvider(root.absolutePath) {
                DoorPreviewCanvas(buildDoorDrawing(door()), Modifier.size(300.dp, 450.dp).testTag("door"))
            } }
        }
        assertEquals(Color.White, colorAt(10.0, 15.0))
        file.writeBytes(bytes)
        compose.waitUntil(15_000) { colorAt(10.0, 15.0).let { it.red - it.blue > 0.12f } }
        assertTrue(file.delete())
        compose.waitUntil(10_000) { colorAt(10.0, 15.0) == Color.White }
        file.writeBytes(bytes)
        compose.waitUntil(15_000) { colorAt(10.0, 15.0).let { it.red - it.blue > 0.12f } }
    }
}
