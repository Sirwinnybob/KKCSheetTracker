package com.kkc.sheettracker.navigation

import android.content.Intent
import androidx.compose.runtime.Immutable

/**
 * Intent contract between KKCSheetTracker and Hours Tracker (com.example.timecard) for the
 * mirrored bottom navbar. KKC sends the navbar's resolved look; Hours Tracker draws a copy and
 * sends the tapped destination back. Sync header is expanded in Task 9.
 */
object KkcNavBarContract {
    const val VERSION = 1

    const val EXTRA_VERSION = "extra_kkc_navbar_version"
    const val EXTRA_DESTINATIONS = "extra_kkc_navbar_destinations"
    const val EXTRA_SUPPLY_COUNT = "extra_kkc_navbar_supply_count"
    const val EXTRA_SAFETY_COUNT = "extra_kkc_navbar_safety_count"
    const val EXTRA_DARK = "extra_kkc_navbar_dark"
    const val EXTRA_PRIMARY = "extra_kkc_navbar_primary"
    const val EXTRA_ON_SURFACE_VARIANT = "extra_kkc_navbar_on_surface_variant"
    const val EXTRA_SURFACE_VARIANT = "extra_kkc_navbar_surface_variant"
    const val EXTRA_FROSTED_BASE = "extra_kkc_navbar_frosted_base"
    const val EXTRA_FROSTED_CONTENT = "extra_kkc_navbar_frosted_content"
    const val EXTRA_FROSTED_ALPHA = "extra_kkc_navbar_frosted_alpha"
    const val EXTRA_FROSTED_BLUR_DP = "extra_kkc_navbar_frosted_blur_dp"
    const val EXTRA_BOLD_MODE = "extra_kkc_navbar_bold_mode"
    const val EXTRA_BOLD_GRADIENT = "extra_kkc_navbar_bold_gradient"
    const val EXTRA_BADGE_CONTAINER = "extra_kkc_navbar_badge_container"
    const val EXTRA_BADGE_CONTENT = "extra_kkc_navbar_badge_content"
    const val EXTRA_INDICATOR_CORNER_DP = "extra_kkc_navbar_indicator_corner_dp"
    const val EXTRA_ANIM_DISABLED = "extra_kkc_navbar_anim_disabled"
    const val EXTRA_BLUR_DISABLED = "extra_kkc_navbar_blur_disabled"
    const val EXTRA_SHADOWS_DISABLED = "extra_kkc_navbar_shadows_disabled"

    // Return path (Hours Tracker → KKC)
    const val EXTRA_NAV_DESTINATION = "extra_kkc_nav_destination"
    const val DEST_CALCULATOR = "calculator"
    const val DEST_HOURS = "hours"
    const val KKC_PACKAGE = "com.kkc.sheettracker"
    const val KKC_ACTIVITY = "com.kkc.sheettracker.MainActivity"
}

/** Resolved navbar look sent to Hours Tracker. Colors are ARGB ints. Same class exists in HT. */
// @Immutable: stable so lambdas capturing it (and the Legacy NavHost builder) aren't recreated
// every recomposition; equality is data-class equals.
@Immutable
data class KkcNavBarPayload(
    val destinations: List<String>,
    val supplyCount: Int,
    val safetyCount: Int,
    val dark: Boolean,
    val primary: Int,
    val onSurfaceVariant: Int,
    val surfaceVariant: Int,
    val frostedBase: Int,
    val frostedContent: Int,
    val frostedAlpha: Float,
    val frostedBlurDp: Float,
    val boldMode: Boolean,
    val boldGradient: List<Int>,
    val badgeContainer: Int,
    val badgeContent: Int,
    val indicatorCornerDp: Float,
    val animDisabled: Boolean,
    val blurDisabled: Boolean,
    val shadowsDisabled: Boolean
) {
    fun toExtras(): Map<String, Any> = with(KkcNavBarContract) {
        mapOf(
            EXTRA_VERSION to VERSION,
            EXTRA_DESTINATIONS to destinations.toTypedArray(),
            EXTRA_SUPPLY_COUNT to supplyCount,
            EXTRA_SAFETY_COUNT to safetyCount,
            EXTRA_DARK to dark,
            EXTRA_PRIMARY to primary,
            EXTRA_ON_SURFACE_VARIANT to onSurfaceVariant,
            EXTRA_SURFACE_VARIANT to surfaceVariant,
            EXTRA_FROSTED_BASE to frostedBase,
            EXTRA_FROSTED_CONTENT to frostedContent,
            EXTRA_FROSTED_ALPHA to frostedAlpha,
            EXTRA_FROSTED_BLUR_DP to frostedBlurDp,
            EXTRA_BOLD_MODE to boldMode,
            EXTRA_BOLD_GRADIENT to boldGradient.toIntArray(),
            EXTRA_BADGE_CONTAINER to badgeContainer,
            EXTRA_BADGE_CONTENT to badgeContent,
            EXTRA_INDICATOR_CORNER_DP to indicatorCornerDp,
            EXTRA_ANIM_DISABLED to animDisabled,
            EXTRA_BLUR_DISABLED to blurDisabled,
            EXTRA_SHADOWS_DISABLED to shadowsDisabled
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
