package com.kkc.sheettracker.ui.viewer

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.kkc.sheettracker.data.models.ReferenceDocType

/**
 * Main CNC viewer's image-area mode: which document is shown full-size in place of the Sheet
 * bitmap. `mode == null` means Sheet (the default). Independent of, and separately persisted
 * from, the popup reference viewer's own state in `ReferenceModalOverlay.kt`.
 */
data class MainViewReferenceSnapshot(
    val mode: ReferenceDocType? = null,
    val plansPage: Int = 1,
    val assemblyPage: Int = 1
) {
    fun pageForMode(): Int = if (mode == ReferenceDocType.ASSEMBLY) assemblyPage else plansPage

    fun withMode(next: ReferenceDocType?): MainViewReferenceSnapshot = copy(mode = next)

    fun withPage(page: Int): MainViewReferenceSnapshot {
        val safe = page.coerceAtLeast(1)
        return if (mode == ReferenceDocType.ASSEMBLY) copy(assemblyPage = safe) else copy(plansPage = safe)
    }
}

class MainViewReferenceState internal constructor(
    private val prefs: SharedPreferences
) {
    var snapshot by mutableStateOf(load(prefs))
        private set

    fun setMode(next: ReferenceDocType?) {
        val nextSnapshot = snapshot.withMode(next)
        if (nextSnapshot == snapshot) return
        snapshot = nextSnapshot
        persist()
    }

    fun setPage(page: Int) {
        val next = snapshot.withPage(page)
        if (next == snapshot) return
        snapshot = next
        persist()
    }

    private fun persist() {
        prefs.edit()
            .putString(KEY_MODE, snapshot.mode?.name.orEmpty())
            .putInt(KEY_PLANS_PAGE, snapshot.plansPage)
            .putInt(KEY_ASM_PAGE, snapshot.assemblyPage)
            .apply()
    }

    companion object {
        private const val PREFS_FILE = "kkc_tracker"
        private const val KEY_MODE = "mainview_ref_mode"
        private const val KEY_PLANS_PAGE = "mainview_ref_plans_page"
        private const val KEY_ASM_PAGE = "mainview_ref_asm_page"

        fun create(context: Context): MainViewReferenceState =
            MainViewReferenceState(context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE))

        private fun load(prefs: SharedPreferences): MainViewReferenceSnapshot {
            val modeName = prefs.getString(KEY_MODE, null)
            val mode = modeName?.takeIf { it.isNotBlank() }
                ?.let { runCatching { ReferenceDocType.valueOf(it) }.getOrNull() }
            return MainViewReferenceSnapshot(
                mode = mode,
                plansPage = prefs.getInt(KEY_PLANS_PAGE, 1),
                assemblyPage = prefs.getInt(KEY_ASM_PAGE, 1)
            )
        }
    }
}

@Composable
fun rememberMainViewReferenceState(): MainViewReferenceState {
    val context = LocalContext.current
    return remember { MainViewReferenceState.create(context) }
}
