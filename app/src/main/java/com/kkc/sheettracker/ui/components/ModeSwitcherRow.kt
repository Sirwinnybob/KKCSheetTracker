package com.kkc.sheettracker.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.navigation.WorkMode
import com.kkc.sheettracker.navigation.shortLabel

/**
 * Compact sliding-pill switcher for a TopAppBar `actions` slot, letting the operator switch which
 * WorkMode a Dashboard/Jobs screen is currently showing. Shares [KKCSlidingPillRow] with the
 * hardwoods doc controls, so it follows the active theme. Distinct from the larger
 * WorkModeIconTile grid used in Settings.
 */
@Composable
fun ModeSwitcherRow(
    modes: List<WorkMode>,
    selected: WorkMode,
    onSelect: (WorkMode) -> Unit,
    modifier: Modifier = Modifier,
    persistKey: String? = null
) {
    val options = remember(modes, selected, onSelect) {
        modes.map { mode ->
            KKCPillOption(
                label = mode.shortLabel(),
                isSelected = mode == selected,
                onClick = { onSelect(mode) }
            )
        }
    }
    KKCSlidingPillRow(
        options = options,
        modifier = modifier.kkcTopBarItem("mode-switcher").padding(end = 4.dp),
        persistKey = persistKey
    )
}
