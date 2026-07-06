package com.kkc.sheettracker.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * App-wide reactive holder for the simple admin hide/show flag. Backed by
 * [UiPreferencesStore] (SharedPreferences "kkc_tracker", key "admin_mode"). A single shared
 * StateFlow lets any screen — Settings, Supply, etc. — observe and flip admin mode without
 * threading the boolean through the navigation tree.
 *
 * This is intentionally NOT a security mechanism; it only reveals admin-only UI.
 */
object AdminModeController {
    private var store: UiPreferencesStore? = null
    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    /** Call once at app startup to load the persisted value. Idempotent. */
    fun init(context: Context) {
        val s = store ?: UiPreferencesStore(context.applicationContext).also { store = it }
        _enabled.value = s.getAdminMode()
    }

    fun setEnabled(value: Boolean) {
        store?.setAdminMode(value)
        _enabled.value = value
    }
}
