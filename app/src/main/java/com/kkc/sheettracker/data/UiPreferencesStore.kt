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
}
