package com.kkc.sheettracker.navigation

import com.kkc.sheettracker.navigation.KkcNavBarContract.EXTRA_BOLD_GRADIENT
import com.kkc.sheettracker.navigation.KkcNavBarContract.EXTRA_BOLD_MODE
import com.kkc.sheettracker.navigation.KkcNavBarContract.EXTRA_DESTINATIONS
import com.kkc.sheettracker.navigation.KkcNavBarContract.EXTRA_FROSTED_ALPHA
import com.kkc.sheettracker.navigation.KkcNavBarContract.EXTRA_PRIMARY
import com.kkc.sheettracker.navigation.KkcNavBarContract.EXTRA_SUPPLY_COUNT
import com.kkc.sheettracker.navigation.KkcNavBarContract.EXTRA_VERSION
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KkcNavBarContractTest {

    private val sample = KkcNavBarPayload(
        destinations = listOf("jobs", "hours", "timecard", "supply", "standards"),
        supplyCount = 3,
        safetyCount = 1,
        dark = true,
        primary = 0xFF112233.toInt(),
        onSurfaceVariant = 0xFF445566.toInt(),
        surfaceVariant = 0xFF778899.toInt(),
        frostedBase = 0xFFAABBCC.toInt(),
        frostedContent = 0xFF000000.toInt(),
        frostedAlpha = 0.72f,
        frostedBlurDp = 14f,
        boldMode = true,
        boldGradient = listOf(0xFFFF0000.toInt(), 0xFF0000FF.toInt()),
        badgeContainer = 0xFFB3261E.toInt(),
        badgeContent = 0xFFFFFFFF.toInt(),
        indicatorCornerDp = 9f,
        animDisabled = false,
        blurDisabled = false,
        shadowsDisabled = true
    )

    @Test
    fun extrasUseTheCanonicalKeySet() {
        assertEquals(CANONICAL_KEYS, sample.toExtras().keys)
    }

    @Test
    fun versionIsOne() {
        assertEquals(1, KkcNavBarContract.VERSION)
        assertEquals(1, sample.toExtras()[EXTRA_VERSION])
    }

    @Test
    fun destinationsKeepBarOrder() {
        assertArrayEquals(
            arrayOf("jobs", "hours", "timecard", "supply", "standards"),
            sample.toExtras()[EXTRA_DESTINATIONS] as Array<*>
        )
    }

    @Test
    fun valueTypesMatchTheHoursTrackerParser() {
        val extras = sample.toExtras()
        assertTrue(extras[EXTRA_PRIMARY] is Int)
        assertTrue(extras[EXTRA_SUPPLY_COUNT] is Int)
        assertTrue(extras[EXTRA_FROSTED_ALPHA] is Float)
        assertTrue(extras[EXTRA_BOLD_MODE] is Boolean)
        assertTrue(extras[EXTRA_BOLD_GRADIENT] is IntArray)
        assertTrue(extras[EXTRA_DESTINATIONS] is Array<*>)
    }

    @Test
    fun returnPathConstantsMatchHoursTracker() {
        assertEquals("extra_kkc_nav_destination", KkcNavBarContract.EXTRA_NAV_DESTINATION)
        assertEquals("calculator", KkcNavBarContract.DEST_CALCULATOR)
        assertEquals("hours", KkcNavBarContract.DEST_HOURS)
        assertEquals("com.kkc.sheettracker", KkcNavBarContract.KKC_PACKAGE)
        assertEquals("com.kkc.sheettracker.MainActivity", KkcNavBarContract.KKC_ACTIVITY)
    }

    companion object {
        // KEEP IN SYNC with Hours Tracker
        // app/src/test/java/com/example/timecard/kkcnav/KkcNavBarContractTest.kt CANONICAL_KEYS.
        val CANONICAL_KEYS = setOf(
            "extra_kkc_navbar_version",
            "extra_kkc_navbar_destinations",
            "extra_kkc_navbar_supply_count",
            "extra_kkc_navbar_safety_count",
            "extra_kkc_navbar_dark",
            "extra_kkc_navbar_primary",
            "extra_kkc_navbar_on_surface_variant",
            "extra_kkc_navbar_surface_variant",
            "extra_kkc_navbar_frosted_base",
            "extra_kkc_navbar_frosted_content",
            "extra_kkc_navbar_frosted_alpha",
            "extra_kkc_navbar_frosted_blur_dp",
            "extra_kkc_navbar_bold_mode",
            "extra_kkc_navbar_bold_gradient",
            "extra_kkc_navbar_badge_container",
            "extra_kkc_navbar_badge_content",
            "extra_kkc_navbar_indicator_corner_dp",
            "extra_kkc_navbar_anim_disabled",
            "extra_kkc_navbar_blur_disabled",
            "extra_kkc_navbar_shadows_disabled"
        )
    }
}
