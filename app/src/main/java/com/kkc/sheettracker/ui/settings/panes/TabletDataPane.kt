package com.kkc.sheettracker.ui.settings.panes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.ui.settings.CardBody
import com.kkc.sheettracker.ui.settings.GroupCard
import com.kkc.sheettracker.ui.settings.SaveableField

@Composable
internal fun TabletDataPane(
    tabletId: String,
    onTabletIdChanged: (String) -> Unit,
    basePath: String,
    onBasePathChanged: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        GroupCard(caption = "Tablet") {
            CardBody {
                SaveableField(
                    label = "Tablet ID",
                    savedValue = tabletId,
                    onSave = onTabletIdChanged,
                    supportingText = "Used for progress file naming. Must be unique per tablet.",
                    saveLabel = "Save Tablet ID"
                )
            }
        }
        GroupCard(caption = "Data source") {
            CardBody {
                SaveableField(
                    label = "Ready Jobs Folder Path",
                    savedValue = basePath,
                    onSave = onBasePathChanged,
                    supportingText = "Path to the synced Ready Jobs folder on this tablet.",
                    saveLabel = "Save Path (app will restart)"
                )
            }
        }
    }
}
