package com.kkc.sheettracker.ui.settings.panes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.data.UiPreferencesStore
import com.kkc.sheettracker.ui.settings.GroupCard
import com.kkc.sheettracker.ui.settings.GroupDivider
import com.kkc.sheettracker.ui.settings.SettingNavRow
import com.kkc.sheettracker.ui.settings.SettingToggle

@Composable
internal fun ViewersPane(
    isDarkTheme: Boolean,
    useStandardSheets: Boolean,
    onUseStandardSheetsChanged: (Boolean) -> Unit,
    continuousScrollDefault: Boolean,
    onContinuousScrollDefaultChanged: (Boolean) -> Unit,
    uiPreferencesStore: UiPreferencesStore,
    onOpenAssemblyViewerDefaults: () -> Unit,
    onOpenSpecialtyViewerDefaults: () -> Unit,
) {
    var scrollPreviewLabelOnly by remember { mutableStateOf(uiPreferencesStore.getScrollPreviewLabelOnly()) }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        GroupCard(caption = "Sheets") {
            if (isDarkTheme) {
                SettingToggle(
                    label = "Use standard sheets",
                    checked = useStandardSheets,
                    onCheckedChange = onUseStandardSheetsChanged,
                    subtitle = "Load light mode PDFs instead of dark mode in viewer pages."
                )
                GroupDivider()
            }
            SettingToggle(
                label = "Continuous scroll",
                checked = continuousScrollDefault,
                onCheckedChange = onContinuousScrollDefaultChanged,
                subtitle = "Scroll reference PDFs page-to-page instead of tapping through them."
            )
            GroupDivider()
            SettingToggle(
                label = "Label-only scroll preview",
                checked = scrollPreviewLabelOnly,
                onCheckedChange = {
                    scrollPreviewLabelOnly = it
                    uiPreferencesStore.setScrollPreviewLabelOnly(it)
                },
                subtitle = "Show just the sheet label while dragging the scrollbar, instead of page thumbnails."
            )
        }
        GroupCard(caption = "Viewer defaults") {
            SettingNavRow("Assembly viewer defaults", "Layout, panes, fullscreen", onOpenAssemblyViewerDefaults)
            GroupDivider()
            SettingNavRow("Specialty viewer defaults", "Station order, expanded sections", onOpenSpecialtyViewerDefaults)
        }
    }
}
