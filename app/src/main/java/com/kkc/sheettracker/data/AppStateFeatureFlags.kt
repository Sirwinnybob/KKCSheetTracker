package com.kkc.sheettracker.data

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AppStateFlagsSnapshot(
    val shadowEnabled: Boolean,
    val dashboardEnabled: Boolean,
    val jobsEnabled: Boolean,
    val detailEnabled: Boolean,
    val viewerStatusEnabled: Boolean,
    val navMultiStackEnabled: Boolean,
    val lowEndMode: Boolean,
    val animationsEnabled: Boolean,
    val shadowsEnabled: Boolean,
    val blurEnabled: Boolean,
    val lazyLoadingEnabled: Boolean
)

class AppStateFeatureFlags(
    private val prefs: SharedPreferences,
    private val isDebugBuild: Boolean
) {
    private val _snapshot = MutableStateFlow(snapshot())
    val snapshotFlow = _snapshot.asStateFlow()

    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key in LOW_END_KEYS) notifyChanged()
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun snapshot(): AppStateFlagsSnapshot {
        return AppStateFlagsSnapshot(
            shadowEnabled = prefs.getBoolean(KEY_SHADOW_ENABLED, isDebugBuild),
            dashboardEnabled = prefs.getBoolean(KEY_DASHBOARD_ENABLED, true),
            jobsEnabled = prefs.getBoolean(KEY_JOBS_ENABLED, true),
            detailEnabled = prefs.getBoolean(KEY_DETAIL_ENABLED, false),
            viewerStatusEnabled = prefs.getBoolean(KEY_VIEWER_STATUS_ENABLED, false),
            navMultiStackEnabled = prefs.getBoolean(KEY_NAV_MULTISTACK_ENABLED, isDebugBuild),
            lowEndMode = prefs.getBoolean(KEY_LOW_END_MODE, false),
            animationsEnabled = prefs.getBoolean(KEY_LOW_END_ANIMATIONS_ENABLED, true),
            shadowsEnabled = prefs.getBoolean(KEY_LOW_END_SHADOWS_ENABLED, true),
            blurEnabled = prefs.getBoolean(KEY_LOW_END_BLUR_ENABLED, true),
            lazyLoadingEnabled = prefs.getBoolean(KEY_LOW_END_LAZY_LOADING_ENABLED, true)
        )
    }

    fun notifyChanged() {
        _snapshot.value = snapshot()
    }

    companion object {
        const val KEY_SHADOW_ENABLED = "app_state_store_shadow_enabled"
        const val KEY_DASHBOARD_ENABLED = "app_state_store_dashboard_enabled"
        const val KEY_JOBS_ENABLED = "app_state_store_jobs_enabled"
        const val KEY_DETAIL_ENABLED = "app_state_store_detail_enabled"
        const val KEY_VIEWER_STATUS_ENABLED = "app_state_store_viewer_status_enabled"
        const val KEY_NAV_MULTISTACK_ENABLED = "nav_multistack_enabled"
        const val KEY_LOW_END_MODE = "low_end_mode"
        const val KEY_LOW_END_ANIMATIONS_ENABLED = "low_end_animations_enabled"
        const val KEY_LOW_END_SHADOWS_ENABLED = "low_end_shadows_enabled"
        const val KEY_LOW_END_BLUR_ENABLED = "low_end_blur_enabled"
        const val KEY_LOW_END_LAZY_LOADING_ENABLED = "low_end_lazy_loading_enabled"

        private val LOW_END_KEYS = setOf(
            KEY_LOW_END_MODE,
            KEY_LOW_END_ANIMATIONS_ENABLED,
            KEY_LOW_END_SHADOWS_ENABLED,
            KEY_LOW_END_BLUR_ENABLED,
            KEY_LOW_END_LAZY_LOADING_ENABLED
        )
    }
}
