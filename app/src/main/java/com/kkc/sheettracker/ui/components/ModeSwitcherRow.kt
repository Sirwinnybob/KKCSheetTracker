package com.kkc.sheettracker.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.navigation.WorkMode
import com.kkc.sheettracker.navigation.shortLabel
import com.kkc.sheettracker.ui.components.icons.HardwoodsPlankIcon
import com.kkc.sheettracker.ui.components.icons.HardwoodsSpecialtyIcon
import com.kkc.sheettracker.ui.components.icons.ReferenceAssemblyIcon
import com.kkc.sheettracker.ui.components.icons.StationCncIcon

/** Each mode shows its station icon (same as the Specialty station headers). */
internal fun WorkMode.modeIcon(): ImageVector = when (this) {
    WorkMode.CNC -> StationCncIcon
    WorkMode.HARDWOODS -> HardwoodsPlankIcon
    WorkMode.ASSEMBLY -> ReferenceAssemblyIcon
    WorkMode.SPECIALTY -> HardwoodsSpecialtyIcon
}

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
                onClick = { onSelect(mode) },
                icon = mode.modeIcon()
            )
        }
    }
    KKCSlidingPillRow(
        options = options,
        modifier = modifier.kkcTopBarItem("mode-switcher").padding(end = 4.dp),
        persistKey = persistKey
    )
}
