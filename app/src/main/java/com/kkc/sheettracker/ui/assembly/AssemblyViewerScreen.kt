package com.kkc.sheettracker.ui.assembly

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.kkc.sheettracker.data.AssemblyStateStore
import com.kkc.sheettracker.data.JobRepository
import com.kkc.sheettracker.data.models.AssemblyBomEntry
import com.kkc.sheettracker.data.models.AssemblyCabinetParts
import com.kkc.sheettracker.data.models.AssemblyCncPart
import com.kkc.sheettracker.data.models.AssemblyHardwoodRow
import com.kkc.sheettracker.data.models.HardwoodDocType
import com.kkc.sheettracker.data.models.ReferenceDocType
import com.kkc.sheettracker.data.models.SheetStatus
import com.kkc.sheettracker.ui.components.ReferencePdfPane
import com.kkc.sheettracker.ui.theme.KKCThemeColors
import com.kkc.sheettracker.viewer3d.Model3DPane
import com.kkc.sheettracker.viewer3d.ViewerServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// Which document to show in a PDF pane
private enum class PdfDoc { PLANS, ASSEMBLY }

// What a pane is currently showing
private sealed interface PaneContent {
    data class Pdf(val doc: PdfDoc) : PaneContent
    data class ThreeD(val roomName: String?) : PaneContent
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssemblyViewerScreen(
    assemblyStateStore: AssemblyStateStore,
    jobRepository: JobRepository,
    jobFolderName: String,
    basePath: String,
    isDarkTheme: Boolean,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val jobCards by assemblyStateStore.jobCards.collectAsState()
    val jobCard = remember(jobCards, jobFolderName) {
        jobCards.firstOrNull { it.folderName == jobFolderName }
    }
    val cabinetSheetIndex = remember(jobFolderName) {
        jobRepository.getCabinetSheetIndex(jobFolderName)
    }
    val plansFile = remember(jobFolderName, isDarkTheme) {
        val fn = cabinetSheetIndex?.documents?.plansElevations?.pdfFilename
            ?.takeIf { it.isNotBlank() }
            ?: jobRepository.findReferencePdfFilename(jobFolderName, ReferenceDocType.PLANS_ELEVATIONS)
            ?: return@remember null
        jobRepository.getJobRootPdfFile(jobFolderName, fn, preferDarkMode = isDarkTheme)
    }
    val assemblyFile = remember(jobFolderName, isDarkTheme) {
        val fn = cabinetSheetIndex?.documents?.assembly?.pdfFilename
            ?.takeIf { it.isNotBlank() }
            ?: jobRepository.findReferencePdfFilename(jobFolderName, ReferenceDocType.ASSEMBLY)
            ?: return@remember null
        jobRepository.getJobRootPdfFile(jobFolderName, fn, preferDarkMode = isDarkTheme)
    }

    // NanoHTTPD server — starts when this screen enters composition
    var serverPort by remember { mutableIntStateOf(0) }
    DisposableEffect(jobFolderName) {
        val server = ViewerServer(context, File(basePath))
        serverPort = server.startAndGetPort()
        onDispose { server.stop() }
    }

    var plansPage     by remember { mutableIntStateOf(1) }
    var assemblyPage  by remember { mutableIntStateOf(1) }
    var cabinetInput  by remember { mutableStateOf("") }
    var activeCabinet by remember { mutableStateOf("") }
    var cabinetContext by remember { mutableStateOf<String?>(null) }
    var showParts     by remember { mutableStateOf(false) }
    var cabinetParts  by remember { mutableStateOf<AssemblyCabinetParts?>(null) }

    var leftPane:  PaneContent by remember { mutableStateOf(PaneContent.Pdf(PdfDoc.PLANS)) }
    var rightPane: PaneContent by remember { mutableStateOf(PaneContent.Pdf(PdfDoc.ASSEMBLY)) }

    val scope = rememberCoroutineScope()

    fun jumpToCabinet(cab: String) {
        if (cab.isBlank()) return
        val idx = cabinetSheetIndex
        var detectedRoom: String? = null
        if (idx != null) {
            idx.documents.plansElevations.cabinetToPages[cab]?.firstOrNull()?.let { plansPage = it }
            idx.documents.assembly.cabinetToPages[cab]?.firstOrNull()?.let { assemblyPage = it }
            val assemblyDetail = idx.documents.assembly.cabinetToPages[cab]
                ?.firstOrNull()?.toString()?.let { idx.documents.assembly.pageDetails[it] }
            val plansDetail = idx.documents.plansElevations.cabinetToPages[cab]
                ?.firstOrNull()?.toString()?.let { idx.documents.plansElevations.pageDetails[it] }
            val detail = assemblyDetail ?: plansDetail
            cabinetContext = listOfNotNull(detail?.room, detail?.wall)
                .joinToString(" — ").takeIf { it.isNotBlank() }
            detectedRoom = extractRoomFolder(detail?.room)
        }
        activeCabinet = cab
        if (detectedRoom != null) {
            if (leftPane is PaneContent.ThreeD)  leftPane  = PaneContent.ThreeD(detectedRoom)
            if (rightPane is PaneContent.ThreeD) rightPane = PaneContent.ThreeD(detectedRoom)
        }
        scope.launch(Dispatchers.Default) {
            val parts = assemblyStateStore.deriveCabinetParts(jobFolderName, cab)
            withContext(Dispatchers.Main) { cabinetParts = parts }
        }
    }

    if (showParts) {
        val sheetState = rememberModalBottomSheetState()
        ModalBottomSheet(
            onDismissRequest = { showParts = false },
            sheetState = sheetState
        ) {
            PartsChecklistSheet(
                cabinetNumber = activeCabinet,
                parts = cabinetParts ?: AssemblyCabinetParts(cabinetNumber = activeCabinet)
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        jobCard?.let { "${it.jobNumber} — ${it.jobName}" } ?: jobFolderName,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (cabinetParts != null) {
                        TextButton(onClick = { showParts = true }) {
                            Text("Parts")
                            Icon(Icons.Default.ExpandMore, contentDescription = null)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = cabinetInput,
                    onValueChange = { cabinetInput = it.filter { c -> c.isDigit() } },
                    label = { Text("Cabinet #") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Search
                    ),
                    keyboardActions = KeyboardActions(
                        onSearch = { jumpToCabinet(cabinetInput.trim()) }
                    ),
                    trailingIcon = {
                        IconButton(
                            onClick = { jumpToCabinet(cabinetInput.trim()) },
                            enabled = cabinetInput.isNotBlank()
                        ) {
                            Icon(Icons.Default.Search, contentDescription = "Jump to cabinet")
                        }
                    },
                    modifier = Modifier.width(180.dp),
                    shape = MaterialTheme.shapes.medium
                )
                if (cabinetContext != null) {
                    Text(
                        "Cab $activeCabinet — $cabinetContext",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else if (activeCabinet.isNotBlank()) {
                    Text(
                        "Cabinet $activeCabinet",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
            }
            HorizontalDivider()

            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                PaneSlot(
                    modifier = Modifier.weight(1f),
                    content = leftPane,
                    onContentChange = { leftPane = it },
                    plansFile = plansFile,
                    assemblyFile = assemblyFile,
                    plansPage = plansPage,
                    assemblyPage = assemblyPage,
                    onPlansPageChange = { plansPage = it },
                    onAssemblyPageChange = { assemblyPage = it },
                    serverPort = serverPort,
                    jobFolderName = jobFolderName,
                    basePath = basePath,
                    isDarkTheme = isDarkTheme,
                    context = context
                )
                VerticalDivider()
                PaneSlot(
                    modifier = Modifier.weight(1f),
                    content = rightPane,
                    onContentChange = { rightPane = it },
                    plansFile = plansFile,
                    assemblyFile = assemblyFile,
                    plansPage = plansPage,
                    assemblyPage = assemblyPage,
                    onPlansPageChange = { plansPage = it },
                    onAssemblyPageChange = { assemblyPage = it },
                    serverPort = serverPort,
                    jobFolderName = jobFolderName,
                    basePath = basePath,
                    isDarkTheme = isDarkTheme,
                    context = context
                )
            }
        }
    }
}

@Composable
private fun PaneSlot(
    modifier: Modifier = Modifier,
    content: PaneContent,
    onContentChange: (PaneContent) -> Unit,
    plansFile: java.io.File?,
    assemblyFile: java.io.File?,
    plansPage: Int,
    assemblyPage: Int,
    onPlansPageChange: (Int) -> Unit,
    onAssemblyPageChange: (Int) -> Unit,
    serverPort: Int,
    jobFolderName: String,
    basePath: String,
    isDarkTheme: Boolean,
    context: Context
) {
    var showDropdown by remember { mutableStateOf(false) }

    val labelText = when (content) {
        is PaneContent.Pdf -> when (content.doc) {
            PdfDoc.PLANS    -> "Plans & Elevations"
            PdfDoc.ASSEMBLY -> "Assembly Sheets"
        }
        is PaneContent.ThreeD -> "3D Model"
    }

    when (content) {
        is PaneContent.Pdf -> {
            val (file, page, onPageChange) = when (content.doc) {
                PdfDoc.PLANS    -> Triple(plansFile,    plansPage,    onPlansPageChange)
                PdfDoc.ASSEMBLY -> Triple(assemblyFile, assemblyPage, onAssemblyPageChange)
            }
            val missingText = when (content.doc) {
                PdfDoc.PLANS    -> "Plans & Elevations PDF not found"
                PdfDoc.ASSEMBLY -> "Assembly Sheets PDF not found"
            }
            ReferencePdfPane(
                modifier = modifier,
                pdfFile = file,
                currentPage = page,
                onCurrentPageChange = onPageChange,
                showDocControls = {
                    PaneSwitcherLabel(
                        label = labelText,
                        showDropdown = showDropdown,
                        onToggle = { showDropdown = !showDropdown }
                    )
                    PaneDropdownMenu(
                        expanded = showDropdown,
                        onDismiss = { showDropdown = false },
                        onSelect = { onContentChange(it); showDropdown = false }
                    )
                },
                missingText = missingText
            )
        }
        is PaneContent.ThreeD -> {
            val roomForFullScreen = content.roomName
            Model3DPane(
                modifier = modifier,
                folderName = jobFolderName,
                roomName = content.roomName,
                serverPort = serverPort,
                isDarkTheme = isDarkTheme,
                onFullScreen = {
                    if (roomForFullScreen != null) {
                        launchFullScreen3D(context, File("$basePath/$jobFolderName/3D/$roomForFullScreen/3d.dae"))
                    }
                },
                headerSlot = {
                    PaneSwitcherLabel(
                        label = labelText,
                        showDropdown = showDropdown,
                        onToggle = { showDropdown = !showDropdown }
                    )
                    PaneDropdownMenu(
                        expanded = showDropdown,
                        onDismiss = { showDropdown = false },
                        onSelect = { onContentChange(it); showDropdown = false }
                    )
                    Spacer(modifier = Modifier.weight(1f))
                }
            )
        }
    }
}

@Composable
private fun PaneSwitcherLabel(label: String, showDropdown: Boolean, onToggle: () -> Unit) {
    TextButton(onClick = onToggle) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Icon(
            Icons.Default.ArrowDropDown,
            contentDescription = "Switch pane content",
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun PaneDropdownMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onSelect: (PaneContent) -> Unit
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text("Plans & Elevations") },
            onClick = { onSelect(PaneContent.Pdf(PdfDoc.PLANS)) }
        )
        DropdownMenuItem(
            text = { Text("Assembly Sheets") },
            onClick = { onSelect(PaneContent.Pdf(PdfDoc.ASSEMBLY)) }
        )
        DropdownMenuItem(
            text = { Text("3D Model") },
            onClick = { onSelect(PaneContent.ThreeD(null)) }
        )
    }
}

private fun extractRoomFolder(roomText: String?): String? {
    if (roomText.isNullOrBlank()) return null
    val m = Regex("""\(([^)]+)\)""").find(roomText)
    return (if (m != null) m.groupValues[1] else roomText).trim().uppercase()
}

private fun launchFullScreen3D(context: Context, daeFile: File) {
    if (!daeFile.exists()) return
    try {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", daeFile)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/octet-stream")
            setPackage("com.example.pccoe.assimpandroid")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    } catch (_: Exception) {
        // AssimpAndroid not installed or unavailable — fail silently
    }
}

@Composable
private fun PartsChecklistSheet(cabinetNumber: String, parts: AssemblyCabinetParts) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            "Cabinet $cabinetNumber — Parts",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
        )
        HorizontalDivider()

        if (parts.bom.isNotEmpty()) {
            BomPartsContent(parts.bom)
        } else if (parts.cncParts.isEmpty() && parts.hardwoodRows.isEmpty()) {
            Text(
                "No parts found for cabinet $cabinetNumber",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp)
            )
        } else {
            LegacyPartsContent(parts)
        }
    }
}

@Composable
private fun BomPartsContent(bom: List<AssemblyBomEntry>) {
    val bySection = bom.groupBy { it.part.sectionType }
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        for ((section, entries) in bySection) {
            item(key = "bom_sec_$section") { PartsSheetSectionHeader(section.uppercase()) }
            items(entries, key = { "bom_${section}_${it.part.description}_${it.part.width}_${it.part.length}_${entries.indexOf(it)}" }) { entry ->
                BomPartRow(entry)
            }
        }
    }
}

@Composable
private fun BomPartRow(entry: AssemblyBomEntry) {
    val colors = KKCThemeColors.statusColors
    val part = entry.part

    val (icon, iconTint, chipLabel, chipColor) = when {
        part.isPurchased -> BomRowDisplay(
            icon = "—",
            iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
            chip = "Purch.",
            chipColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
        entry.cncPart != null -> {
            val cp = entry.cncPart
            val (ic, tint) = when {
                cp.isBadPart -> "⚠" to MaterialTheme.colorScheme.error
                cp.sheetStatus == SheetStatus.COMPLETE -> "✓" to colors.completeBorder
                cp.sheetStatus == SheetStatus.SKIPPED -> "⏭" to MaterialTheme.colorScheme.onSurfaceVariant
                cp.sheetStatus == SheetStatus.IN_PROGRESS -> "◑" to colors.inProgressBorder
                else -> "✗" to MaterialTheme.colorScheme.onSurfaceVariant
            }
            BomRowDisplay(ic, tint, "CNC", tint)
        }
        entry.hardwoodRow != null -> {
            val hw = entry.hardwoodRow
            val fullyDone = hw.doneCount >= part.qty
            val (ic, tint) = when {
                hw.skipped -> "⏭" to MaterialTheme.colorScheme.onSurfaceVariant
                hw.badCount > 0 -> "⚠" to MaterialTheme.colorScheme.error
                fullyDone -> "✓" to colors.completeBorder
                hw.doneCount > 0 -> "◑" to colors.inProgressBorder
                else -> "✗" to MaterialTheme.colorScheme.onSurfaceVariant
            }
            BomRowDisplay(ic, tint, "HW ${hw.doneCount}/${part.qty}", tint)
        }
        else -> BomRowDisplay(
            icon = "?",
            iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
            chip = "?",
            chipColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(icon, color = iconTint, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(18.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "${part.qty}×  ${part.description}",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "${part.width}\" × ${part.length}\"",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            chipLabel,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = chipColor
        )
    }
}

private data class BomRowDisplay(
    val icon: String,
    val iconTint: androidx.compose.ui.graphics.Color,
    val chip: String,
    val chipColor: androidx.compose.ui.graphics.Color
)

@Composable
private fun LegacyPartsContent(parts: AssemblyCabinetParts) {
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        if (parts.cncParts.isNotEmpty()) {
            item { PartsSheetSectionHeader("CNC PARTS") }
            val byMaterial = parts.cncParts.groupBy { it.materialName }
            for ((materialName, matParts) in byMaterial) {
                item(key = "mat_$materialName") {
                    Text(
                        materialName,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
                    )
                }
                items(matParts, key = { "cnc_${materialName}_${it.pageNumber}_${it.partNumber}" }) { part ->
                    CncPartRow(part)
                }
            }
        }

        if (parts.hardwoodRows.isNotEmpty()) {
            item {
                if (parts.cncParts.isNotEmpty()) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                }
                PartsSheetSectionHeader("HARDWOODS")
            }
            val byDocType = parts.hardwoodRows.groupBy { it.docType }
            for ((docType, rows) in byDocType) {
                item(key = "hw_doc_${docType.name}") {
                    Text(
                        docType.displayName(),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
                    )
                }
                items(rows, key = { "hw_${docType.name}_${it.description}_${it.qty}_${it.width}_${it.length}" }) { row ->
                    HardwoodRowItem(row)
                }
            }
        }
    }
}

@Composable
private fun PartsSheetSectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(bottom = 4.dp)
    )
}

@Composable
private fun CncPartRow(part: AssemblyCncPart) {
    val colors = KKCThemeColors.statusColors
    val (icon, tint) = when {
        part.isBadPart -> "⚠" to MaterialTheme.colorScheme.error
        part.sheetStatus == SheetStatus.COMPLETE -> "✓" to colors.completeBorder
        part.sheetStatus == SheetStatus.SKIPPED -> "⏭" to MaterialTheme.colorScheme.onSurfaceVariant
        part.sheetStatus == SheetStatus.IN_PROGRESS -> "◑" to colors.inProgressBorder
        else -> "✗" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(icon, color = tint, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(18.dp))
        Text(
            "#${part.partNumber}",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.width(32.dp)
        )
        Text(
            part.partName,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            "${part.width}\" × ${part.length}\"",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun HardwoodRowItem(row: AssemblyHardwoodRow) {
    val colors = KKCThemeColors.statusColors
    val fullyDone = row.doneCount >= row.qty
    val (icon, tint) = when {
        row.skipped -> "⏭" to MaterialTheme.colorScheme.onSurfaceVariant
        row.badCount > 0 -> "⚠" to MaterialTheme.colorScheme.error
        fullyDone -> "✓" to colors.completeBorder
        row.doneCount > 0 -> "◑" to colors.inProgressBorder
        else -> "✗" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(icon, color = tint, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(18.dp))
        Text(
            row.description,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (row.material != null) {
            Text(
                row.material,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(80.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            "${row.width} × ${row.length}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(100.dp)
        )
        Text(
            "${row.doneCount}/${row.qty}",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = if (fullyDone) colors.completeBorder else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.width(36.dp)
        )
    }
}

private fun HardwoodDocType.displayName(): String = when (this) {
    HardwoodDocType.FACE_FRAME_CUT_LIST -> "Face Frame Cut List"
    HardwoodDocType.NAILER_CUT_LIST     -> "Nailer Cut List"
    HardwoodDocType.DOOR_CUT_LIST       -> "Door Cut List"
    HardwoodDocType.DOOR_LIST           -> "Door List"
}
