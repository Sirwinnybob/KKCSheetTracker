package com.kkc.sheettracker.ui.mixes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.ui.components.KKCPillStyle
import com.kkc.sheettracker.ui.components.LocalLowEndMode
import com.kkc.sheettracker.ui.components.kkcPillIndicator
import com.kkc.sheettracker.ui.components.rememberKKCPillStyle

/*
 * Mixes-screen pieces built on the app's sliding-pill styling (KKCSlidingPill.kt), so the screen follows
 * the active theme the same way the job detail action row and the sliders do. These exist because the
 * shared KKCPillActionRow can't anchor a dropdown per button or carry drag gestures.
 */

private val TrackShape = RoundedCornerShape(9.dp)
private val PillShape = RoundedCornerShape(6.dp)

/**
 * The pill track ([com.kkc.sheettracker.ui.components.KKCPillContainer] look) drawn without clipping its
 * content, so a material chip can be dragged out of it onto another machine.
 */
@Composable
internal fun MixesPillTrack(modifier: Modifier = Modifier, style: KKCPillStyle = rememberKKCPillStyle(),
    content: @Composable () -> Unit) {
    val shadows = !LocalLowEndMode.current.shadowsDisabled
    Box(modifier
        .then(if (shadows) Modifier.shadow(3.5.dp, TrackShape, clip = false) else Modifier)
        .background(style.container, TrackShape)
        .border(1.dp, style.containerBorder, TrackShape)
        .padding(4.dp)) { content() }
}

/** An always-filled pill, like the job detail action buttons; sits in a [MixesPillTrack]. */
@Composable
internal fun MixesPillButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null,
    enabled: Boolean = true, style: KKCPillStyle = rememberKKCPillStyle(), trailingIcon: ImageVector? = null) {
    Row(modifier.height(40.dp).alpha(if (enabled) 1f else 0.45f).kkcPillIndicator(style).clip(PillShape)
        .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        icon?.let { Icon(it, null, Modifier.size(20.dp), tint = style.selectedText) }
        Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = style.selectedText, maxLines = 1)
        trailingIcon?.let { Icon(it, null, Modifier.size(18.dp), tint = style.selectedText) }
    }
}
