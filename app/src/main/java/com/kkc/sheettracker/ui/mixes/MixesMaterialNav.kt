package com.kkc.sheettracker.ui.mixes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.ui.settings.GroupCard
import com.kkc.sheettracker.ui.settings.GroupCaption
import com.kkc.sheettracker.ui.theme.LocalKKCStatusColors

internal fun useRail(widthDp: Int): Boolean = widthDp >= 1000
internal fun stripColumns(widthDp: Int): Int = (widthDp / 240).coerceAtLeast(1)
internal fun selectAvailableMaterial(current: String?, materials: List<String>): String? =
    if (materials.isEmpty() || current in materials) current else materials.first()

@Composable
internal fun MixesMaterialNav(items: List<MaterialNavItem>, rail: Boolean, widthDp: Int, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    GroupCard(modifier = modifier.testTag(if (rail) "mixes-material-rail" else "mixes-material-strip")) {
        GroupCaption("SCM CNC", Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
        Column(Modifier.padding(horizontal = 6.dp).heightIn(max = if (rail) 1000.dp else 240.dp).verticalScroll(rememberScrollState())) {
            val columns = if (rail) 1 else stripColumns(widthDp)
            items.chunked(columns).forEach { chunk ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    chunk.forEach { item -> MaterialTile(item, onSelect, Modifier.weight(1f)) }
                    repeat(columns - chunk.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun MaterialTile(item: MaterialNavItem, onSelect: (String) -> Unit, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    val status = LocalKKCStatusColors.current
    val (label, tone) = when (val chip = item.chip) {
        is MaterialChip.Left -> "${chip.n} left" to if (chip.interrupted) status.skip else scheme.primary
        MaterialChip.Done -> "Done" to status.complete
        MaterialChip.NoMix -> "No mix" to status.bad
        MaterialChip.NoPgms -> "No PGMs" to scheme.onSurfaceVariant
        MaterialChip.Offline -> "Offline" to scheme.onSurfaceVariant
    }
    Column(modifier.padding(bottom = 4.dp).clip(RoundedCornerShape(9.dp))
        .background(if (item.selected) scheme.secondaryContainer else scheme.surface)
        .selectable(item.selected, role = Role.Tab, onClick = { onSelect(item.material) })
        .heightIn(min = 64.dp).padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(item.material, style = MaterialTheme.typography.bodyMedium, fontWeight = if (item.selected) FontWeight.SemiBold else FontWeight.Normal)
        Text(label, style = MaterialTheme.typography.labelMedium, color = tone)
    }
}
