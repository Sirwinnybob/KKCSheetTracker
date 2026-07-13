package com.kkc.sheettracker.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Large, tablet-friendly segmented control to toggle between Production Order and Name sort.
 * Designed for shop-floor use: tall tap targets, high-contrast labels, clear active state.
 */
@Composable
fun SortToggleBar(
    sortByName: Boolean,
    onSortChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val activeColor = MaterialTheme.colorScheme.primary
    val activeTextColor = MaterialTheme.colorScheme.onPrimary
    val inactiveTextColor = MaterialTheme.colorScheme.onSurfaceVariant

    Surface(
        shape = RoundedCornerShape(9.dp),
        color = trackColor,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .height(48.dp)
    ) {
        Row(modifier = Modifier.padding(4.dp)) {
            SortSegment(
                label = "⬇ Production Order",
                isActive = !sortByName,
                activeColor = activeColor,
                activeTextColor = activeTextColor,
                inactiveTextColor = inactiveTextColor,
                modifier = Modifier.weight(1f),
                onClick = { onSortChange(false) }
            )
            SortSegment(
                label = "A–Z  Name",
                isActive = sortByName,
                activeColor = activeColor,
                activeTextColor = activeTextColor,
                inactiveTextColor = inactiveTextColor,
                modifier = Modifier.weight(1f),
                onClick = { onSortChange(true) }
            )
        }
    }
}

@Composable
private fun SortSegment(
    label: String,
    isActive: Boolean,
    activeColor: Color,
    activeTextColor: Color,
    inactiveTextColor: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val bgColor by animateColorAsState(
        targetValue = if (isActive) activeColor else Color.Transparent,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "sort_segment_bg"
    )
    val textColor by animateColorAsState(
        targetValue = if (isActive) activeTextColor else inactiveTextColor,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "sort_segment_text"
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .clip(RoundedCornerShape(7.dp))
            .background(bgColor)
            .clickable(onClick = onClick)
    ) {
        Text(
            text = label,
            color = textColor,
            fontSize = 13.sp,
            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
            letterSpacing = 0.2.sp
        )
    }
}
