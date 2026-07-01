package com.kkc.sheettracker.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import com.kkc.sheettracker.ui.theme.LocalKKCThemeTokens

object DashboardSurfaceDefaults {
    val heroShape: Shape
        @Composable get() = RoundedCornerShape(LocalKKCThemeTokens.current.shape.largeDp.dp)

    val sectionShape: Shape
        @Composable get() = RoundedCornerShape(LocalKKCThemeTokens.current.shape.mediumDp.dp)

    val chipShape: Shape
        @Composable get() = RoundedCornerShape(14.dp)

    @Composable
    fun containerColor(accent: DashboardAccent): Color {
        return MaterialTheme.colorScheme.surface.copy(alpha = LocalKKCThemeTokens.current.surface.cardAlpha)
    }

    @Composable
    fun accentWash(accent: DashboardAccent): Color {
        val scheme = MaterialTheme.colorScheme
        return when (accent) {
            DashboardAccent.NEUTRAL -> scheme.surfaceVariant.copy(alpha = 0.18f)
            DashboardAccent.INFO -> scheme.primaryContainer.copy(alpha = 0.22f)
            DashboardAccent.SUCCESS -> scheme.secondaryContainer.copy(alpha = 0.24f)
            DashboardAccent.WARNING -> scheme.tertiaryContainer.copy(alpha = 0.20f)
            DashboardAccent.DANGER -> scheme.errorContainer.copy(alpha = 0.16f)
        }
    }

    @Composable
    fun outlineColor(accent: DashboardAccent): Color {
        val scheme = MaterialTheme.colorScheme
        val base = when (accent) {
            DashboardAccent.NEUTRAL -> scheme.outlineVariant
            DashboardAccent.INFO -> scheme.primary.copy(alpha = 0.22f)
            DashboardAccent.SUCCESS -> scheme.primary.copy(alpha = 0.18f)
            DashboardAccent.WARNING -> scheme.tertiary.copy(alpha = 0.2f)
            DashboardAccent.DANGER -> scheme.error.copy(alpha = 0.2f)
        }
        return base.copy(alpha = 0.9f)
    }

    @Composable
    fun accentColor(accent: DashboardAccent): Color = when (accent) {
        DashboardAccent.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
        DashboardAccent.INFO -> MaterialTheme.colorScheme.primary
        DashboardAccent.SUCCESS -> MaterialTheme.colorScheme.primary
        DashboardAccent.WARNING -> MaterialTheme.colorScheme.tertiary
        DashboardAccent.DANGER -> MaterialTheme.colorScheme.error
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
    val containerColor = if (tinted) {
        val wash = tintOverride?.copy(alpha = 0.14f) ?: DashboardSurfaceDefaults.accentWash(accent)
        wash.compositeOver(MaterialTheme.colorScheme.surface)
    } else {
        DashboardSurfaceDefaults.containerColor(accent)
    }
    Card(
        modifier = modifier
            .fillMaxWidth()
            .shadow(elevation = 3.dp, shape = shape, clip = false),
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
    DashboardSurfaceCard(
        modifier = modifier,
        accent = accent,
        shape = DashboardSurfaceDefaults.heroShape,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        content = content
    )
}

@Composable
fun DashboardSectionHeader(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
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
