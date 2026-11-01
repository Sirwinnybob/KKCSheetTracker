package com.kkc.sheettracker.ui.viewer

import android.graphics.Bitmap
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import androidx.test.platform.app.InstrumentationRegistry
import com.kkc.sheettracker.data.mixfiles.*
import com.kkc.sheettracker.data.mixservice.ViewerMixSelection
import com.kkc.sheettracker.data.models.*
import com.kkc.sheettracker.navigation.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Network-free fixtures using the same order observer, navigator and route as the real viewer. */
class LiveMixViewerOrderTest {
    @get:Rule val compose = createComposeRule()
    private val material = Material("m.pdf", "19MM PRE_FINISHED", 11, metadata = MaterialMetadata(pages =
        listOf(PageMetadata(pageNumber = 11, sheetFiles = listOf("R11")),
            PageMetadata(pageNumber = 3, sheetFiles = listOf("R3")), PageMetadata(pageNumber = 6, sheetFiles = listOf("R6")))))
    private fun catalog(vararg pgms: String) = MixFilesCatalog("job", material.materialName, pgms.joinToString(), listOf(
        MixFileRow("MIX.FRZ", MixFileKind.FRZ, null, null, "r", null, false, null, null,
            pgms.map { MixFileEntry(it, it, linkedMapOf(), null, 1, 1, true, false) })))

    @Test fun navigatorReordersRetainsCurrentAdvancesOnRemovalAndKeepsOfflineCache() {
        val state = mutableStateOf<CatalogState>(CatalogState.Live(catalog("R11.pgm", "R3.pgm", "R6.pgm", "lost.pgm")))
        var current by mutableIntStateOf(11)
        var displayed by mutableStateOf(listOf(11, 3, 6))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "absent-live-mix.pdf")
        compose.setContent { MaterialTheme {
            val order = viewerOrderForState(material, state.value, "MIX.FRZ")
            ObserveViewerOrder(material, order, current, displayed) { pages, page -> displayed = pages; current = page }
            SheetNavigatorSheet(displayed, current, material.metadata!!.pages, emptyMap(), mutableMapOf(),
                mutableMapOf(11 to null, 3 to null, 6 to null), file, "", onSelectPage = { current = it }, onDismiss = {},
                unmatchedPgms = (order as? ViewerOrder.Mix)?.unmatched.orEmpty(),
                orderNotice = if (state.value is CatalogState.Offline) "Offline · showing saved mix order." else null)
        } }
        compose.onNodeWithTag("mix-unmatched-0").assertTextEquals("Unmatched · lost.pgm").assertHasNoClickAction()
        compose.runOnIdle { state.value = CatalogState.Live(catalog("R6.pgm", "R11.pgm", "R3.pgm", "lost.pgm")) }
        compose.runOnIdle { assertEquals(listOf(6, 11, 3), displayed); assertEquals(11, current) }
        compose.runOnIdle { state.value = CatalogState.Live(catalog("R6.pgm", "R3.pgm", "lost.pgm")) }
        compose.runOnIdle { assertEquals(listOf(6, 3), displayed); assertEquals(3, current) }
        compose.runOnIdle { state.value = CatalogState.Offline(catalog("R6.pgm", "R3.pgm", "lost.pgm")) }
        compose.onNodeWithText("Offline · showing saved mix order.").assertExists()
        compose.runOnIdle { assertEquals(listOf(6, 3), displayed); assertEquals(3, current) }
        // Expand the ordinary half-open navigator so its final row is inside the viewport.
        compose.onNodeWithContentDescription("Drag handle").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("mix-unmatched-0").performScrollTo().assertIsDisplayed()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.takeScreenshot().let { bitmap ->
            File(context.getExternalFilesDir(null), "task-9-live-navigator.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        compose.runOnIdle { state.value = CatalogState.Live(catalog("lost.pgm")) }
        compose.runOnIdle { assertEquals(emptyList<Int>(), displayed) }
        compose.onNodeWithTag("mix-unmatched-0").assertExists().assertHasNoClickAction()
        assertFalse(file.exists())
    }

    @Test fun androidNavigationDecodesViewerNamesExactlyOnce() {
        val name = "19 — # + %20 50%"
        val selected = ViewerMixSelection(name, "$name.FRZ")
        var actualFolder: String? = null
        var actualPdf: String? = null
        var actualMix: ViewerMixSelection? = null
        lateinit var controller: androidx.navigation.NavHostController
        compose.setContent { MaterialTheme {
            controller = rememberNavController()
            NavHost(controller, startDestination = "home") {
                composable("home") { Text("Home") }
                composable(VIEWER_ROUTE_PATTERN, arguments = listOf(
                    navArgument("folderName") { type = NavType.StringType },
                    navArgument("pdfFilename") { type = NavType.StringType },
                    navArgument("startPage") { type = NavType.IntType },
                    navArgument("mixName") { type = NavType.StringType; nullable = true; defaultValue = null },
                    navArgument("mixFile") { type = NavType.StringType; nullable = true; defaultValue = null },
                    navArgument("preferStartPage") { type = NavType.BoolType; defaultValue = false }
                )) { entry ->
                    actualFolder = entry.arguments?.getString("folderName")
                    actualPdf = entry.arguments?.getString("pdfFilename")
                    actualMix = viewerMixSelection(entry.arguments?.getString("mixName"), entry.arguments?.getString("mixFile"))
                    Text(actualMix?.mixFile.orEmpty())
                }
            }
        } }
        compose.runOnIdle { controller.navigate(viewerRoute(name, "$name.pdf", 11, selected)) }
        compose.onNodeWithText("$name.FRZ").assertExists()
        compose.runOnIdle { assertEquals(name, actualFolder); assertEquals("$name.pdf", actualPdf); assertEquals(selected, actualMix) }
    }
}
