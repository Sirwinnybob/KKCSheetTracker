package com.kkc.sheettracker.ui.components

import com.kkc.sheettracker.testutil.SourceFiles
import org.junit.Assert.assertTrue
import org.junit.Test

class VerticalSplitLayoutWiringTest {

    @Test
    fun bothDividerControlRowsOwnIndependentHorizontalScrolling() {
        val source = SourceFiles.mainSource("com/kkc/sheettracker/ui/components/VerticalSplitLayout.kt").readText()
        val topControls = source.substringAfter("if (topDividerControls != null && dividerControlsVisible)")
            .substringBefore("                Box(")
        val bottomControls = source.substringAfter("if (bottomDividerControls != null && dividerControlsVisible)")
            .substringBefore("                bottomContent(")

        assertTrue(
            "top divider controls must scroll on their owning Row with a local scroll state",
            topControls.contains("modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 4.dp)")
        )
        assertTrue(
            "bottom divider controls must scroll on their owning Row with a local scroll state",
            bottomControls.contains("modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 4.dp)")
        )
    }
}
