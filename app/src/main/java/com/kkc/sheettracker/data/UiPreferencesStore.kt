package com.kkc.sheettracker.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Persists lightweight UI preferences (e.g. board vs list view per screen)
 * using the same "kkc_tracker" SharedPreferences file as the rest of the app.
 */
class UiPreferencesStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("kkc_tracker", Context.MODE_PRIVATE)

    fun getBoardView(screen: String): Boolean =
        prefs.getBoolean("board_view_$screen", false)

    fun setBoardView(screen: String, value: Boolean) =
        prefs.edit().putBoolean("board_view_$screen", value).apply()

    /**
     * Admin mode is a simple, non-security hide/show gate unlocked by a plain-text
     * password in Settings. When on, extra UI (the supply "To Order" tab and job-lineup
     * editing) becomes visible. This is intentionally not real authentication.
     */
    fun getAdminMode(): Boolean =
        prefs.getBoolean("admin_mode", false)

    fun setAdminMode(enabled: Boolean) =
        prefs.edit().putBoolean("admin_mode", enabled).apply()

    fun getSupplyTabOrder(): List<String> {
        val raw = prefs.getString("supply_tab_order", "") ?: ""
        if (raw.isBlank()) return emptyList()
        return raw.split(",")
    }

    fun setSupplyTabOrder(order: List<String>) {
        prefs.edit().putString("supply_tab_order", order.joinToString(",")).apply()
    }

    fun isSafetySubscriber(): Boolean =
        prefs.getBoolean("safety_subscriber", false)

    fun setSafetySubscriber(subscribed: Boolean) =
        prefs.edit().putBoolean("safety_subscriber", subscribed).apply()

    fun getSafetyAuthorName(): String =
        prefs.getString("safety_author_name", "") ?: ""

    fun setSafetyAuthorName(name: String) =
        prefs.edit().putString("safety_author_name", name.trim()).apply()

    /**
     * Low-end device mode master toggle. When enabled, granular toggles below
     * are consulted to selectively disable expensive UI effects. Stored per-device
     * (no Syncthing sync).
     */
    fun getLowEndMode(): Boolean =
        prefs.getBoolean("low_end_mode", false)

    fun setLowEndMode(enabled: Boolean) =
        prefs.edit().putBoolean("low_end_mode", enabled).apply()

    /**
     * Granular UI effect toggles. Default true so effects are on unless
     * low_end_mode is enabled and user explicitly disables them.
     */
    fun getAnimationsEnabled(): Boolean =
        prefs.getBoolean("low_end_animations_enabled", true)

    fun setAnimationsEnabled(enabled: Boolean) =
        prefs.edit().putBoolean("low_end_animations_enabled", enabled).apply()

    fun getShadowsEnabled(): Boolean =
        prefs.getBoolean("low_end_shadows_enabled", true)

    fun setShadowsEnabled(enabled: Boolean) =
        prefs.edit().putBoolean("low_end_shadows_enabled", enabled).apply()

    fun getBlurEnabled(): Boolean =
        prefs.getBoolean("low_end_blur_enabled", true)

    fun setBlurEnabled(enabled: Boolean) =
        prefs.edit().putBoolean("low_end_blur_enabled", enabled).apply()

    fun getLazyLoadingEnabled(): Boolean =
        prefs.getBoolean("low_end_lazy_loading_enabled", true)

    fun setLazyLoadingEnabled(enabled: Boolean) =
        prefs.edit().putBoolean("low_end_lazy_loading_enabled", enabled).apply()
}
