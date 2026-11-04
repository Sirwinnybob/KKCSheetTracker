package com.kkc.sheettracker.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.data.cnc.CncOperationWatcher
import com.kkc.sheettracker.data.cnc.OperationNotice
import com.kkc.sheettracker.data.cnc.OperationNoticeTone
import kotlinx.coroutines.delay

private const val MAX_VISIBLE_NOTICES = 3
private const val SUCCESS_NOTICE_MS = 8_000L

/**
 * App-level, non-blocking outcomes of CNC moves and mix changes, drawn over whatever screen is open.
 * Completions time out; failures and anything needing attention stay until dismissed. Only the cards
 * take touches, so the screen underneath stays usable.
 */
@Composable
fun CncOperationNoticeHost(watcher: CncOperationWatcher) {
    val notices by watcher.notices.collectAsState()
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        AnimatedVisibility(notices.isNotEmpty(), enter = fadeIn() + slideInVertically { -it / 2 },
            exit = fadeOut() + slideOutVertically { -it / 2 }) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp).widthIn(max = 640.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                notices.takeLast(MAX_VISIBLE_NOTICES).forEach { notice ->
                    key(notice.id) { OperationNoticeCard(notice) { watcher.dismiss(notice.id) } }
                }
                val hidden = notices.size - MAX_VISIBLE_NOTICES
                if (hidden > 0) Text("$hidden more CNC update${if (hidden == 1) "" else "s"}",
                    style = MaterialTheme.typography.labelMedium, modifier = Modifier.align(Alignment.CenterHorizontally))
            }
        }
    }
}

@Composable
private fun OperationNoticeCard(notice: OperationNotice, onDismiss: () -> Unit) {
    if (!notice.sticky) LaunchedEffect(notice.id) { delay(SUCCESS_NOTICE_MS); onDismiss() }
    val colors = MaterialTheme.colorScheme
    val (container, content) = when (notice.tone) {
        OperationNoticeTone.SUCCESS -> colors.inverseSurface to colors.inverseOnSurface
        OperationNoticeTone.FAILURE -> colors.surfaceVariant to colors.onSurfaceVariant
        OperationNoticeTone.ATTENTION -> colors.errorContainer to colors.onErrorContainer
    }
    // Opaque fill: a shadow under a translucent surface bleeds through (see CLAUDE.md).
    Surface(color = container, contentColor = content, shape = RoundedCornerShape(16.dp), shadowElevation = 6.dp,
        modifier = Modifier.fillMaxWidth().testTag("cnc-operation-notice")) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(listOf(notice.machine, notice.job, notice.material).joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(notice.outcome, style = MaterialTheme.typography.bodyMedium,
                    color = if (notice.tone == OperationNoticeTone.FAILURE) colors.error else content)
            }
            TextButton(onClick = onDismiss) { Text("Dismiss", color = content) }
        }
    }
}
