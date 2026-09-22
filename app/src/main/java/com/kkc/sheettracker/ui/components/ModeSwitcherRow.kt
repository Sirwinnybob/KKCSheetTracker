package com.kkc.sheettracker.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.navigation.WorkMode

private fun WorkMode.shortLabel(): String = when (this) {
    WorkMode.CNC -> "CNC"
    WorkMode.HARDWOODS -> "HW"
    WorkMode.ASSEMBLY -> "ASM"
    WorkMode.SPECIALTY -> "SPC"
}

/**
 * Compact toggle-chip row for a TopAppBar `actions` slot, letting the operator switch which
 * WorkMode a Dashboard/Jobs screen is currently showing. Distinct from the larger
 * WorkModeIconTile grid used in Settings.
 */
@Composable
fun ModeSwitcherRow(
    modes: List<WorkMode>,
    selected: WorkMode,
    onSelect: (WorkMode) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        modes.forEach { mode ->
            FilterChip(
                selected = mode == selected,
                onClick = { onSelect(mode) },
                label = { Text(mode.shortLabel()) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    }
}
