package com.kkc.sheettracker.ui.settings

import androidx.compose.ui.graphics.vector.ImageVector
import com.kkc.sheettracker.ui.components.icons.SettingsAdminSelected
import com.kkc.sheettracker.ui.components.icons.SettingsAdminUnselected
import com.kkc.sheettracker.ui.components.icons.SettingsLookFeelSelected
import com.kkc.sheettracker.ui.components.icons.SettingsLookFeelUnselected
import com.kkc.sheettracker.ui.components.icons.SettingsMeSelected
import com.kkc.sheettracker.ui.components.icons.SettingsMeUnselected
import com.kkc.sheettracker.ui.components.icons.SettingsPerformanceSelected
import com.kkc.sheettracker.ui.components.icons.SettingsPerformanceUnselected
import com.kkc.sheettracker.ui.components.icons.SettingsSyncNetworkSelected
import com.kkc.sheettracker.ui.components.icons.SettingsSyncNetworkUnselected
import com.kkc.sheettracker.ui.components.icons.SettingsTabletDataSelected
import com.kkc.sheettracker.ui.components.icons.SettingsTabletDataUnselected
import com.kkc.sheettracker.ui.components.icons.SettingsUpdatesSelected
import com.kkc.sheettracker.ui.components.icons.SettingsUpdatesUnselected
import com.kkc.sheettracker.ui.components.icons.SettingsViewersSelected
import com.kkc.sheettracker.ui.components.icons.SettingsViewersUnselected

internal fun SettingsSection.icon(selected: Boolean): ImageVector = when (this) {
    SettingsSection.LOOK_AND_FEEL -> if (selected) SettingsLookFeelSelected else SettingsLookFeelUnselected
    SettingsSection.VIEWERS -> if (selected) SettingsViewersSelected else SettingsViewersUnselected
    SettingsSection.ME -> if (selected) SettingsMeSelected else SettingsMeUnselected
    SettingsSection.UPDATES_ABOUT -> if (selected) SettingsUpdatesSelected else SettingsUpdatesUnselected
    SettingsSection.TABLET_DATA -> if (selected) SettingsTabletDataSelected else SettingsTabletDataUnselected
    SettingsSection.SYNC_NETWORK -> if (selected) SettingsSyncNetworkSelected else SettingsSyncNetworkUnselected
    SettingsSection.PERFORMANCE_POWER -> if (selected) SettingsPerformanceSelected else SettingsPerformanceUnselected
    SettingsSection.ADMIN -> if (selected) SettingsAdminSelected else SettingsAdminUnselected
}
