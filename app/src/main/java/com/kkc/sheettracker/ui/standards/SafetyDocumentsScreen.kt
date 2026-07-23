package com.kkc.sheettracker.ui.standards

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.kkc.sheettracker.data.SafetyRepository
import com.kkc.sheettracker.data.UiPreferencesStore
import com.kkc.sheettracker.data.models.ALL_SAFETY_STATUSES
import com.kkc.sheettracker.data.models.SAFETY_CATEGORIES
import com.kkc.sheettracker.data.models.SafetyComment
import com.kkc.sheettracker.data.models.SafetyItem
import com.kkc.sheettracker.ui.components.KKCTopAppBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Safety & SDS Documents screen featuring a 2-tab layout:
 * - Tab 0: Documents (PDFs published under `.safety`)
 * - Tab 1: Safety Concerns feed, report concern dialog, password unlock gate, and detail thread modal.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SafetyDocumentsScreen(basePath: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val repository = remember(basePath) { SafetyRepository(basePath) }
    val uiPrefs = remember(context) { UiPreferencesStore(context) }
    val safetyDir = remember(basePath) { File(basePath, ".safety") }

    val tabletId = remember(context) {
        runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull() ?: Build.MODEL ?: "tablet"
    }

    var selectedTab by remember { mutableIntStateOf(0) }
    var isSubscriber by remember { mutableStateOf(uiPrefs.isSafetySubscriber()) }
    var savedAuthorName by remember { mutableStateOf(uiPrefs.getSafetyAuthorName()) }

    var pdfFiles by remember { mutableStateOf<List<File>>(emptyList()) }
    var concerns by remember { mutableStateOf<List<SafetyItem>>(emptyList()) }

    var showReportDialog by remember { mutableStateOf(false) }
    var showPasswordDialog by remember { mutableStateOf(false) }
    var selectedConcernForDetail by remember { mutableStateOf<SafetyItem?>(null) }

    fun refreshData() {
        coroutineScope.launch {
            pdfFiles = withContext(Dispatchers.IO) { SafetyDocumentsScreenLogic.listPdfs(safetyDir) }
            concerns = withContext(Dispatchers.IO) { repository.getConcerns() }
        }
    }

    LaunchedEffect(basePath) {
        refreshData()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        KKCTopAppBar(
            title = { Text("Safety / SDS") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
            actions = {
                Button(
                    onClick = { showReportDialog = true },
                    modifier = Modifier.padding(end = 8.dp)
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("+ Report Safety Concern")
                }
            }
        )

        TabRow(selectedTabIndex = selectedTab) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = { Text("Documents (PDFs)") },
                icon = { Icon(Icons.Filled.PictureAsPdf, contentDescription = null) }
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = { Text("Safety Concerns") },
                icon = { Icon(Icons.Filled.Warning, contentDescription = null) }
            )
        }

        Box(modifier = Modifier.fillMaxSize()) {
            if (selectedTab == 0) {
                // Tab 0: Documents (PDFs)
                if (pdfFiles.isEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "No safety documents found.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                } else {
                    LazyColumn(contentPadding = PaddingValues(vertical = 4.dp)) {
                        items(pdfFiles, key = { it.name }) { file ->
                            ListItem(
                                headlineContent = { Text(file.nameWithoutExtension) },
                                leadingContent = {
                                    Icon(Icons.Filled.PictureAsPdf, contentDescription = null)
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        try {
                                            val uri = FileProvider.getUriForFile(
                                                context,
                                                "${context.packageName}.provider",
                                                file
                                            )
                                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                                setDataAndType(uri, "application/pdf")
                                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            }
                                            context.startActivity(intent)
                                        } catch (e: Exception) {
                                            Log.e("KKC", "Failed to open safety document: ${file.absolutePath}", e)
                                        }
                                    }
                            )
                        }
                    }
                }
            } else {
                // Tab 1: Safety Concerns
                if (!isSubscriber) {
                    // Non-Subscriber view: Locked card
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        val cardShape = RoundedCornerShape(16.dp)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .shadow(elevation = 2.dp, shape = cardShape, clip = false)
                                .clip(cardShape)
                                .background(MaterialTheme.colorScheme.surface)
                                .padding(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                Icon(
                                    Icons.Filled.Lock,
                                    contentDescription = null,
                                    modifier = Modifier.size(48.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = "🔒 Subscriber access required to view active concerns and discussion threads",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    textAlign = TextAlign.Center
                                )
                                Button(
                                    onClick = { showPasswordDialog = true }
                                ) {
                                    Icon(Icons.Filled.Key, contentDescription = null)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Subscribe with Password")
                                }
                            }
                        }
                    }
                } else {
                    // Subscriber Feed View
                    if (concerns.isEmpty()) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = "No safety concerns reported yet.",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(concerns, key = { it.id }) { item ->
                                SafetyConcernCard(
                                    item = item,
                                    repository = repository,
                                    onClick = { selectedConcernForDetail = item }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Password Unlock Dialog
    if (showPasswordDialog) {
        var password by remember { mutableStateOf("") }
        var isError by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { showPasswordDialog = false },
            title = { Text("Subscriber Access") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Enter password to view active safety concerns and discussion threads:")
                    OutlinedTextField(
                        value = password,
                        onValueChange = {
                            password = it
                            isError = false
                        },
                        label = { Text("Password") },
                        singleLine = true,
                        isError = isError,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (isError) {
                        Text(
                            text = "Incorrect password. Please try again.",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (password.trim() == "KKC-Safety") {
                            uiPrefs.setSafetySubscriber(true)
                            isSubscriber = true
                            showPasswordDialog = false
                        } else {
                            isError = true
                        }
                    }
                ) {
                    Text("Unlock")
                }
            },
            dismissButton = {
                TextButton(onClick = { showPasswordDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Report Concern Dialog
    if (showReportDialog) {
        ReportConcernDialog(
            context = context,
            repository = repository,
            uiPrefs = uiPrefs,
            savedAuthorName = savedAuthorName,
            tabletId = tabletId,
            onDismiss = { showReportDialog = false },
            onSubmitted = { newAuthor ->
                if (newAuthor.isNotBlank()) savedAuthorName = newAuthor
                refreshData()
                showReportDialog = false
            }
        )
    }

    // Concern Detail & Discussion Modal Dialog
    val detailItem = selectedConcernForDetail
    if (detailItem != null) {
        ConcernDetailDialog(
            concern = detailItem,
            repository = repository,
            uiPrefs = uiPrefs,
            tabletId = tabletId,
            savedAuthorName = savedAuthorName,
            onDismiss = { selectedConcernForDetail = null },
            onStatusChanged = { refreshData() },
            onAuthorUpdated = { savedAuthorName = it }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReportConcernDialog(
    context: Context,
    repository: SafetyRepository,
    uiPrefs: UiPreferencesStore,
    savedAuthorName: String,
    tabletId: String,
    onDismiss: () -> Unit,
    onSubmitted: (newAuthor: String) -> Unit
) {
    val coroutineScope = rememberCoroutineScope()

    var author by remember { mutableStateOf(savedAuthorName) }
    var rememberName by remember { mutableStateOf(savedAuthorName.isNotBlank()) }
    var selectedCategory by remember { mutableStateOf(SAFETY_CATEGORIES.first()) }
    var categoryExpanded by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var attachedImageNames by remember { mutableStateOf<List<String>>(emptyList()) }

    var titleError by remember { mutableStateOf(false) }
    var descError by remember { mutableStateOf(false) }
    var authorError by remember { mutableStateOf(false) }

    var cameraTempFile by remember { mutableStateOf<File?>(null) }

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val file = cameraTempFile
        if (success && file != null && file.exists()) {
            runCatching {
                val bytes = file.readBytes()
                file.delete()
                val filename = "safety_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}.jpg"
                val savedName = repository.saveAttachment(bytes, filename)
                attachedImageNames = attachedImageNames + savedName
            }
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                if (bytes != null) {
                    val mimeType = context.contentResolver.getType(uri) ?: "image/jpeg"
                    val ext = if (mimeType.contains("png")) ".png" else ".jpg"
                    val filename = "safety_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}$ext"
                    val savedName = repository.saveAttachment(bytes, filename)
                    attachedImageNames = attachedImageNames + savedName
                }
            }
        }
    }

    fun launchCamera() {
        runCatching {
            val file = File.createTempFile("safety_cam_", ".jpg", context.cacheDir)
            cameraTempFile = file
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
            cameraLauncher.launch(uri)
        }.onFailure { e ->
            Log.e("KKC", "Camera launch failed", e)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        val dialogShape = RoundedCornerShape(16.dp)
        Box(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.88f)
                .shadow(4.dp, dialogShape, clip = false)
                .clip(dialogShape)
                .background(MaterialTheme.colorScheme.surface)
                .padding(24.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Report Safety Concern",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = "Close")
                    }
                }

                // Author Name
                OutlinedTextField(
                    value = author,
                    onValueChange = {
                        author = it
                        authorError = false
                    },
                    label = { Text("Your Name *") },
                    isError = authorError,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { rememberName = !rememberName }
                ) {
                    Checkbox(
                        checked = rememberName,
                        onCheckedChange = { rememberName = it }
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "Remember name on this tablet",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                // Category Dropdown
                ExposedDropdownMenuBox(
                    expanded = categoryExpanded,
                    onExpandedChange = { categoryExpanded = it }
                ) {
                    OutlinedTextField(
                        value = selectedCategory,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Category *") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = categoryExpanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                    )
                    ExposedDropdownMenu(
                        expanded = categoryExpanded,
                        onDismissRequest = { categoryExpanded = false }
                    ) {
                        SAFETY_CATEGORIES.forEach { cat ->
                            DropdownMenuItem(
                                text = { Text(cat) },
                                onClick = {
                                    selectedCategory = cat
                                    categoryExpanded = false
                                }
                            )
                        }
                    }
                }

                // Title
                OutlinedTextField(
                    value = title,
                    onValueChange = {
                        title = it
                        titleError = false
                    },
                    label = { Text("Title *") },
                    isError = titleError,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // Description
                OutlinedTextField(
                    value = description,
                    onValueChange = {
                        description = it
                        descError = false
                    },
                    label = { Text("Description *") },
                    isError = descError,
                    minLines = 3,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth()
                )

                // Photos Section
                Text(
                    "Photo Attachments",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { launchCamera() }) {
                        Icon(Icons.Filled.PhotoCamera, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Take Photo")
                    }
                    OutlinedButton(onClick = { galleryLauncher.launch("image/*") }) {
                        Icon(Icons.Filled.PhotoLibrary, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Gallery")
                    }
                }

                if (attachedImageNames.isNotEmpty()) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(attachedImageNames) { name ->
                            val file = repository.getAttachmentFile(name)
                            val bitmap = remember(file.absolutePath) {
                                if (file.exists()) BitmapFactory.decodeFile(file.absolutePath) else null
                            }
                            Box(modifier = Modifier.size(80.dp)) {
                                if (bitmap != null) {
                                    Image(
                                        bitmap = bitmap.asImageBitmap(),
                                        contentDescription = "Photo",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .clip(RoundedCornerShape(8.dp))
                                    )
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(MaterialTheme.colorScheme.surfaceVariant),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(Icons.Filled.Image, contentDescription = null)
                                    }
                                }
                                IconButton(
                                    onClick = { attachedImageNames = attachedImageNames - name },
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .size(24.dp)
                                        .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                                ) {
                                    Icon(
                                        Icons.Filled.Close,
                                        contentDescription = "Remove photo",
                                        tint = Color.White,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel")
                    }
                    Spacer(Modifier.width(12.dp))
                    Button(
                        onClick = {
                            var valid = true
                            if (author.isBlank()) {
                                authorError = true
                                valid = false
                            }
                            if (title.isBlank()) {
                                titleError = true
                                valid = false
                            }
                            if (description.isBlank()) {
                                descError = true
                                valid = false
                            }
                            if (valid) {
                                if (rememberName) {
                                    uiPrefs.setSafetyAuthorName(author)
                                }
                                coroutineScope.launch {
                                    withContext(Dispatchers.IO) {
                                        repository.addConcern(
                                            author = author,
                                            title = title,
                                            category = selectedCategory,
                                            description = description,
                                            attachmentIds = attachedImageNames,
                                            tabletId = tabletId
                                        )
                                    }
                                    onSubmitted(if (rememberName) author.trim() else "")
                                }
                            }
                        }
                    ) {
                        Text("Submit Report")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConcernDetailDialog(
    concern: SafetyItem,
    repository: SafetyRepository,
    uiPrefs: UiPreferencesStore,
    tabletId: String,
    savedAuthorName: String,
    onDismiss: () -> Unit,
    onStatusChanged: () -> Unit,
    onAuthorUpdated: (String) -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    var currentStatus by remember(concern.id) { mutableStateOf(concern.status) }
    var statusDropdownExpanded by remember { mutableStateOf(false) }
    var comments by remember(concern.id) { mutableStateOf<List<SafetyComment>>(emptyList()) }
    var commentAuthor by remember { mutableStateOf(savedAuthorName) }
    var commentText by remember { mutableStateOf("") }

    val refreshComments = {
        coroutineScope.launch {
            comments = withContext(Dispatchers.IO) { repository.getComments(concern.id) }
        }
    }

    LaunchedEffect(concern.id) {
        refreshComments()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        val dialogShape = RoundedCornerShape(16.dp)
        Box(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.9f)
                .shadow(4.dp, dialogShape, clip = false)
                .clip(dialogShape)
                .background(MaterialTheme.colorScheme.surface)
                .padding(24.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = concern.category,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                        StatusBadge(status = currentStatus)
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = "Close")
                    }
                }

                Text(
                    text = concern.title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = "Reported by ${concern.author} • ${formatTimestamp(concern.createdAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        "Update Status:",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    ExposedDropdownMenuBox(
                        expanded = statusDropdownExpanded,
                        onExpandedChange = { statusDropdownExpanded = it }
                    ) {
                        OutlinedTextField(
                            value = currentStatus,
                            onValueChange = {},
                            readOnly = true,
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = statusDropdownExpanded) },
                            modifier = Modifier
                                .width(200.dp)
                                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                        )
                        ExposedDropdownMenu(
                            expanded = statusDropdownExpanded,
                            onDismissRequest = { statusDropdownExpanded = false }
                        ) {
                            ALL_SAFETY_STATUSES.forEach { st ->
                                DropdownMenuItem(
                                    text = { Text(st) },
                                    onClick = {
                                        currentStatus = st
                                        statusDropdownExpanded = false
                                        coroutineScope.launch {
                                            withContext(Dispatchers.IO) {
                                                repository.setStatus(
                                                    concern.id,
                                                    st,
                                                    commentAuthor.ifBlank { "Tablet User" },
                                                    tabletId
                                                )
                                            }
                                            onStatusChanged()
                                        }
                                    }
                                )
                            }
                        }
                    }
                }

                HorizontalDivider()

                Text(
                    text = "Description",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = concern.description,
                    style = MaterialTheme.typography.bodyMedium
                )

                if (concern.attachmentIds.isNotEmpty()) {
                    Text(
                        text = "Photo Attachments (${concern.attachmentIds.size})",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(concern.attachmentIds) { filename ->
                            val file = repository.getAttachmentFile(filename)
                            val bitmap = remember(file.absolutePath) {
                                if (file.exists()) BitmapFactory.decodeFile(file.absolutePath) else null
                            }
                            if (bitmap != null) {
                                Image(
                                    bitmap = bitmap.asImageBitmap(),
                                    contentDescription = "Photo attachment",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(120.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                )
                            }
                        }
                    }
                }

                HorizontalDivider()

                Text(
                    text = "Discussion & Updates (${comments.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                if (comments.isEmpty()) {
                    Text(
                        text = "No comments yet. Start the discussion below.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        comments.forEach { comment ->
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                    .padding(12.dp)
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = comment.author,
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = formatTimestamp(comment.createdAt),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Text(
                                        text = comment.text,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }
                        }
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Add a Comment / Update",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    OutlinedTextField(
                        value = commentAuthor,
                        onValueChange = { commentAuthor = it },
                        label = { Text("Your Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = commentText,
                        onValueChange = { commentText = it },
                        label = { Text("Comment") },
                        minLines = 2,
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        Button(
                            onClick = {
                                if (commentText.isNotBlank()) {
                                    val authorName = commentAuthor.ifBlank { "Anonymous" }
                                    if (savedAuthorName.isBlank() && authorName != "Anonymous") {
                                        uiPrefs.setSafetyAuthorName(authorName)
                                        onAuthorUpdated(authorName)
                                    }
                                    coroutineScope.launch {
                                        withContext(Dispatchers.IO) {
                                            repository.addComment(concern.id, authorName, commentText)
                                        }
                                        commentText = ""
                                        refreshComments()
                                    }
                                }
                            },
                            enabled = commentText.isNotBlank()
                        ) {
                            Icon(Icons.Filled.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Post Comment")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SafetyConcernCard(
    item: SafetyItem,
    repository: SafetyRepository,
    onClick: () -> Unit
) {
    val cardShape = RoundedCornerShape(12.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(elevation = 2.dp, shape = cardShape, clip = false)
            .clip(cardShape)
            .background(MaterialTheme.colorScheme.surface)
            .clickable { onClick() }
            .padding(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = item.category,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                StatusBadge(status = item.status)
            }

            Text(
                text = item.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Text(
                text = "Reported by ${item.author} • ${formatTimestamp(item.createdAt)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text(
                text = item.description,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )

            if (item.attachmentIds.isNotEmpty()) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    items(item.attachmentIds) { filename ->
                        val file = repository.getAttachmentFile(filename)
                        val bitmap = remember(file.absolutePath) {
                            if (file.exists()) BitmapFactory.decodeFile(file.absolutePath) else null
                        }
                        if (bitmap != null) {
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = "Attachment thumbnail",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(60.dp)
                                    .clip(RoundedCornerShape(6.dp))
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(status: String) {
    val (bgColor, textColor) = when (status.uppercase()) {
        "OPEN" -> Color(0xFFFFF3E0) to Color(0xFFE65100)
        "ACKNOWLEDGED" -> Color(0xFFE3F2FD) to Color(0xFF1565C0)
        "IN PROGRESS" -> Color(0xFFF3E5F5) to Color(0xFF7B1FA2)
        "RESOLVED" -> Color(0xFFE8F5E9) to Color(0xFF2E7D32)
        else -> Color(0xFFEEEEEE) to Color(0xFF616161)
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(bgColor)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(
            text = status,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            color = textColor
        )
    }
}

private fun formatTimestamp(isoString: String): String {
    if (isoString.isBlank()) return ""
    return runCatching {
        val instant = Instant.parse(isoString)
        val zone = ZoneId.systemDefault()
        val formatter = DateTimeFormatter.ofPattern("MMM d, yyyy h:mm a")
        instant.atZone(zone).format(formatter)
    }.getOrDefault(isoString)
}

