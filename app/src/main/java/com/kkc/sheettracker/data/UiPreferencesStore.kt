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
}
