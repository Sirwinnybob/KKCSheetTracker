package com.kkc.sheettracker.data

import android.content.SharedPreferences

data class AppStateFlagsSnapshot(
    val shadowEnabled: Boolean,
    val dashboardEnabled: Boolean,
    val jobsEnabled: Boolean,
    val detailEnabled: Boolean,
    val viewerStatusEnabled: Boolean,
    val navMultiStackEnabled: Boolean
)

class AppStateFeatureFlags(
    private val prefs: SharedPreferences,
    private val isDebugBuild: Boolean
) {
    fun snapshot(): AppStateFlagsSnapshot {
        return AppStateFlagsSnapshot(
            shadowEnabled = prefs.getBoolean(KEY_SHADOW_ENABLED, isDebugBuild),
            dashboardEnabled = prefs.getBoolean(KEY_DASHBOARD_ENABLED, false),
            jobsEnabled = prefs.getBoolean(KEY_JOBS_ENABLED, false),
            detailEnabled = prefs.getBoolean(KEY_DETAIL_ENABLED, false),
            viewerStatusEnabled = prefs.getBoolean(KEY_VIEWER_STATUS_ENABLED, false),
            navMultiStackEnabled = prefs.getBoolean(KEY_NAV_MULTISTACK_ENABLED, isDebugBuild)
        )
    }

    companion object {
        const val KEY_SHADOW_ENABLED = "app_state_store_shadow_enabled"
        const val KEY_DASHBOARD_ENABLED = "app_state_store_dashboard_enabled"
        const val KEY_JOBS_ENABLED = "app_state_store_jobs_enabled"
        const val KEY_DETAIL_ENABLED = "app_state_store_detail_enabled"
        const val KEY_VIEWER_STATUS_ENABLED = "app_state_store_viewer_status_enabled"
        const val KEY_NAV_MULTISTACK_ENABLED = "nav_multistack_enabled"
    }
}
