package com.kkc.sheettracker.ui.components

import com.kkc.sheettracker.testutil.SourceFiles
import org.junit.Assert.assertTrue
import org.junit.Test

class HeaderGradientUpdateBadgeWiringTest {

    @Test
    fun settingsGearIconRendersUpdateDot() {
        val source = SourceFiles.mainSource("com/kkc/sheettracker/ui/components/HeaderGradient.kt").readText()

        assertTrue("HeaderGradient.kt must define LocalHasPendingUpdates", source.contains("val LocalHasPendingUpdates = staticCompositionLocalOf { false }"))
        assertTrue("KKCTopAppBar must read LocalHasPendingUpdates", source.contains("val hasPendingUpdates = LocalHasPendingUpdates.current"))
        assertTrue("KKCTopAppBar must check hasPendingUpdates before rendering the Settings icon", source.contains("if (hasPendingUpdates) {"))
        assertTrue("KKCTopAppBar must wrap the Settings icon in a BadgedBox with an empty dot Badge", source.contains("BadgedBox(badge = { Badge {} }) {"))
    }
}
