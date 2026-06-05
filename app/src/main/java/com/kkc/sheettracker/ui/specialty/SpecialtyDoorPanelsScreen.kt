package com.kkc.sheettracker.ui.specialty

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.ui.theme.KKCSpacing
import com.kkc.sheettracker.data.DoorCutUnitTypeMetadata
import com.kkc.sheettracker.data.HardwoodsRepository
import com.kkc.sheettracker.data.filterDoorCutRowsToSheets as sharedFilterDoorCutRowsToSheets
import com.kkc.sheettracker.data.loadHardwoodsCutlistIndexRawJson as sharedLoadHardwoodsCutlistIndexRawJson
import com.kkc.sheettracker.data.parseDoorCutUnitTypeMetadata as sharedParseDoorCutUnitTypeMetadata
import com.kkc.sheettracker.data.models.HardwoodCutlistIndex
import com.kkc.sheettracker.data.models.HardwoodCutlistRow
import com.kkc.sheettracker.data.models.HardwoodDocType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpecialtyDoorPanelsScreen(
    jobFolderName: String,
    hardwoodsRepository: HardwoodsRepository,
    onBack: () -> Unit
) {
    val loadState by produceState(
        initialValue = DoorPanelsLoadState(isLoading = true),
        key1 = jobFolderName,
        key2 = hardwoodsRepository
    ) {
        value = DoorPanelsLoadState(isLoading = true)
        value = withContext(Dispatchers.IO) {
            runCatching {
                val hardwoodIndex = hardwoodsRepository.loadHardwoodsIndex(jobFolderName)
                val rawCutlistIndexJson = loadHardwoodsCutlistIndexRawJson(
                    basePath = hardwoodsRepository.currentBasePath(),
                    jobFolderName = jobFolderName
                )
                DoorPanelsLoadState(
                    isLoading = false,
                    viewModel = buildDoorPanelsViewModel(
                        hardwoodIndex = hardwoodIndex,
                        rawCutlistIndexJson = rawCutlistIndexJson
                    )
                )
            }.getOrElse {
                DoorPanelsLoadState(
                    isLoading = false,
                    viewModel = DoorPanelsViewModel(),
                    errorMessage = "Unable to load door panel data."
                )
            }
        }
    }
    val viewModel = loadState.viewModel ?: DoorPanelsViewModel()
    var selectedOption by rememberSaveable(jobFolderName) {
        mutableStateOf<DoorPanelsDocOption?>(viewModel.options.firstOrNull())
    }
    if (selectedOption !in viewModel.options) {
        selectedOption = viewModel.options.firstOrNull()
    }
    val selectedRows = selectedOption?.let { option -> viewModel.rowsByOption[option].orEmpty() }.orEmpty()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Door Panels") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = KKCSpacing.screenHorizontal),
            contentPadding = PaddingValues(vertical = KKCSpacing.listContentVertical),
            verticalArrangement = Arrangement.spacedBy(KKCSpacing.m)
        ) {
            item(key = "job-title") {
                Text(jobFolderName, style = MaterialTheme.typography.titleMedium)
            }

            if (loadState.isLoading) {
                item(key = "loading") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(KKCSpacing.inCardSpacing),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(modifier = Modifier.padding(KKCSpacing.xxs))
                        Text(
                            "Loading door panel data...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else if (!loadState.errorMessage.isNullOrBlank()) {
                item(key = "error") {
                    Text(
                        text = loadState.errorMessage.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            if (!loadState.isLoading && viewModel.options.isNotEmpty()) {
                item(key = "doc-options") {
                    Row(
                        modifier = Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(KKCSpacing.inCardSpacing)
                    ) {
                        viewModel.options.forEach { option ->
                            val selected = selectedOption == option
                            Button(
                                onClick = { selectedOption = option },
                                colors = if (selected) {
                                    ButtonDefaults.buttonColors()
                                } else {
                                    ButtonDefaults.outlinedButtonColors()
                                }
                            ) {
                                Text(option.label)
                            }
                        }
                    }
                }
            }

            if (!loadState.isLoading && viewModel.hiddenDoorCutListBecauseMissingUnitType) {
                item(key = "missing-unit-type") {
                    Text(
                        text = "Door Cut List is unavailable for this job (unitType metadata missing).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (!loadState.isLoading && selectedOption == null) {
                item(key = "empty-no-docs") {
                    Text(
                        text = "No door panel content found.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else if (!loadState.isLoading && selectedRows.isEmpty()) {
                item(key = "empty-selected-doc") {
                    val message = if (selectedOption == DoorPanelsDocOption.DOOR_CUT_LIST) {
                        "No SHEETS rows found in Door Cut List."
                    } else {
                        "No rows found."
                    }
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else if (!loadState.isLoading) {
                items(
                    items = selectedRows,
                    key = { row: HardwoodCutlistRow ->
                        row.rowId.ifBlank { "${row.page}-${row.rowOrdinal}" }
                    }
                ) { row ->
                    DoorPanelRowCard(row = row)
                }
            }
        }
    }
}

@Composable
private fun DoorPanelRowCard(row: HardwoodCutlistRow) {
    val material = row.material?.takeIf { it.isNotBlank() } ?: "Unknown"
    val cabinets = row.cabinets.joinToString(", ").ifBlank { "None" }
    Surface(
        tonalElevation = 1.dp,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = KKCSpacing.cardPaddingSmall, vertical = KKCSpacing.m),
            verticalArrangement = Arrangement.spacedBy(KKCSpacing.xxs)
        ) {
            Text(material, style = MaterialTheme.typography.titleSmall)
            Text(
                text = row.description.ifBlank { "No description" },
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = "Qty ${row.qty} • ${row.width} x ${row.length}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "Cabinets: $cabinets",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

internal enum class DoorPanelsDocOption(val label: String) {
    DOOR_LIST("Door List"),
    DOOR_CUT_LIST("Door Cut List")
}

internal data class DoorPanelsViewModel(
    val options: List<DoorPanelsDocOption> = emptyList(),
    val rowsByOption: Map<DoorPanelsDocOption, List<HardwoodCutlistRow>> = emptyMap(),
    val hiddenDoorCutListBecauseMissingUnitType: Boolean = false
)

internal data class DoorPanelsLoadState(
    val isLoading: Boolean = false,
    val viewModel: DoorPanelsViewModel? = null,
    val errorMessage: String? = null
)

internal fun buildDoorPanelsViewModel(
    hardwoodIndex: HardwoodCutlistIndex?,
    rawCutlistIndexJson: String?
): DoorPanelsViewModel {
    val docs = hardwoodIndex?.documents.orEmpty().associateBy { it.docType }
    val doorListDoc = docs[HardwoodDocType.DOOR_LIST]
    val doorCutDoc = docs[HardwoodDocType.DOOR_CUT_LIST]

    val doorCutMetadata = parseDoorCutUnitTypeMetadata(rawCutlistIndexJson)
    val showDoorCutOption = doorCutDoc != null && doorCutMetadata.hasUnitTypeMetadata
    val options = buildList {
        if (doorListDoc != null) add(DoorPanelsDocOption.DOOR_LIST)
        if (showDoorCutOption) add(DoorPanelsDocOption.DOOR_CUT_LIST)
    }
    val rowsByOption = buildMap {
        if (doorListDoc != null) {
            put(DoorPanelsDocOption.DOOR_LIST, doorListDoc.rows)
        }
        if (showDoorCutOption) {
            put(
                DoorPanelsDocOption.DOOR_CUT_LIST,
                filterDoorCutRowsToSheets(
                    rows = doorCutDoc.rows,
                    unitTypeMetadata = doorCutMetadata
                )
            )
        }
    }
    return DoorPanelsViewModel(
        options = options,
        rowsByOption = rowsByOption,
        hiddenDoorCutListBecauseMissingUnitType = doorCutDoc != null && !doorCutMetadata.hasUnitTypeMetadata
    )
}

internal fun loadHardwoodsCutlistIndexRawJson(basePath: String, jobFolderName: String): String? {
    return sharedLoadHardwoodsCutlistIndexRawJson(basePath, jobFolderName)
}

internal fun filterDoorCutRowsToSheets(
    rows: List<HardwoodCutlistRow>,
    unitTypeMetadata: DoorCutUnitTypeMetadata
): List<HardwoodCutlistRow> {
    return sharedFilterDoorCutRowsToSheets(rows, unitTypeMetadata)
}

internal fun parseDoorCutUnitTypeMetadata(rawCutlistIndexJson: String?): DoorCutUnitTypeMetadata {
    return sharedParseDoorCutUnitTypeMetadata(rawCutlistIndexJson)
}
