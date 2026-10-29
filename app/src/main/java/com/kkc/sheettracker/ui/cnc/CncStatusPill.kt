package com.kkc.sheettracker.ui.cnc

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.ui.components.LocalLowEndMode
import com.kkc.sheettracker.ui.theme.KKCThemeColors
import com.kkc.sheettracker.ui.theme.contentColorFor
import com.kkc.sheettracker.ui.theme.contrastRatio

@Composable
internal fun CncStatusPill(
    pill: CncPill,
    placement: CncPillPlacement = CncPillPlacement.HEADER,
    modifier: Modifier = Modifier,
) {
    val colors = KKCThemeColors.statusColors
    val accent = when (pill.kind) {
        CncPillKind.CUTTING -> colors.inProgress
        CncPillKind.IDLE -> MaterialTheme.colorScheme.onSurfaceVariant
        CncPillKind.FINISHED -> colors.complete
    }
    val fill = accent.copy(alpha = 0.12f).compositeOver(MaterialTheme.colorScheme.surface)
    val ink = if (contrastRatio(accent, fill) >= 4.5f) accent else contentColorFor(fill)
    val text = when (placement) {
        CncPillPlacement.DASHBOARD -> pill.dashboardText
        CncPillPlacement.HEADER -> pill.headerText
        CncPillPlacement.NAVIGATOR -> pill.navigatorText
    }
    val alpha = if (pill.kind == CncPillKind.CUTTING && !LocalLowEndMode.current.animationsDisabled) {
        val transition = rememberInfiniteTransition(label = "cncCutting")
        val animated by transition.animateFloat(0.4f, 1f,
            infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "cncDot")
        animated
    } else 1f
    Surface(modifier = modifier, color = fill, contentColor = ink, shape = RoundedCornerShape(6.dp)) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (pill.kind == CncPillKind.CUTTING) {
                Box(Modifier.size(6.dp).testTag("cnc-status-dot").background(accent.copy(alpha = alpha), CircleShape))
            }
            Text(text, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
    }
}

