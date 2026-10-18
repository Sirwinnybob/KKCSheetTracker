package com.kkc.sheettracker.ui.components

import com.kkc.sheettracker.testutil.SourceFiles
import org.junit.Assert.assertTrue
import org.junit.Test

class NavBarShopKeypadWiringTest {

    @Test
    fun shopKeypadSearchUsesItsOwnTappableDisplayInsteadOfTheEditableField() {
        val source = SourceFiles.mainSource("com/kkc/sheettracker/ui/components/AppScaffold.kt").readText()

        assertTrue(
            "The shop keypad must open from a separate tappable display because BasicTextField consumes taps",
            source.contains("ShopKeypadSearchField(")
        )
    }

    @Test
    fun shopKeypadSearchDisplayKeepsAFullSizeTouchTarget() {
        val source = SourceFiles.mainSource("com/kkc/sheettracker/ui/components/AppScaffold.kt").readText()

        assertTrue(
            "The shop keypad search lane must have a 48dp high touch target",
            source.contains(".sizeIn(minHeight = 48.dp)")
        )
    }

    @Test
    fun shopKeypadRendersInItsOwnFrostedPopupAboveTheNavigationBar() {
        val source = SourceFiles.mainSource("com/kkc/sheettracker/ui/components/AppScaffold.kt").readText()

        assertTrue(
            "The keypad must be rendered by a popup so it cannot increase the navigation bar height",
            source.contains("private fun ShopKeypadPopup(")
        )
    }
}
