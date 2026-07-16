package com.kkc.sheettracker.ui.components

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.kkc.sheettracker.data.models.ReferenceDocType

data class ReferenceModalSnapshot(
    val isOpen: Boolean = false,
    val docType: ReferenceDocType = ReferenceDocType.PLANS_ELEVATIONS,
    val plansPage: Int = 1,
    val assemblyPage: Int = 1,
    val modalX: Float = 24f,
    val modalY: Float = 24f,
    val modalWidth: Float = 360f,
    val modalHeight: Float = 480f
) {
    fun pageForActiveDoc(): Int =
        if (docType == ReferenceDocType.ASSEMBLY) assemblyPage else plansPage

    fun withDocType(next: ReferenceDocType): ReferenceModalSnapshot = copy(docType = next)

    fun withPage(page: Int): ReferenceModalSnapshot {
        val safe = page.coerceAtLeast(1)
        return if (docType == ReferenceDocType.ASSEMBLY) copy(assemblyPage = safe) else copy(plansPage = safe)
    }
}

/** First page mapped to [cabinet] in the active doc's page space, or null if none. */
fun resolveJumpPage(cabinetToPages: Map<String, List<Int>>, cabinet: Int): Int? =
    cabinetToPages[cabinet.toString()]?.firstOrNull()

class ReferenceModalOverlayState internal constructor(
    private val prefs: SharedPreferences
) {
    var snapshot by mutableStateOf(load(prefs))
        private set

    var noRefNoteToken by mutableStateOf(0)
        private set

    fun toggleOpen(defaultDocType: ReferenceDocType?) = setOpen(!snapshot.isOpen, defaultDocType)

    fun setOpen(open: Boolean, defaultDocType: ReferenceDocType?) {
        var next = snapshot
        if (open && defaultDocType != null) next = next.withDocType(defaultDocType)
        next = next.copy(isOpen = open)
        if (next == snapshot) return
        snapshot = next
        persist()
    }

    fun setDocType(docType: ReferenceDocType) {
        if (snapshot.docType == docType) return
        snapshot = snapshot.withDocType(docType)
        persist()
    }

    fun setPage(page: Int) {
        val next = snapshot.withPage(page)
        if (next == snapshot) return
        snapshot = next
        persist()
    }

    fun showNoRefNote() { noRefNoteToken += 1 }

    fun updateModalBounds(x: Float, y: Float, width: Float, height: Float, persistNow: Boolean) {
        val next = snapshot.copy(modalX = x, modalY = y, modalWidth = width, modalHeight = height)
        if (next == snapshot) return
        snapshot = next
        if (persistNow) persist()
    }

    fun clampToViewport(vw: Float, vh: Float, margin: Float, minW: Float, minH: Float) {
        val maxW = (vw - margin * 2f).coerceAtLeast(minW)
        val maxH = (vh - margin * 2f).coerceAtLeast(minH)
        val w = snapshot.modalWidth.coerceIn(minW, maxW)
        val h = snapshot.modalHeight.coerceIn(minH, maxH)
        val x = snapshot.modalX.coerceIn(margin, (vw - w - margin).coerceAtLeast(margin))
        val y = snapshot.modalY.coerceIn(margin, (vh - h - margin).coerceAtLeast(margin))
        val next = snapshot.copy(modalX = x, modalY = y, modalWidth = w, modalHeight = h)
        if (next != snapshot) { snapshot = next; persist() }
    }

    private fun persist() {
        prefs.edit()
            .putBoolean(KEY_OPEN, snapshot.isOpen)
            .putString(KEY_DOC, snapshot.docType.name)
            .putInt(KEY_PLANS_PAGE, snapshot.plansPage)
            .putInt(KEY_ASM_PAGE, snapshot.assemblyPage)
            .putFloat(KEY_X, snapshot.modalX)
            .putFloat(KEY_Y, snapshot.modalY)
            .putFloat(KEY_W, snapshot.modalWidth)
            .putFloat(KEY_H, snapshot.modalHeight)
            .apply()
    }

    companion object {
        private const val PREFS_FILE = "kkc_tracker"
        private const val KEY_OPEN = "refmodal_open"
        private const val KEY_DOC = "refmodal_doc"
        private const val KEY_PLANS_PAGE = "refmodal_plans_page"
        private const val KEY_ASM_PAGE = "refmodal_asm_page"
        private const val KEY_X = "refmodal_x_dp"
        private const val KEY_Y = "refmodal_y_dp"
        private const val KEY_W = "refmodal_w_dp"
        private const val KEY_H = "refmodal_h_dp"

        fun create(context: Context): ReferenceModalOverlayState =
            ReferenceModalOverlayState(context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE))

        private fun load(prefs: SharedPreferences): ReferenceModalSnapshot {
            val doc = prefs.getString(KEY_DOC, null)
                ?.let { runCatching { ReferenceDocType.valueOf(it) }.getOrNull() }
                ?: ReferenceDocType.PLANS_ELEVATIONS
            return ReferenceModalSnapshot(
                isOpen = prefs.getBoolean(KEY_OPEN, false),
                docType = doc,
                plansPage = prefs.getInt(KEY_PLANS_PAGE, 1),
                assemblyPage = prefs.getInt(KEY_ASM_PAGE, 1),
                modalX = prefs.getFloat(KEY_X, 24f),
                modalY = prefs.getFloat(KEY_Y, 24f),
                modalWidth = prefs.getFloat(KEY_W, 360f),
                modalHeight = prefs.getFloat(KEY_H, 480f)
            )
        }
    }
}

@Composable
fun rememberReferenceModalOverlayState(): ReferenceModalOverlayState {
    val context = LocalContext.current
    return remember { ReferenceModalOverlayState.create(context) }
}
