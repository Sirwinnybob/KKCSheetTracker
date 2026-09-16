package com.kkc.sheettracker.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.ui.components.LocalLowEndMode
import com.kkc.sheettracker.ui.theme.KKCThemeColors
import com.kkc.sheettracker.ui.theme.LocalKKCIsDarkTheme
import com.kkc.sheettracker.ui.theme.LocalKKCThemeTokens
import com.kkc.sheettracker.ui.theme.boldGradientBrush

object DashboardSurfaceDefaults {
    val heroShape: Shape
        @Composable get() = RoundedCornerShape(LocalKKCThemeTokens.current.shape.largeDp.dp)

    val sectionShape: Shape
        @Composable get() = RoundedCornerShape(LocalKKCThemeTokens.current.shape.mediumDp.dp)

    val chipShape: Shape
        @Composable get() = RoundedCornerShape(11.dp)

    @Composable
    fun containerColor(accent: DashboardAccent): Color {
        return MaterialTheme.colorScheme.surface.copy(alpha = LocalKKCThemeTokens.current.surface.cardAlpha)
    }

    @Composable
    fun accentWash(accent: DashboardAccent): Color {
        val status = KKCThemeColors.statusColors
        return when (accent) {
            DashboardAccent.NEUTRAL -> status.notStarted.copy(alpha = 0.18f)
            DashboardAccent.INFO -> status.inProgress.copy(alpha = 0.22f)
            DashboardAccent.SUCCESS -> status.complete.copy(alpha = 0.24f)
            DashboardAccent.WARNING -> status.skip.copy(alpha = 0.20f)
            DashboardAccent.DANGER -> status.bad.copy(alpha = 0.16f)
        }
    }

    @Composable
    fun outlineColor(accent: DashboardAccent): Color {
        val status = KKCThemeColors.statusColors
        val base = when (accent) {
            DashboardAccent.NEUTRAL -> status.notStarted.copy(alpha = 0.4f)
            DashboardAccent.INFO -> status.inProgress.copy(alpha = 0.22f)
            DashboardAccent.SUCCESS -> status.complete.copy(alpha = 0.18f)
            DashboardAccent.WARNING -> status.skip.copy(alpha = 0.2f)
            DashboardAccent.DANGER -> status.bad.copy(alpha = 0.2f)
        }
        // Scales each branch's own alpha by 0.9 rather than flattening it; sole caller overrides via its own .copy(alpha=...) anyway.
        return base.copy(alpha = base.alpha * 0.9f)
    }

    @Composable
    fun accentColor(accent: DashboardAccent): Color {
        val status = KKCThemeColors.statusColors
        return when (accent) {
            DashboardAccent.NEUTRAL -> status.notStarted
            DashboardAccent.INFO -> status.inProgress
            DashboardAccent.SUCCESS -> status.complete
            DashboardAccent.WARNING -> status.skip
            DashboardAccent.DANGER -> status.bad
        }
    }
}

@Composable
fun DashboardSurfaceCard(
    modifier: Modifier = Modifier,
    accent: DashboardAccent = DashboardAccent.NEUTRAL,
    shape: Shape = DashboardSurfaceDefaults.sectionShape,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
    // Tints the card background with the accent color instead of plain surface —
    // opt-in so existing call sites (which pass accent purely for badge/text color) don't change.
    tinted: Boolean = false,
    // Overrides the tint source color when tinted=true — lets callers with a more precise
    // palette than the 5-bucket DashboardAccent (e.g. supply's per-status colors) tint exactly.
    tintOverride: Color? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val lowEnd = LocalLowEndMode.current
    val containerColor = if (tinted) {
        val wash = tintOverride?.copy(alpha = 0.14f) ?: DashboardSurfaceDefaults.accentWash(accent)
        wash.compositeOver(MaterialTheme.colorScheme.surface)
    } else {
        DashboardSurfaceDefaults.containerColor(accent)
    }
    Card(
        modifier = modifier
            .fillMaxWidth()
            .shadow(elevation = if (lowEnd.shadowsDisabled) 0.dp else 3.dp, shape = shape, clip = false)
            .clip(shape)
            .then(
                if (lowEnd.shadowsDisabled) Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
                else Modifier
            ),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(
            defaultElevation = 0.dp,
            pressedElevation = 1.dp
        )
    ) {
        Column(
            modifier = Modifier.padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content
        )
    }
}

@Composable
fun DashboardHeroSurface(
    modifier: Modifier = Modifier,
    accent: DashboardAccent = DashboardAccent.INFO,
    content: @Composable ColumnScope.() -> Unit
) {
    val tokens = LocalKKCThemeTokens.current
    if (tokens.boldMode) {
        val lowEnd = LocalLowEndMode.current
        val shape = DashboardSurfaceDefaults.heroShape
        val palette = tokens.palette(LocalKKCIsDarkTheme.current)
        Box(
            modifier = modifier
                .fillMaxWidth()
                .shadow(elevation = if (lowEnd.shadowsDisabled) 0.dp else 3.dp, shape = shape, clip = false)
                .clip(shape)
                .background(boldGradientBrush(palette))
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                content = content
            )
        }
    } else {
        DashboardSurfaceCard(
            modifier = modifier,
            accent = accent,
            shape = DashboardSurfaceDefaults.heroShape,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
            content = content
        )
    }
}

@Composable
fun DashboardSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null
) {
    val tokens = LocalKKCThemeTokens.current
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (tokens.boldMode) {
            val palette = tokens.palette(LocalKKCIsDarkTheme.current)
            Box(
                modifier = Modifier
                    .width(36.dp)
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(boldGradientBrush(palette))
            )
            Spacer(modifier = Modifier.height(2.dp))
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        if (!subtitle.isNullOrBlank()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun DashboardAccentPill(
    text: String,
    accent: DashboardAccent,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(DashboardSurfaceDefaults.chipShape)
            .background(DashboardSurfaceDefaults.accentWash(accent))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = DashboardSurfaceDefaults.accentColor(accent)
        )
    }
}

@Composable
fun DashboardStatRowSurface(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        content()
    }
}
