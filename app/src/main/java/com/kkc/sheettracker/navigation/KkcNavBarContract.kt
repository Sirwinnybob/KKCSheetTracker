package com.kkc.sheettracker.navigation

import android.content.Intent
import androidx.compose.runtime.Immutable

/**
 * KEEP IN SYNC — byte-identical keys, VERSION and payload fields with Hours Tracker
 * C:\Scripts\Hours Tracker\AndroidApp\app\src\main\java\com\example\timecard\kkcnav\KkcNavBarContract.kt
 * Tests pin both sides to the same CANONICAL_KEYS list:
 *   KKC  app/src/test/java/com/kkc/sheettracker/navigation/KkcNavBarContractTest.kt
 *   HT   app/src/test/java/com/example/timecard/kkcnav/KkcNavBarContractTest.kt
 * Adding/removing/renaming a key or changing a value's type: update both files AND both tests,
 * and bump VERSION on both sides (an HT build that sees an unknown VERSION shows no bar, so old
 * and new builds stay safe in either order).
 *
 * v2: the bar follows HOURS TRACKER's light/dark mode, so KKC sends BOTH resolved color sets
 * (`_light` / `_dark` suffix keys; no single "dark" flag) and HT picks one at render time.
 * Theme-independent values (counts, alpha, blur, bold mode, corner, low-end flags) are sent once.
 */
object KkcNavBarContract {
    const val VERSION = 2

    // Theme-independent
    const val EXTRA_VERSION = "extra_kkc_navbar_version"
    const val EXTRA_DESTINATIONS = "extra_kkc_navbar_destinations"
    const val EXTRA_SUPPLY_COUNT = "extra_kkc_navbar_supply_count"
    const val EXTRA_SAFETY_COUNT = "extra_kkc_navbar_safety_count"
    const val EXTRA_FROSTED_ALPHA = "extra_kkc_navbar_frosted_alpha"
    const val EXTRA_FROSTED_BLUR_DP = "extra_kkc_navbar_frosted_blur_dp"
    const val EXTRA_BOLD_MODE = "extra_kkc_navbar_bold_mode"
    const val EXTRA_INDICATOR_CORNER_DP = "extra_kkc_navbar_indicator_corner_dp"
    const val EXTRA_ANIM_DISABLED = "extra_kkc_navbar_anim_disabled"
    const val EXTRA_BLUR_DISABLED = "extra_kkc_navbar_blur_disabled"
    const val EXTRA_SHADOWS_DISABLED = "extra_kkc_navbar_shadows_disabled"

    // Per-theme colors (light + dark sets)
    const val EXTRA_PRIMARY_LIGHT = "extra_kkc_navbar_primary_light"
    const val EXTRA_PRIMARY_DARK = "extra_kkc_navbar_primary_dark"
    const val EXTRA_ON_SURFACE_VARIANT_LIGHT = "extra_kkc_navbar_on_surface_variant_light"
    const val EXTRA_ON_SURFACE_VARIANT_DARK = "extra_kkc_navbar_on_surface_variant_dark"
    const val EXTRA_SURFACE_VARIANT_LIGHT = "extra_kkc_navbar_surface_variant_light"
    const val EXTRA_SURFACE_VARIANT_DARK = "extra_kkc_navbar_surface_variant_dark"
    const val EXTRA_FROSTED_BASE_LIGHT = "extra_kkc_navbar_frosted_base_light"
    const val EXTRA_FROSTED_BASE_DARK = "extra_kkc_navbar_frosted_base_dark"
    const val EXTRA_FROSTED_CONTENT_LIGHT = "extra_kkc_navbar_frosted_content_light"
    const val EXTRA_FROSTED_CONTENT_DARK = "extra_kkc_navbar_frosted_content_dark"
    const val EXTRA_BOLD_GRADIENT_LIGHT = "extra_kkc_navbar_bold_gradient_light"
    const val EXTRA_BOLD_GRADIENT_DARK = "extra_kkc_navbar_bold_gradient_dark"
    const val EXTRA_BADGE_CONTAINER_LIGHT = "extra_kkc_navbar_badge_container_light"
    const val EXTRA_BADGE_CONTAINER_DARK = "extra_kkc_navbar_badge_container_dark"
    const val EXTRA_BADGE_CONTENT_LIGHT = "extra_kkc_navbar_badge_content_light"
    const val EXTRA_BADGE_CONTENT_DARK = "extra_kkc_navbar_badge_content_dark"

    // Return path (Hours Tracker → KKC)
    const val EXTRA_NAV_DESTINATION = "extra_kkc_nav_destination"
    const val DEST_CALCULATOR = "calculator"
    const val DEST_HOURS = "hours"
    const val KKC_PACKAGE = "com.kkc.sheettracker"
    const val KKC_ACTIVITY = "com.kkc.sheettracker.MainActivity"
}

/** One theme's resolved navbar colors (ARGB ints). Same class exists in HT. */
@Immutable
data class KkcNavBarColors(
    val primary: Int,
    val onSurfaceVariant: Int,
    val surfaceVariant: Int,
    val frostedBase: Int,
    val frostedContent: Int,
    val boldGradient: List<Int>,
    val badgeContainer: Int,
    val badgeContent: Int
)

/** Resolved navbar look sent to Hours Tracker. Colors are ARGB ints. Same class exists in HT. */
// @Immutable: stable so lambdas capturing it (and the Legacy NavHost builder) aren't recreated
// every recomposition; equality is data-class equals.
@Immutable
data class KkcNavBarPayload(
    val destinations: List<String>,
    val supplyCount: Int,
    val safetyCount: Int,
    val light: KkcNavBarColors,
    val dark: KkcNavBarColors,
    val frostedAlpha: Float,
    val frostedBlurDp: Float,
    val boldMode: Boolean,
    val indicatorCornerDp: Float,
    val animDisabled: Boolean,
    val blurDisabled: Boolean,
    val shadowsDisabled: Boolean
) {
    fun colorsFor(darkTheme: Boolean): KkcNavBarColors = if (darkTheme) dark else light

    fun toExtras(): Map<String, Any> = with(KkcNavBarContract) {
        mapOf(
            EXTRA_VERSION to VERSION,
            EXTRA_DESTINATIONS to destinations.toTypedArray(),
            EXTRA_SUPPLY_COUNT to supplyCount,
            EXTRA_SAFETY_COUNT to safetyCount,
            EXTRA_FROSTED_ALPHA to frostedAlpha,
            EXTRA_FROSTED_BLUR_DP to frostedBlurDp,
            EXTRA_BOLD_MODE to boldMode,
            EXTRA_INDICATOR_CORNER_DP to indicatorCornerDp,
            EXTRA_ANIM_DISABLED to animDisabled,
            EXTRA_BLUR_DISABLED to blurDisabled,
            EXTRA_SHADOWS_DISABLED to shadowsDisabled,
            EXTRA_PRIMARY_LIGHT to light.primary,
            EXTRA_PRIMARY_DARK to dark.primary,
            EXTRA_ON_SURFACE_VARIANT_LIGHT to light.onSurfaceVariant,
            EXTRA_ON_SURFACE_VARIANT_DARK to dark.onSurfaceVariant,
            EXTRA_SURFACE_VARIANT_LIGHT to light.surfaceVariant,
            EXTRA_SURFACE_VARIANT_DARK to dark.surfaceVariant,
            EXTRA_FROSTED_BASE_LIGHT to light.frostedBase,
            EXTRA_FROSTED_BASE_DARK to dark.frostedBase,
            EXTRA_FROSTED_CONTENT_LIGHT to light.frostedContent,
            EXTRA_FROSTED_CONTENT_DARK to dark.frostedContent,
            EXTRA_BOLD_GRADIENT_LIGHT to light.boldGradient.toIntArray(),
            EXTRA_BOLD_GRADIENT_DARK to dark.boldGradient.toIntArray(),
            EXTRA_BADGE_CONTAINER_LIGHT to light.badgeContainer,
            EXTRA_BADGE_CONTAINER_DARK to dark.badgeContainer,
            EXTRA_BADGE_CONTENT_LIGHT to light.badgeContent,
            EXTRA_BADGE_CONTENT_DARK to dark.badgeContent
        )
    }
}

internal fun Intent.putKkcNavBarExtras(payload: KkcNavBarPayload) {
    payload.toExtras().forEach { (key, value) ->
        when (value) {
            is Int -> putExtra(key, value)
            is Float -> putExtra(key, value)
            is Boolean -> putExtra(key, value)
            is IntArray -> putExtra(key, value)
            is Array<*> -> putExtra(key, value.map { it as String }.toTypedArray())
            else -> error("Unsupported navbar extra type for $key: ${value::class}")
        }
    }
}
