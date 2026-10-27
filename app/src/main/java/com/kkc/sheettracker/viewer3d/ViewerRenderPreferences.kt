package com.kkc.sheettracker.viewer3d

import android.content.SharedPreferences
import org.json.JSONObject

/** App-owned preferences remain stable when the local viewer server changes its port. */
internal class ViewerRenderPreferences(private val preferences: SharedPreferences) {
    private fun quality(value: String?) = value?.takeIf { it in setOf("off", "low", "medium", "high") } ?: "high"

    fun readJson(): String = JSONObject()
        .put("showOutlines", preferences.getBoolean("show_outlines", true))
        .put("antialiasingQuality", quality(preferences.getString("antialiasing_quality", "high")))
        .toString()

    fun save(showOutlines: Boolean, antialiasingQuality: String) {
        preferences.edit()
            .putBoolean("show_outlines", showOutlines)
            .putString("antialiasing_quality", quality(antialiasingQuality))
            .apply()
    }
}
