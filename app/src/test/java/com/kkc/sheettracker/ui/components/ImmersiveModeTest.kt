package com.kkc.sheettracker.ui.components

import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.junit.Assert.assertEquals
import org.junit.Test

class ImmersiveModeTest {

    @Test
    fun persistentImmersiveConfig_hidesSystemBarsWithTransientReveal() {
        val config = persistentImmersiveConfig()

        assertEquals(WindowInsetsCompat.Type.systemBars(), config.insetTypes)
        assertEquals(
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE,
            config.systemBarsBehavior
        )
    }
}
