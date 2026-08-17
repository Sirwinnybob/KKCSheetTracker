package com.kkc.sheettracker.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class SpecialtyAvailabilityTest {
    @Test
    fun resolverCombinesTheFiveAvailabilityChecks() {
        val result = resolveSpecialtyAvailability(
            hasDeliverySheet = { true },
            hasAssemblySheet = { false },
            hasPlansElevations = { true },
            hasThreeDAssets = { true },
            hasClosetRods = { false }
        )

        assertEquals(
            SpecialtyAvailability(
                hasDeliverySheet = true,
                hasAssemblySheet = false,
                hasPlansElevations = true,
                hasThreeDAssets = true,
                hasClosetRods = false
            ),
            result
        )
    }
}
