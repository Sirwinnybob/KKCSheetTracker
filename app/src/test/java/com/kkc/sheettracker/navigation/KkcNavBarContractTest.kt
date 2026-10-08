package com.kkc.sheettracker.navigation

import com.kkc.sheettracker.navigation.KkcNavBarContract.EXTRA_BADGE_CONTAINER_DARK
import com.kkc.sheettracker.navigation.KkcNavBarContract.EXTRA_BADGE_CONTAINER_LIGHT
import com.kkc.sheettracker.navigation.KkcNavBarContract.EXTRA_BOLD_GRADIENT_DARK
import com.kkc.sheettracker.navigation.KkcNavBarContract.EXTRA_BOLD_GRADIENT_LIGHT
import com.kkc.sheettracker.navigation.KkcNavBarContract.EXTRA_BOLD_MODE
import com.kkc.sheettracker.navigation.KkcNavBarContract.EXTRA_DESTINATIONS
import com.kkc.sheettracker.navigation.KkcNavBarContract.EXTRA_FROSTED_ALPHA
import com.kkc.sheettracker.navigation.KkcNavBarContract.EXTRA_FROSTED_BASE_DARK
import com.kkc.sheettracker.navigation.KkcNavBarContract.EXTRA_FROSTED_BASE_LIGHT
import com.kkc.sheettracker.navigation.KkcNavBarContract.EXTRA_FROSTED_CONTENT_DARK
import com.kkc.sheettracker.navigation.KkcNavBarContract.EXTRA_FROSTED_CONTENT_LIGHT
import com.kkc.sheettracker.navigation.KkcNavBarContract.EXTRA_ON_SURFACE_VARIANT_DARK
import com.kkc.sheettracker.navigation.KkcNavBarContract.EXTRA_ON_SURFACE_VARIANT_LIGHT
import com.kkc.sheettracker.navigation.KkcNavBarContract.EXTRA_PRIMARY_DARK
import com.kkc.sheettracker.navigation.KkcNavBarContract.EXTRA_PRIMARY_LIGHT
import com.kkc.sheettracker.navigation.KkcNavBarContract.EXTRA_SUPPLY_COUNT
import com.kkc.sheettracker.navigation.KkcNavBarContract.EXTRA_SURFACE_VARIANT_DARK
import com.kkc.sheettracker.navigation.KkcNavBarContract.EXTRA_SURFACE_VARIANT_LIGHT
import com.kkc.sheettracker.navigation.KkcNavBarContract.EXTRA_VERSION
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class KkcNavBarContractTest {

    private val lightColors = KkcNavBarColors(
        primary = 0xFF112233.toInt(),
        onSurfaceVariant = 0xFF445566.toInt(),
        surfaceVariant = 0xFF778899.toInt(),
        frostedBase = 0xFFAABBCC.toInt(),
        frostedContent = 0xFF000000.toInt(),
        boldGradient = listOf(0xFFFF0000.toInt(), 0xFF0000FF.toInt()),
        badgeContainer = 0xFFB3261E.toInt(),
        badgeContent = 0xFFFFFFFF.toInt()
    )

    private val darkColors = KkcNavBarColors(
        primary = 0xFF998877.toInt(),
        onSurfaceVariant = 0xFF665544.toInt(),
        surfaceVariant = 0xFF332211.toInt(),
        frostedBase = 0xFF0C0D0E.toInt(),
        frostedContent = 0xFFFFFFFF.toInt(),
        boldGradient = listOf(0xFF00FF00.toInt(), 0xFFFFFF00.toInt()),
        badgeContainer = 0xFFF2B8B5.toInt(),
        badgeContent = 0xFF601410.toInt()
    )

    private val sample = KkcNavBarPayload(
        destinations = listOf("jobs", "hours", "timecard", "supply", "standards"),
        supplyCount = 3,
        safetyCount = 1,
        light = lightColors,
        dark = darkColors,
        frostedAlpha = 0.72f,
        frostedBlurDp = 14f,
        boldMode = true,
        indicatorCornerDp = 9f,
        animDisabled = false,
        blurDisabled = false,
        shadowsDisabled = true
    )

    @Test
    fun extrasUseTheCanonicalKeySet() {
        assertEquals(CANONICAL_KEYS, sample.toExtras().keys)
        assertEquals(27, sample.toExtras().size)
    }

    @Test
    fun versionIsTwo() {
        assertEquals(2, KkcNavBarContract.VERSION)
        assertEquals(2, sample.toExtras()[EXTRA_VERSION])
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
        assertTrue(extras[EXTRA_SUPPLY_COUNT] is Int)
        assertTrue(extras[EXTRA_FROSTED_ALPHA] is Float)
        assertTrue(extras[EXTRA_BOLD_MODE] is Boolean)
        assertTrue(extras[EXTRA_DESTINATIONS] is Array<*>)
        listOf(
            EXTRA_PRIMARY_LIGHT, EXTRA_PRIMARY_DARK,
            EXTRA_ON_SURFACE_VARIANT_LIGHT, EXTRA_ON_SURFACE_VARIANT_DARK,
            EXTRA_SURFACE_VARIANT_LIGHT, EXTRA_SURFACE_VARIANT_DARK,
            EXTRA_FROSTED_BASE_LIGHT, EXTRA_FROSTED_BASE_DARK,
            EXTRA_FROSTED_CONTENT_LIGHT, EXTRA_FROSTED_CONTENT_DARK,
            EXTRA_BADGE_CONTAINER_LIGHT, EXTRA_BADGE_CONTAINER_DARK
        ).forEach { assertTrue("$it must be an Int", extras[it] is Int) }
        assertTrue(extras[EXTRA_BOLD_GRADIENT_LIGHT] is IntArray)
        assertTrue(extras[EXTRA_BOLD_GRADIENT_DARK] is IntArray)
    }

    @Test
    fun lightAndDarkColorsAreEmittedUnderTheirOwnKeys() {
        val extras = sample.toExtras()
        assertEquals(lightColors.primary, extras[EXTRA_PRIMARY_LIGHT])
        assertEquals(darkColors.primary, extras[EXTRA_PRIMARY_DARK])
        assertEquals(lightColors.frostedContent, extras[EXTRA_FROSTED_CONTENT_LIGHT])
        assertEquals(darkColors.frostedContent, extras[EXTRA_FROSTED_CONTENT_DARK])
        assertArrayEquals(lightColors.boldGradient.toIntArray(), extras[EXTRA_BOLD_GRADIENT_LIGHT] as IntArray)
        assertArrayEquals(darkColors.boldGradient.toIntArray(), extras[EXTRA_BOLD_GRADIENT_DARK] as IntArray)
    }

    @Test
    fun colorsForPicksTheMatchingSet() {
        assertSame(sample.light, sample.colorsFor(darkTheme = false))
        assertSame(sample.dark, sample.colorsFor(darkTheme = true))
    }

    @Test
    fun legacySingleSetDarkExtraIsGone() {
        assertTrue("extra_kkc_navbar_dark" !in sample.toExtras().keys)
        assertTrue("extra_kkc_navbar_primary" !in sample.toExtras().keys)
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
            "extra_kkc_navbar_frosted_alpha",
            "extra_kkc_navbar_frosted_blur_dp",
            "extra_kkc_navbar_bold_mode",
            "extra_kkc_navbar_indicator_corner_dp",
            "extra_kkc_navbar_anim_disabled",
            "extra_kkc_navbar_blur_disabled",
            "extra_kkc_navbar_shadows_disabled",
            "extra_kkc_navbar_primary_light",
            "extra_kkc_navbar_primary_dark",
            "extra_kkc_navbar_on_surface_variant_light",
            "extra_kkc_navbar_on_surface_variant_dark",
            "extra_kkc_navbar_surface_variant_light",
            "extra_kkc_navbar_surface_variant_dark",
            "extra_kkc_navbar_frosted_base_light",
            "extra_kkc_navbar_frosted_base_dark",
            "extra_kkc_navbar_frosted_content_light",
            "extra_kkc_navbar_frosted_content_dark",
            "extra_kkc_navbar_bold_gradient_light",
            "extra_kkc_navbar_bold_gradient_dark",
            "extra_kkc_navbar_badge_container_light",
            "extra_kkc_navbar_badge_container_dark",
            "extra_kkc_navbar_badge_content_light",
            "extra_kkc_navbar_badge_content_dark"
        )
    }
}
