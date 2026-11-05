package com.kkc.sheettracker.ui.cnc

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.ui.theme.KKCThemeColors
import com.kkc.sheettracker.ui.theme.contentColorFor
import com.kkc.sheettracker.ui.theme.contrastRatio

private val RED_SCM_ACCENT = Color(0xFFD32F2F)
private val BLUE_SCM_ACCENT = Color(0xFF1976D2)

@Composable
internal fun CncStatusPill(
    pill: CncPill,
    placement: CncPillPlacement = CncPillPlacement.HEADER,
    modifier: Modifier = Modifier,
) {
    val colors = KKCThemeColors.statusColors
    val accent = when (pill.machine) {
        CncPillMachine.RED -> RED_SCM_ACCENT
        CncPillMachine.BLUE -> BLUE_SCM_ACCENT
        null -> when (pill.kind) {
            CncPillKind.CUTTING -> colors.inProgress
            CncPillKind.IDLE -> MaterialTheme.colorScheme.onSurfaceVariant
            CncPillKind.FINISHED -> colors.complete
        }
    }
    val fill = accent.copy(alpha = 0.12f).compositeOver(MaterialTheme.colorScheme.surface)
    val ink = if (contrastRatio(accent, fill) >= 4.5f) accent else contentColorFor(fill)
    val text = when (placement) {
        CncPillPlacement.DASHBOARD -> pill.dashboardText
        CncPillPlacement.HEADER -> pill.headerText
        CncPillPlacement.NAVIGATOR -> pill.navigatorText
    }
    Surface(modifier = modifier, color = fill, contentColor = ink, shape = RoundedCornerShape(6.dp)) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (pill.kind == CncPillKind.CUTTING) {
                Box(Modifier.size(6.dp).testTag("cnc-status-dot").background(accent, CircleShape))
            }
            Text(text, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
    }
}

