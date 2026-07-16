package com.kkc.sheettracker.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.runtime.getValue
import com.kkc.sheettracker.ui.theme.KKCThemeColors

enum class ProgressState {
    NOT_STARTED,
    IN_PROGRESS,
    COMPLETE,
    SKIPPED;

    companion object {
        fun from(done: Int, total: Int): ProgressState {
            if (total <= 0) return NOT_STARTED
            val safeDone = done.coerceAtLeast(0)
            return when {
                safeDone <= 0 -> NOT_STARTED
                safeDone >= total -> COMPLETE
                else -> IN_PROGRESS
            }
        }
    }
}

@Composable
fun ProgressPill(
    done: Int,
    total: Int,
    modifier: Modifier = Modifier,
    state: ProgressState = ProgressState.from(done, total),
    showCheckOnComplete: Boolean = true,
    skippedFillColor: Color? = null
) {
    val colors = KKCThemeColors.statusColors
    val safeTotal = total.coerceAtLeast(0)
    val safeDone = done.coerceAtLeast(0).coerceAtMost(if (safeTotal > 0) safeTotal else done.coerceAtLeast(0))
    val fraction = when {
        state == ProgressState.SKIPPED -> 1f
        safeTotal <= 0 -> 0f
        else -> (safeDone.toFloat() / safeTotal.toFloat()).coerceIn(0f, 1f)
    }
    val fillColor = when (state) {
        ProgressState.NOT_STARTED -> Color.Transparent
        ProgressState.IN_PROGRESS -> colors.inProgressBorder
        ProgressState.COMPLETE -> colors.completeBorder
        ProgressState.SKIPPED -> skippedFillColor ?: colors.skipBorder
    }
    // Solid, not semi-transparent: Modifier.shadow() + a translucent background lets the shadow
    // bleed through as a dark ring (see CLAUDE.md "Frosted Glass Buttons"). Pre-composite onto the
    // surface color so the pill keeps the same look but the compositor never sees an alpha < 1
    // fill to bleed through.
    val trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        .compositeOver(MaterialTheme.colorScheme.surface)
    val shape = RoundedCornerShape(9.dp)
    val label = "$safeDone/$safeTotal"
    val textColor = when {
        state == ProgressState.COMPLETE && showCheckOnComplete -> Color.White
        fraction >= 0.6f && state != ProgressState.NOT_STARTED -> Color.White
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Box(
        modifier = modifier
            .height(22.dp)
            .width(80.dp)
            .shadow(elevation = 2.dp, shape = shape, clip = false)
            .clip(shape)
            .background(trackColor)
            .semantics {
                contentDescription = if (state == ProgressState.COMPLETE) {
                    "$safeDone of $safeTotal complete"
                } else {
                    "$safeDone of $safeTotal done"
                }
            }
    ) {
        val animatedFraction by animateFloatAsState(
            targetValue = fraction,
            animationSpec = spring(
                stiffness = Spring.StiffnessLow,
                dampingRatio = Spring.DampingRatioNoBouncy
            ),
            label = "progressPillFraction"
        )
        Box(
            modifier = Modifier
                .fillMaxWidth(animatedFraction)
                .fillMaxHeight()
                .background(fillColor)
        )

        if (state == ProgressState.COMPLETE && showCheckOnComplete) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "Check icon",
                tint = Color.White,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(14.dp)
            )
        } else {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = textColor,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 6.dp)
            )
        }
    }
}
