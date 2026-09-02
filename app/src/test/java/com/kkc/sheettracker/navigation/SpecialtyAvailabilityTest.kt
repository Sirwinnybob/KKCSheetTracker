package com.kkc.sheettracker.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class SpecialtyAvailabilityTest {
    @Test
    fun resolverCombinesTheSixAvailabilityChecks() {
        val result = resolveSpecialtyAvailability(
            hasDeliverySheet = { true },
            hasPullsSheet = { true },
            hasAssemblySheet = { false },
            hasPlansElevations = { true },
            hasThreeDAssets = { true },
            hasClosetRods = { false }
        )

        assertEquals(
            SpecialtyAvailability(
                hasDeliverySheet = true,
                hasPullsSheet = true,
                hasAssemblySheet = false,
                hasPlansElevations = true,
                hasThreeDAssets = true,
                hasClosetRods = false
            ),
            result
        )
    }
}
