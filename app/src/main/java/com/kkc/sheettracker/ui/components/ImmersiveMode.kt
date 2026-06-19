package com.kkc.sheettracker.ui.components

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

internal data class PersistentImmersiveConfig(
    val insetTypes: Int,
    val systemBarsBehavior: Int
)

internal fun persistentImmersiveConfig(): PersistentImmersiveConfig =
    PersistentImmersiveConfig(
        insetTypes = WindowInsetsCompat.Type.systemBars(),
        systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    )

/**
 * Hides the system status bar and navigation bar (immersive mode) for the lifetime
 * of the composable that calls this. On dispose (navigation back), the system bars
 * are restored and normal window fitting resumes.
 *
 * Uses BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE so the user can temporarily reveal
 * system bars via an edge swipe without permanently exiting immersive mode.
 */
@Composable
fun ImmersiveSystemBars() {
    val view = LocalView.current
    val isInspection = LocalInspectionMode.current
    if (!isInspection) {
        DisposableEffect(Unit) {
            val activity = view.context as? Activity
                ?: return@DisposableEffect onDispose {}
            val window = activity.window
            WindowCompat.setDecorFitsSystemWindows(window, false)
            val controller = WindowInsetsControllerCompat(window, view)
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            // App is always edge-to-edge immersive (see MainActivity.onWindowFocusChanged
            // and PersistentNavigationBarHider). Do NOT restore the bars on dispose —
            // leaving a viewer must not un-hide them on the screens behind it.
            onDispose { }
        }
    }
}

/**
 * Persistently hides both the status bar (notification bar) and the system navigation bar
 * (back/home/recents) for the lifetime of this composable.
 *
 * Intended to be placed at the root app level so the app is always in true edge-to-edge
 * mode. Uses BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE so users can still swipe either bar
 * into view temporarily when needed.
 *
 * On dispose, system bars are restored.
 */
@Composable
fun PersistentNavigationBarHider() {
    val view = LocalView.current
    val isInspection = LocalInspectionMode.current
    if (!isInspection) {
        DisposableEffect(Unit) {
            val activity = view.context as? Activity
                ?: return@DisposableEffect onDispose {}
            val window = activity.window
            val config = persistentImmersiveConfig()
            WindowCompat.setDecorFitsSystemWindows(window, false)
            val controller = WindowInsetsControllerCompat(window, view)
            controller.hide(config.insetTypes)
            controller.systemBarsBehavior = config.systemBarsBehavior
            onDispose {
                controller.show(config.insetTypes)
            }
        }
    }
}
