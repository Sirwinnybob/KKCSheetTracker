package com.kkc.sheettracker.ui.components

import android.content.Context
import android.content.SharedPreferences
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.ui.zIndex
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class CalculatorMode {
    FULLSCREEN,
    MODAL
}

data class CalculatorSnapshot(
    val isOpen: Boolean = false,
    val mode: CalculatorMode = CalculatorMode.MODAL,
    val modalX: Float = 24f,
    val modalY: Float = 24f,
    val modalWidth: Float = 360f,
    val modalHeight: Float = 480f,
    val engineState: CalculatorEngineState = CalculatorEngineState(),
    val history: List<CalculatorHistoryEntry> = emptyList()
)

data class CalculatorHistoryEntry(
    val expression: String,
    val result: String,
    val timestampMs: Long
)

class CalculatorOverlayState internal constructor(
    private val prefs: SharedPreferences
) {
    var snapshot by mutableStateOf(loadCalculatorSnapshot(prefs))
        private set

    fun toggleOpen() {
        setOpen(!snapshot.isOpen)
    }

    fun setOpen(open: Boolean) {
        if (snapshot.isOpen == open) return
        snapshot = snapshot.copy(isOpen = open)
        persist()
    }

    fun setMode(mode: CalculatorMode) {
        if (snapshot.mode == mode) return
        snapshot = snapshot.copy(mode = mode)
        persist()
    }

    fun pressKey(key: String) {
        val before = snapshot
        val nextEngine = CalculatorEngine.press(before.engineState, key)
        var nextHistory = before.history
        if (key == "=" && !nextEngine.hasError) {
            val expr = before.engineState.expression
                .replace('*', '×')
                .replace('/', '÷')
                .trim()
            val result = nextEngine.display.trim()
            if (expr.isNotBlank() && expr != result) {
                val newEntry = CalculatorHistoryEntry(
                    expression = expr,
                    result = result,
                    timestampMs = System.currentTimeMillis()
                )
                nextHistory = listOf(newEntry) + nextHistory
                if (nextHistory.size > MAX_HISTORY_ITEMS) {
                    nextHistory = nextHistory.take(MAX_HISTORY_ITEMS)
                }
            }
        }
        snapshot = snapshot.copy(engineState = nextEngine, history = nextHistory)
        persist()
    }

    fun applyHistoryResult(entry: CalculatorHistoryEntry) {
        snapshot = snapshot.copy(
            engineState = CalculatorEngineState(
                expression = entry.result,
                display = entry.result,
                memory = snapshot.engineState.memory,
                hasError = false,
                justEvaluated = true
            )
        )
        persist()
    }

    fun applyHistoryExpression(entry: CalculatorHistoryEntry) {
        snapshot = snapshot.copy(
            engineState = CalculatorEngine.loadExpression(
                state = snapshot.engineState,
                expression = entry.expression
            )
        )
        persist()
    }

    fun clearHistory() {
        if (snapshot.history.isEmpty()) return
        snapshot = snapshot.copy(history = emptyList())
        persist()
    }

    fun updateModalBounds(x: Float, y: Float, width: Float, height: Float, persistNow: Boolean) {
        val current = snapshot
        val next = current.copy(
            modalX = x,
            modalY = y,
            modalWidth = width,
            modalHeight = height
        )
        if (next == current) return
        snapshot = next
        if (persistNow) persist()
    }

    fun clampToViewport(viewportWidth: Float, viewportHeight: Float, margin: Float, minWidth: Float, minHeight: Float) {
        val clamped = clampBounds(snapshot, viewportWidth, viewportHeight, margin, minWidth, minHeight)
        if (clamped != snapshot) {
            snapshot = clamped
            persist()
        }
    }

    private fun persist() {
        prefs.edit()
            .putBoolean(KEY_OPEN, snapshot.isOpen)
            .putString(KEY_MODE, snapshot.mode.name)
            .putFloat(KEY_MODAL_X, snapshot.modalX)
            .putFloat(KEY_MODAL_Y, snapshot.modalY)
            .putFloat(KEY_MODAL_WIDTH, snapshot.modalWidth)
            .putFloat(KEY_MODAL_HEIGHT, snapshot.modalHeight)
            .putString(KEY_EXPR, snapshot.engineState.expression)
            .putString(KEY_DISPLAY, snapshot.engineState.display)
            .putFloat(KEY_MEMORY, snapshot.engineState.memory.toFloat())
            .putBoolean(KEY_HAS_ERROR, snapshot.engineState.hasError)
            .putBoolean(KEY_JUST_EVAL, snapshot.engineState.justEvaluated)
            .putString(KEY_HISTORY_JSON, historyToJson(snapshot.history))
            .apply()
    }

    companion object {
        private const val PREFS_FILE = "kkc_tracker"
        private const val KEY_OPEN = "calculator_open"
        private const val KEY_MODE = "calculator_mode"
        private const val KEY_MODAL_X = "calculator_modal_x_dp"
        private const val KEY_MODAL_Y = "calculator_modal_y_dp"
        private const val KEY_MODAL_WIDTH = "calculator_modal_width_dp"
        private const val KEY_MODAL_HEIGHT = "calculator_modal_height_dp"
        private const val KEY_EXPR = "calculator_expr"
        private const val KEY_DISPLAY = "calculator_display"
        private const val KEY_MEMORY = "calculator_memory"
        private const val KEY_HAS_ERROR = "calculator_has_error"
        private const val KEY_JUST_EVAL = "calculator_just_eval"
        private const val KEY_HISTORY_JSON = "calculator_history_json"
        private const val MAX_HISTORY_ITEMS = 60

        fun create(context: Context): CalculatorOverlayState {
            return CalculatorOverlayState(
                context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
            )
        }

        private fun loadCalculatorSnapshot(prefs: SharedPreferences): CalculatorSnapshot {
            val mode = prefs.getString(KEY_MODE, null)
                ?.let { runCatching { CalculatorMode.valueOf(it) }.getOrNull() }
                ?: CalculatorMode.MODAL
            return CalculatorSnapshot(
                isOpen = prefs.getBoolean(KEY_OPEN, false),
                mode = mode,
                modalX = prefs.getFloat(KEY_MODAL_X, 24f),
                modalY = prefs.getFloat(KEY_MODAL_Y, 24f),
                modalWidth = prefs.getFloat(KEY_MODAL_WIDTH, 360f),
                modalHeight = prefs.getFloat(KEY_MODAL_HEIGHT, 480f),
                engineState = CalculatorEngineState(
                    expression = prefs.getString(KEY_EXPR, "0") ?: "0",
                    display = prefs.getString(KEY_DISPLAY, "0") ?: "0",
                    memory = prefs.getFloat(KEY_MEMORY, 0f).toDouble(),
                    hasError = prefs.getBoolean(KEY_HAS_ERROR, false),
                    justEvaluated = prefs.getBoolean(KEY_JUST_EVAL, false)
                ),
                history = historyFromJson(prefs.getString(KEY_HISTORY_JSON, null))
            )
        }

        private fun clampBounds(
            snapshot: CalculatorSnapshot,
            viewportWidth: Float,
            viewportHeight: Float,
            margin: Float,
            minWidth: Float,
            minHeight: Float
        ): CalculatorSnapshot {
            val maxWidth = (viewportWidth - (margin * 2f)).coerceAtLeast(minWidth)
            val maxHeight = (viewportHeight - (margin * 2f)).coerceAtLeast(minHeight)
            val clampedWidth = snapshot.modalWidth.coerceIn(minWidth, maxWidth)
            val clampedHeight = snapshot.modalHeight.coerceIn(minHeight, maxHeight)
            val maxX = (viewportWidth - clampedWidth - margin).coerceAtLeast(margin)
            val maxY = (viewportHeight - clampedHeight - margin).coerceAtLeast(margin)
            val clampedX = snapshot.modalX.coerceIn(margin, maxX)
            val clampedY = snapshot.modalY.coerceIn(margin, maxY)
            return snapshot.copy(
                modalX = clampedX,
                modalY = clampedY,
                modalWidth = clampedWidth,
                modalHeight = clampedHeight
            )
        }

        private fun historyFromJson(raw: String?): List<CalculatorHistoryEntry> {
            if (raw.isNullOrBlank()) return emptyList()
            return runCatching {
                val arr = JSONArray(raw)
                buildList {
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
                        val expression = obj.optString("expression", "").trim()
                        val result = obj.optString("result", "").trim()
                        val timestampMs = obj.optLong("timestampMs", 0L)
                        if (expression.isNotBlank() && result.isNotBlank()) {
                            add(
                                CalculatorHistoryEntry(
                                    expression = expression,
                                    result = result,
                                    timestampMs = timestampMs
                                )
                            )
                        }
                    }
                }.take(MAX_HISTORY_ITEMS)
            }.getOrElse { emptyList() }
        }

        private fun historyToJson(history: List<CalculatorHistoryEntry>): String {
            val arr = JSONArray()
            history.take(MAX_HISTORY_ITEMS).forEach { item ->
                arr.put(
                    JSONObject()
                        .put("expression", item.expression)
                        .put("result", item.result)
                        .put("timestampMs", item.timestampMs)
                )
            }
            return arr.toString()
        }
    }
}

@Composable
fun rememberCalculatorOverlayState(): CalculatorOverlayState {
    val context = LocalContext.current
    return remember {
        CalculatorOverlayState.create(context)
    }
}

@Composable
fun CalculatorOverlayHost(
    state: CalculatorOverlayState,
    compactWidth: Boolean,
    modifier: Modifier = Modifier
) {
    val snapshot = state.snapshot
    if (!snapshot.isOpen) return
    var showHistory by remember { mutableStateOf(false) }

    val mode = if (compactWidth) CalculatorMode.FULLSCREEN else CalculatorMode.MODAL
    LaunchedEffect(mode) {
        state.setMode(mode)
    }

    BackHandler(enabled = snapshot.isOpen) {
        if (showHistory) showHistory = false else state.setOpen(false)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .zIndex(10f)
    ) {
        if (mode == CalculatorMode.FULLSCREEN) {
            FullscreenCalculator(
                snapshot = snapshot,
                onKeyPress = state::pressKey,
                onShowHistory = { showHistory = true },
                onClose = { state.setOpen(false) }
            )
        } else {
            ResizableModalCalculator(
                stateSnapshot = snapshot,
                onKeyPress = state::pressKey,
                onShowHistory = { showHistory = true },
                onClose = { state.setOpen(false) },
                onSnapshotUpdate = { nextSnapshot, persistNow ->
                    state.updateModalBounds(
                        x = nextSnapshot.modalX,
                        y = nextSnapshot.modalY,
                        width = nextSnapshot.modalWidth,
                        height = nextSnapshot.modalHeight,
                        persistNow = persistNow
                    )
                },
                onDragEnd = {
                    val s = state.snapshot
                    state.updateModalBounds(s.modalX, s.modalY, s.modalWidth, s.modalHeight, persistNow = true)
                },
                onResizeEnd = {
                    val s = state.snapshot
                    state.updateModalBounds(s.modalX, s.modalY, s.modalWidth, s.modalHeight, persistNow = true)
                },
                onClamp = { viewportWidth, viewportHeight, margin, minWidth, minHeight ->
                    state.clampToViewport(viewportWidth, viewportHeight, margin, minWidth, minHeight)
                }
            )
        }

        if (showHistory) {
            CalculatorHistoryDialog(
                history = snapshot.history,
                onDismiss = { showHistory = false },
                onClear = { state.clearHistory() },
                onUseResult = { entry ->
                    state.applyHistoryResult(entry)
                    showHistory = false
                },
                onUseExpression = { entry ->
                    state.applyHistoryExpression(entry)
                    showHistory = false
                }
            )
        }
    }
}

@Composable
private fun FullscreenCalculator(
    snapshot: CalculatorSnapshot,
    onKeyPress: (String) -> Unit,
    onShowHistory: () -> Unit,
    onClose: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
        tonalElevation = 3.dp
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Calculator",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onShowHistory) {
                    Icon(Icons.Filled.History, contentDescription = "Calculator history")
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.Close, contentDescription = "Close calculator")
                }
            }
            CalculatorPanel(
                display = snapshot.engineState.display,
                expression = snapshot.engineState.expression,
                onKeyPress = onKeyPress,
                showResizeHandle = false,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
private fun ResizableModalCalculator(
    stateSnapshot: CalculatorSnapshot,
    onKeyPress: (String) -> Unit,
    onShowHistory: () -> Unit,
    onClose: () -> Unit,
    onSnapshotUpdate: (CalculatorSnapshot, Boolean) -> Unit,
    onDragEnd: () -> Unit,
    onResizeEnd: () -> Unit,
    onClamp: (Float, Float, Float, Float, Float) -> Unit
) {
    val margin = 12f
    val minWidth = 280f
    val minHeight = 340f
    val density = LocalDensity.current.density
    val latestSnapshot by rememberUpdatedState(stateSnapshot)
    val latestSnapshotUpdate by rememberUpdatedState(onSnapshotUpdate)

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(4.dp)
    ) {
        val viewportWidth = maxWidth.value
        val viewportHeight = maxHeight.value

        LaunchedEffect(viewportWidth, viewportHeight, stateSnapshot.modalX, stateSnapshot.modalY, stateSnapshot.modalWidth, stateSnapshot.modalHeight) {
            onClamp(viewportWidth, viewportHeight, margin, minWidth, minHeight)
        }

        val maxWidthDp = (viewportWidth - (margin * 2f)).coerceAtLeast(minWidth)
        val maxHeightDp = (viewportHeight - (margin * 2f)).coerceAtLeast(minHeight)
        val widthDp = stateSnapshot.modalWidth.coerceIn(minWidth, maxWidthDp)
        val heightDp = stateSnapshot.modalHeight.coerceIn(minHeight, maxHeightDp)

        Surface(
            modifier = Modifier
                .offset(stateSnapshot.modalX.dp, stateSnapshot.modalY.dp)
                .width(widthDp.dp)
                .height(heightDp.dp),
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 5.dp,
            shadowElevation = 8.dp
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .pointerInput(viewportWidth, viewportHeight) {
                            var liveX = 0f
                            var liveY = 0f
                            detectDragGestures(
                                onDragStart = {
                                    val s = latestSnapshot
                                    liveX = s.modalX
                                    liveY = s.modalY
                                },
                                onDragEnd = { onDragEnd() }
                            ) { change, dragAmount ->
                                change.consume()
                                liveX = (liveX + dragAmount.x / density).coerceIn(
                                    margin,
                                    (viewportWidth - widthDp - margin).coerceAtLeast(margin)
                                )
                                liveY = (liveY + dragAmount.y / density).coerceIn(
                                    margin,
                                    (viewportHeight - heightDp - margin).coerceAtLeast(margin)
                                )
                                latestSnapshotUpdate(
                                    latestSnapshot.copy(modalX = liveX, modalY = liveY),
                                    false
                                )
                            }
                        }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Calculator",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onShowHistory) {
                        Icon(Icons.Filled.History, contentDescription = "Calculator history")
                    }
                    IconButton(onClick = onClose) {
                        Icon(Icons.Filled.Close, contentDescription = "Close calculator")
                    }
                }

                CalculatorPanel(
                    display = stateSnapshot.engineState.display,
                    expression = stateSnapshot.engineState.expression,
                    onKeyPress = onKeyPress,
                    showResizeHandle = true,
                    modifier = Modifier.fillMaxSize(),
                    initialResizeWidthDp = stateSnapshot.modalWidth,
                    initialResizeHeightDp = stateSnapshot.modalHeight,
                    onResize = { rawWidthDp, rawHeightDp ->
                        val nextWidth = rawWidthDp.coerceIn(minWidth, maxWidthDp)
                        val nextHeight = rawHeightDp.coerceIn(minHeight, maxHeightDp)
                        onSnapshotUpdate(
                            stateSnapshot.copy(modalWidth = nextWidth, modalHeight = nextHeight),
                            false
                        )
                    },
                    onResizeEnd = onResizeEnd
                )
            }
        }
    }
}

@Composable
private fun CalculatorHistoryDialog(
    history: List<CalculatorHistoryEntry>,
    onDismiss: () -> Unit,
    onClear: () -> Unit,
    onUseResult: (CalculatorHistoryEntry) -> Unit,
    onUseExpression: (CalculatorHistoryEntry) -> Unit
) {
    val sections = remember(history) { buildHistorySections(history) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Calculator History") },
        text = {
            if (history.isEmpty()) {
                Text(
                    text = "No calculations yet.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                ) {
                    sections.forEach { section ->
                        item(key = "header_${section.title}") {
                            Text(
                                text = section.title,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 6.dp, bottom = 4.dp)
                            )
                        }
                        itemsIndexed(
                            section.entries,
                            key = { index, item -> "${section.title}_${item.timestampMs}_$index" }
                        ) { index, entry ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 6.dp, horizontal = 2.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = entry.expression,
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                        Text(
                                            text = "= ${entry.result}",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    Text(
                                        text = formatHistoryTime(entry.timestampMs),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End
                                ) {
                                    TextButton(onClick = { onUseExpression(entry) }) {
                                        Text("Use Expr")
                                    }
                                    TextButton(onClick = { onUseResult(entry) }) {
                                        Text("Use Result")
                                    }
                                }
                            }
                            if (index < section.entries.lastIndex) {
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            }
                        }
                        if (section !== sections.lastOrNull()) {
                            item(key = "spacer_${section.title}") {
                                Spacer(Modifier.height(4.dp))
                                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
                                Spacer(Modifier.height(2.dp))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        },
        dismissButton = {
            TextButton(onClick = onClear, enabled = history.isNotEmpty()) {
                Text("Clear")
            }
        }
    )
}

private data class HistorySection(
    val title: String,
    val entries: List<CalculatorHistoryEntry>
)

private fun buildHistorySections(history: List<CalculatorHistoryEntry>): List<HistorySection> {
    if (history.isEmpty()) return emptyList()
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val grouped = history.groupBy { entry ->
        Instant.ofEpochMilli(entry.timestampMs).atZone(zone).toLocalDate()
    }
    return grouped.toList()
        .sortedByDescending { it.first }
        .map { (date, entries) ->
            val title = when (date) {
                today -> "Today"
                today.minusDays(1) -> "Yesterday"
                else -> date.format(HISTORY_DATE_FORMATTER)
            }
            HistorySection(
                title = title,
                entries = entries.sortedByDescending { it.timestampMs }
            )
        }
}

private fun formatHistoryTime(timestampMs: Long): String {
    return Instant.ofEpochMilli(timestampMs)
        .atZone(ZoneId.systemDefault())
        .toLocalTime()
        .format(HISTORY_TIME_FORMATTER)
}

private val HISTORY_DATE_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("EEE, MMM d", Locale.getDefault())

private val HISTORY_TIME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())

@Composable
private fun CalculatorPanel(
    display: String,
    expression: String,
    onKeyPress: (String) -> Unit,
    showResizeHandle: Boolean,
    modifier: Modifier = Modifier,
    initialResizeWidthDp: Float = 0f,
    initialResizeHeightDp: Float = 0f,
    onResize: ((Float, Float) -> Unit)? = null,
    onResizeEnd: (() -> Unit)? = null
) {
    val keys = listOf(
        listOf("MC", "MR", "M+", "M-"),
        listOf("C", "⌫", "÷", "×"),
        listOf("7", "8", "9", "-"),
        listOf("4", "5", "6", "+"),
        listOf("1", "2", "3", "="),
        listOf("0", ".", "", "")
    )
    val latestInitialResizeWidth by rememberUpdatedState(initialResizeWidthDp)
    val latestInitialResizeHeight by rememberUpdatedState(initialResizeHeightDp)

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Surface(
            tonalElevation = 1.dp,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Text(
                    text = expression,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
                Text(
                    text = display,
                    style = MaterialTheme.typography.headlineMedium,
                    textAlign = TextAlign.End,
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 1
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        keys.forEach { rowKeys ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                rowKeys.forEach { key ->
                    if (key.isBlank()) {
                        Spacer(modifier = Modifier.weight(1f))
                    } else {
                        val accent = key in setOf("+", "-", "×", "÷", "=")
                        val memory = key in setOf("MC", "MR", "M+", "M-")
                        Button(
                            onClick = { onKeyPress(key) },
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            shape = RoundedCornerShape(10.dp),
                            colors = when {
                                key == "=" -> ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary
                                )
                                accent -> ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                memory -> ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                                else -> ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        ) {
                            Text(key, style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
        }

        if (showResizeHandle && onResize != null && onResizeEnd != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .pointerInput(Unit) {
                            var liveWidth = 0f
                            var liveHeight = 0f
                            detectDragGestures(
                                onDragStart = {
                                    liveWidth = latestInitialResizeWidth
                                    liveHeight = latestInitialResizeHeight
                                },
                                onDragEnd = { onResizeEnd() }
                            ) { change, dragAmount ->
                                change.consume()
                                val dx = dragAmount.x / density
                                val dy = dragAmount.y / density
                                liveWidth += dx
                                liveHeight += dy
                                onResize(liveWidth, liveHeight)
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "◢",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                Text(
                    text = "Tap X to close",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
