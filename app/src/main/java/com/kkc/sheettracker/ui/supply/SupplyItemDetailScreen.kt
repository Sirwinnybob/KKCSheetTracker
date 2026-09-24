package com.kkc.sheettracker.ui.supply

import android.Manifest
import android.net.Uri
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import com.kkc.sheettracker.ui.components.MarkdownText
import com.kkc.sheettracker.ui.components.StatusChip
import com.kkc.sheettracker.ui.dashboard.DashboardSectionHeader
import com.kkc.sheettracker.ui.dashboard.DashboardSurfaceCard
import com.kkc.sheettracker.ui.dashboard.DashboardAccent
import com.kkc.sheettracker.ui.dashboard.getSoftStatusColors
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Image
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.kkc.sheettracker.data.SupplyRepository
import com.kkc.sheettracker.data.SupplySubscriptionManager
import com.kkc.sheettracker.data.SupplyBarcodeStore
import com.kkc.sheettracker.data.ScanMode
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.AlertDialog
import androidx.compose.ui.text.font.FontFamily
import com.kkc.sheettracker.data.models.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.UUID
import android.widget.Toast
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.TabRow
import androidx.compose.material3.Tab
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.List
import com.kkc.sheettracker.ui.theme.LocalKKCIsDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.automirrored.filled.Send

private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SupplyItemDetailScreen(
    itemId: String,
    basePath: String,
    tabletId: String,
    employeeName: String,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    subscriptionManager: SupplySubscriptionManager,
    barcodeStore: SupplyBarcodeStore
) {
    val repository = remember(basePath) { SupplyRepository(basePath) }
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val subscriptionData by subscriptionManager.subscriptionData.collectAsState()

    var item by remember { mutableStateOf<SupplyItem?>(null) }
    var schema by remember { mutableStateOf(DEFAULT_SUPPLY_SCHEMA) }
    var comments by remember { mutableStateOf<List<SupplyComment>>(emptyList()) }
    var categories by remember { mutableStateOf<List<SupplyCategory>>(emptyList()) }
    val categoryMap = remember(categories) { categories.associateBy { it.id } }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var commentText by remember { mutableStateOf("") }
    var commentAuthor by remember { mutableStateOf(employeeName) }
    var isSubmittingComment by remember { mutableStateOf(false) }
    var isAddingAttachment by remember { mutableStateOf(false) }
    var showAttachmentOptions by remember { mutableStateOf(false) }
    var captureTargetFile by remember { mutableStateOf<File?>(null) }

    var showStatusSheet by remember { mutableStateOf(false) }
    var showLabelsDropdown by remember { mutableStateOf(false) }

    fun loadData() {
        coroutineScope.launch {
            isLoading = true
            errorMessage = null
            try {
                val loadedItem = withContext(Dispatchers.IO) { repository.getItem(itemId) }
                schema = withContext(Dispatchers.IO) { repository.schemaOrDefault() }
                val loadedCats = withContext(Dispatchers.IO) { repository.getCategories() }
                if (loadedItem == null) {
                    errorMessage = "Item not found"
                } else {
                    val loadedComments = withContext(Dispatchers.IO) { repository.getComments(itemId) }
                    item = loadedItem
                    comments = loadedComments
                    categories = loadedCats
                }
            } catch (e: Exception) {
                errorMessage = e.message ?: "Failed to load item"
            } finally {
                isLoading = false
            }
        }
    }

    LaunchedEffect(itemId) { loadData() }

    // Refresh when returning from edit screen
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) loadData()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Camera launcher
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val file = captureTargetFile
        if (success && file != null) {
            coroutineScope.launch {
                isAddingAttachment = true
                runCatching {
                    val uuid = UUID.randomUUID().toString()
                    val attachment = SupplyAttachment(uuid, "photo_${System.currentTimeMillis()}.jpg", "$uuid.jpg")
                    withContext(Dispatchers.IO) {
                        repository.addAttachment(itemId, attachment, file)
                        file.delete()
                    }
                    val updated = withContext(Dispatchers.IO) { repository.getItem(itemId) }
                    if (updated != null) item = updated
                }
                captureTargetFile = null
                isAddingAttachment = false
            }
        }
    }

    fun beginCameraCapture() {
        val dir = File(context.cacheDir, "supply_temp")
        dir.mkdirs()
        val file = File(dir, "capture_${System.currentTimeMillis()}.jpg")
        captureTargetFile = file
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        cameraLauncher.launch(uri)
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            beginCameraCapture()
        } else {
            Toast.makeText(context, "Camera permission is required to take a photo", Toast.LENGTH_LONG).show()
        }
    }

    // Gallery launcher
    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        coroutineScope.launch {
            isAddingAttachment = true
            runCatching {
                val uuid = UUID.randomUUID().toString()
                val mimeType = context.contentResolver.getType(uri) ?: "image/jpeg"
                val ext = when {
                    mimeType.contains("png") -> ".png"
                    mimeType.contains("gif") -> ".gif"
                    mimeType.contains("webp") -> ".webp"
                    else -> ".jpg"
                }
                val storedName = "$uuid$ext"
                val originalName = uri.lastPathSegment?.substringAfterLast('/') ?: "image$ext"
                val attachment = SupplyAttachment(uuid, originalName, storedName)
                val tempFile = File(context.cacheDir, "supply_temp/$storedName")
                tempFile.parentFile?.mkdirs()
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        tempFile.outputStream().use { output -> input.copyTo(output) }
                    }
                    repository.addAttachment(itemId, attachment, tempFile)
                    tempFile.delete()
                }
                val updated = withContext(Dispatchers.IO) { repository.getItem(itemId) }
                if (updated != null) item = updated
            }
            isAddingAttachment = false
        }
    }

    fun launchCamera() {
        when (
            launchCameraSafely(
                cameraPermissionGranted = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.CAMERA
                ) == PackageManager.PERMISSION_GRANTED,
                requestPermission = { cameraPermissionLauncher.launch(Manifest.permission.CAMERA) },
                launch = ::beginCameraCapture
            )
        ) {
            CameraLaunchResult.LAUNCH_FAILED -> Toast.makeText(
                context,
                "Unable to open the camera. Try granting camera access again.",
                Toast.LENGTH_LONG
            ).show()
            else -> Unit
        }
    }

    val currentItem = item

    LaunchedEffect(currentItem, comments) {
        if (currentItem != null) {
            subscriptionManager.dismissNotification(currentItem.id)
        }
    }

    val scanMode by barcodeStore.scanMode.collectAsState()
    var itemScanResult by remember { mutableStateOf<String?>(null) }

    // The supply modal sizes itself to this content (wrapContentHeight), so the root and the
    // wide layout must not fill the available height — only the narrow LazyColumn does.
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val isWide = maxWidth >= 600.dp
        var showDeleteConfirmDialog by remember { mutableStateOf(false) }

        when {
            isLoading -> {
                Box(
                    modifier = Modifier.fillMaxWidth().height(240.dp),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator() }
            }
            errorMessage != null -> {
                Box(
                    modifier = Modifier.fillMaxWidth().height(240.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "Error: $errorMessage",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Button(onClick = { loadData() }) { Text("Retry") }
                    }
                }
            }
            currentItem != null -> {
                if (isWide) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(24.dp)
                    ) {
                        // LEFT COLUMN (68% width)
                        Column(
                            modifier = Modifier
                                .weight(0.68f)
                                .verticalScroll(rememberScrollState())
                                // Room for the last card's shadow; the scroll viewport clips it otherwise.
                                .padding(bottom = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Text(
                                text = currentItem.name,
                                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )

                            if (!currentItem.notes.isNullOrBlank()) {
                                DetailSection(title = "Notes", accent = supplyAccent(currentItem.status)) {
                                    MarkdownText(currentItem.notes, style = MaterialTheme.typography.bodyMedium)
                                }
                            }

                            // Fields, rendered from the current schema (non-blank values only).
                            // Orphan values (keys not in the current schema) are intentionally hidden.
                            data class DetailField(val label: String, val value: String, val type: String, val key: String)
                            val detailFields = schema.mapNotNull { f ->
                                val v = (currentItem.fields[f.key] ?: currentItem.customFields[f.key])
                                    ?.takeIf { it.isNotBlank() }
                                    // SKU already listed under Barcodes (tagged there) — don't show it twice.
                                    ?.takeUnless { f.key == "sku" && it in currentItem.barcodes }
                                v?.let { DetailField(f.label, it, f.type, f.key) }
                            }
                            if (detailFields.isNotEmpty()) {
                                val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
                                DetailSection(title = "Details") {
                                    Column(
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                                    ) {
                                        detailFields.forEachIndexed { index, df ->
                                            val label = df.label
                                            val value = df.value
                                            if (index > 0) {
                                                HorizontalDivider(
                                                    thickness = 0.5.dp,
                                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                                                )
                                            }
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(vertical = 4.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    label,
                                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                                
                                                val isUrl = df.type == "url" && (value.startsWith("http://") || value.startsWith("https://"))
                                                val isTracking = df.key == "trackingNumber" && value.isNotBlank()
                                                
                                                if (isUrl) {
                                                    Text(
                                                        text = value,
                                                        style = MaterialTheme.typography.bodyMedium.copy(
                                                            color = MaterialTheme.colorScheme.primary,
                                                            textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                                                            fontWeight = FontWeight.Medium
                                                        ),
                                                        modifier = Modifier
                                                            .weight(1f)
                                                            .clickable { runCatching { uriHandler.openUri(value) } },
                                                        textAlign = androidx.compose.ui.text.style.TextAlign.End,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                } else if (isTracking) {
                                                    Text(
                                                        text = value,
                                                        style = MaterialTheme.typography.bodyMedium.copy(
                                                            color = MaterialTheme.colorScheme.primary,
                                                            textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                                                            fontWeight = FontWeight.Medium
                                                        ),
                                                        modifier = Modifier
                                                            .weight(1f)
                                                            .clickable {
                                                                runCatching {
                                                                    val encoded = java.net.URLEncoder.encode(value.trim(), "UTF-8")
                                                                    uriHandler.openUri("https://www.google.com/search?q=$encoded")
                                                                }
                                                            },
                                                        textAlign = androidx.compose.ui.text.style.TextAlign.End,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                } else {
                                                    Text(
                                                        text = value,
                                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                                        color = MaterialTheme.colorScheme.onSurface,
                                                        modifier = Modifier.weight(1f),
                                                        textAlign = androidx.compose.ui.text.style.TextAlign.End,
                                                        maxLines = 2,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            ItemBarcodeSection(
                                item = currentItem,
                                barcodeStore = barcodeStore,
                                scope = coroutineScope,
                                onRefresh = {
                                    coroutineScope.launch {
                                        val loaded = withContext(Dispatchers.IO) { repository.getItem(itemId) }
                                        item = loaded
                                    }
                                }
                            )



                            // Comments: thread first, compact composer underneath. The old
                            // Comments/Actions tabs are gone — "Actions" only ever showed a placeholder.
                            DetailSection(
                                title = "Comments",
                                subtitle = if (comments.isEmpty()) null else "${comments.size}"
                            ) {
                                if (comments.isEmpty()) {
                                    Text(
                                        "No comments yet. Leave a note for the next person.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                } else {
                                    comments.forEach { comment ->
                                        CommentCard(
                                            comment = comment,
                                            onDelete = {
                                                coroutineScope.launch {
                                                    withContext(Dispatchers.IO) {
                                                        repository.deleteComment(currentItem.id, comment.id)
                                                    }
                                                    comments = withContext(Dispatchers.IO) {
                                                        repository.getComments(currentItem.id)
                                                    }
                                                }
                                            },
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                }
                                HorizontalDivider(
                                    modifier = Modifier.padding(vertical = 4.dp),
                                    thickness = 0.5.dp,
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.Bottom,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    // Name only when the tablet has no employee set; it shares the
                                    // composer row instead of taking a full-width line.
                                    if (employeeName.isBlank()) {
                                        OutlinedTextField(
                                            value = commentAuthor,
                                            onValueChange = { commentAuthor = it },
                                            placeholder = { Text("Name", maxLines = 1) },
                                            modifier = Modifier.weight(0.35f),
                                            singleLine = true,
                                            shape = RoundedCornerShape(10.dp)
                                        )
                                    }
                                    OutlinedTextField(
                                        value = commentText,
                                        onValueChange = { commentText = it },
                                        placeholder = { Text("Add a comment…") },
                                        modifier = Modifier.weight(1f),
                                        minLines = 1,
                                        maxLines = 4,
                                        shape = RoundedCornerShape(10.dp)
                                    )
                                    FilledIconButton(
                                        onClick = {
                                            val author = commentAuthor.ifBlank { "Unknown" }
                                            val text = commentText.trim()
                                            if (text.isNotBlank()) {
                                                coroutineScope.launch {
                                                    isSubmittingComment = true
                                                    try {
                                                        withContext(Dispatchers.IO) {
                                                            repository.addComment(itemId, author, text, tabletId)
                                                        }
                                                        commentText = ""
                                                        val updated = withContext(Dispatchers.IO) {
                                                            repository.getComments(itemId)
                                                        }
                                                        comments = updated
                                                    } catch (_: Exception) {
                                                    } finally {
                                                        isSubmittingComment = false
                                                    }
                                                }
                                            }
                                        },
                                        enabled = commentText.isNotBlank() && commentAuthor.isNotBlank() && !isSubmittingComment,
                                        modifier = Modifier.size(52.dp)
                                    ) {
                                        if (isSubmittingComment) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(18.dp),
                                                strokeWidth = 2.dp
                                            )
                                        } else {
                                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Post comment")
                                        }
                                    }
                                }
                            }
                        }

                        // RIGHT COLUMN (32% width)
                        Column(
                            modifier = Modifier
                                .weight(0.32f)
                                .verticalScroll(rememberScrollState())
                                .padding(bottom = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            DetailSection(title = "Status") {
                                Box {
                                    val tier = SUPPLY_STATUS_PRIORITY[currentItem.status] ?: 99
                                    val statusColor = supplyStatusColor(tier)
                                    val (statusBg, statusText) = getSoftStatusColors(currentItem.status, statusColor)
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(statusBg)
                                            .clickable { showLabelsDropdown = true }
                                            .padding(horizontal = 14.dp, vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            currentItem.status.uppercase(),
                                            style = MaterialTheme.typography.labelLarge.copy(
                                                fontWeight = FontWeight.Bold,
                                                letterSpacing = 0.8.sp
                                            ),
                                            color = statusText,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Icon(Icons.Filled.ArrowDropDown, contentDescription = "Change status", tint = statusText)
                                    }
                                    DropdownMenu(
                                        expanded = showLabelsDropdown,
                                        onDismissRequest = { showLabelsDropdown = false }
                                    ) {
                                        ALL_SUPPLY_STATUSES.forEach { status ->
                                            val tier = SUPPLY_STATUS_PRIORITY[status] ?: 99
                                            val baseColor = supplyStatusColor(tier)
                                            val (chipBgColor, chipTextColor) = getSoftStatusColors(status, baseColor)
                                            val isSelected = currentItem.status == status
                                            DropdownMenuItem(
                                                onClick = {
                                                    showLabelsDropdown = false
                                                    coroutineScope.launch {
                                                        withContext(Dispatchers.IO) {
                                                            runCatching {
                                                                repository.setStatus(
                                                                    itemId, status,
                                                                    employeeName.ifBlank { "Floor" }, tabletId
                                                                )
                                                            }
                                                        }
                                                        val updated = withContext(Dispatchers.IO) {
                                                            runCatching { repository.getItem(itemId) }.getOrNull()
                                                        }
                                                        if (updated != null) item = updated
                                                    }
                                                },
                                                text = {
                                                    Row(
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                                    ) {
                                                        StatusChip(
                                                            text = status,
                                                            backgroundColor = chipBgColor,
                                                            contentColor = chipTextColor
                                                        )
                                                        if (isSelected) {
                                                            Icon(
                                                                imageVector = Icons.Filled.CheckCircle,
                                                                contentDescription = "Current",
                                                                tint = MaterialTheme.colorScheme.primary,
                                                                modifier = Modifier.size(16.dp)
                                                            )
                                                        }
                                                    }
                                                },
                                                modifier = Modifier.heightIn(min = 36.dp)
                                            )
                                        }
                                    }
                                }
                                val statusMeta = remember(currentItem.statusAt, currentItem.statusBy) {
                                    supplyStatusMetaLabel(currentItem)
                                }
                                if (statusMeta.isNotEmpty()) {
                                    Text(
                                        statusMeta,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            DetailSection(
                                title = "Photos",
                                subtitle = currentItem.attachmentIds.size.takeIf { it > 0 }
                                    ?.let { "$it file${if (it == 1) "" else "s"}" }
                            ) {
                                // Existing attachments
                                val attachmentCount = currentItem.attachmentIds.size
                                currentItem.attachmentIds.forEach { att ->
                                    val attFile = repository.getAttachmentFile(currentItem.id, att.storedName)
                                    val isImage = att.storedName.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS
                                    if (isImage) {
                                        AsyncImage(
                                            model = ImageRequest.Builder(context)
                                                .data(attFile)
                                                .crossfade(true)
                                                .build(),
                                            contentDescription = att.originalName,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .heightIn(max = 140.dp)
                                                .clip(RoundedCornerShape(9.dp)),
                                            contentScale = ContentScale.Crop
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            att.originalName,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))
                                    } else {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                att.originalName,
                                                style = MaterialTheme.typography.bodyMedium,
                                                modifier = Modifier.weight(1f),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(4.dp))
                                    }
                                }

                                // Add attachment buttons
                                Spacer(modifier = Modifier.height(8.dp))
                                if (isAddingAttachment) {
                                    Box(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                                    }
                                } else if (attachmentCount == 0) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        Card(
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(96.dp)
                                                .clickable { launchCamera() },
                                            shape = RoundedCornerShape(9.dp),
                                            colors = CardDefaults.cardColors(
                                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                                            )
                                        ) {
                                            Column(
                                                modifier = Modifier.fillMaxSize(),
                                                verticalArrangement = Arrangement.Center,
                                                horizontalAlignment = Alignment.CenterHorizontally
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Filled.PhotoCamera,
                                                    contentDescription = "Camera",
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.size(28.dp)
                                                )
                                                Spacer(modifier = Modifier.height(6.dp))
                                                Text(
                                                    "Camera",
                                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                        
                                        Card(
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(96.dp)
                                                .clickable { galleryLauncher.launch("image/*") },
                                            shape = RoundedCornerShape(9.dp),
                                            colors = CardDefaults.cardColors(
                                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                                            )
                                        ) {
                                            Column(
                                                modifier = Modifier.fillMaxSize(),
                                                verticalArrangement = Arrangement.Center,
                                                horizontalAlignment = Alignment.CenterHorizontally
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Filled.Image,
                                                    contentDescription = "Gallery",
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.size(28.dp)
                                                )
                                                Spacer(modifier = Modifier.height(6.dp))
                                                Text(
                                                    "Gallery",
                                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }
                                } else {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedButton(
                                            onClick = { launchCamera() },
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                                        ) {
                                            Icon(
                                                Icons.Filled.PhotoCamera,
                                                contentDescription = "Camera icon",
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Camera", style = MaterialTheme.typography.labelMedium)
                                        }
                                        OutlinedButton(
                                            onClick = { galleryLauncher.launch("image/*") },
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                                        ) {
                                            Icon(
                                                Icons.Filled.Image,
                                                contentDescription = "Gallery icon",
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Gallery", style = MaterialTheme.typography.labelMedium)
                                        }
                                    }
                                }
                            }

                            OutlinedButton(
                                onClick = { showDeleteConfirmDialog = true },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                            ) {
                                Icon(Icons.Outlined.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Delete item")
                            }
                        }
                    }
                } else {
                    // Fall back to original single column LazyColumn layout
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // Item Title and Status Header
                        item {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = currentItem.name,
                                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    val tier = SUPPLY_STATUS_PRIORITY[currentItem.status] ?: 99
                                    val baseColor = supplyStatusColor(tier)
                                    val (chipBgColor, chipTextColor) = getSoftStatusColors(currentItem.status, baseColor)
                                    StatusChip(
                                        text = currentItem.status,
                                        backgroundColor = chipBgColor,
                                        contentColor = chipTextColor,
                                        modifier = Modifier
                                            .border(
                                                BorderStroke(0.5.dp, chipTextColor.copy(alpha = 0.25f)),
                                                shape = CircleShape
                                            )
                                            .clickable { showStatusSheet = true }
                                    )
                                    Text(
                                        "Tap to change status",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        // Notes
                        if (!currentItem.notes.isNullOrBlank()) {
                            item {
                                DetailSection(title = "Notes", accent = supplyAccent(currentItem.status)) {
                                    MarkdownText(currentItem.notes, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }

                        // Fields, rendered from the current schema (non-blank values only).
                        // Orphan values (keys not in the current schema) are intentionally hidden.
                        data class DetailField(val label: String, val value: String, val type: String, val key: String)
                        val detailFields = schema.mapNotNull { f ->
                            val v = (currentItem.fields[f.key] ?: currentItem.customFields[f.key])
                                ?.takeIf { it.isNotBlank() }
                                ?.takeUnless { f.key == "sku" && it in currentItem.barcodes }
                            v?.let { DetailField(f.label, it, f.type, f.key) }
                        }
                        if (detailFields.isNotEmpty()) {
                            item {
                                val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
                                DetailSection(title = "Details") {
                                    Column(
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                                    ) {
                                        detailFields.forEachIndexed { index, df ->
                                            val label = df.label
                                            val value = df.value
                                            if (index > 0) {
                                                HorizontalDivider(
                                                    thickness = 0.5.dp,
                                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                                                )
                                            }
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(vertical = 4.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    label,
                                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                                
                                                val isUrl = df.type == "url" && (value.startsWith("http://") || value.startsWith("https://"))
                                                val isTracking = df.key == "trackingNumber" && value.isNotBlank()
                                                
                                                if (isUrl) {
                                                    Text(
                                                        text = value,
                                                        style = MaterialTheme.typography.bodyMedium.copy(
                                                            color = MaterialTheme.colorScheme.primary,
                                                            textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                                                            fontWeight = FontWeight.Medium
                                                        ),
                                                        modifier = Modifier
                                                            .weight(1f)
                                                            .clickable { runCatching { uriHandler.openUri(value) } },
                                                        textAlign = androidx.compose.ui.text.style.TextAlign.End,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                } else if (isTracking) {
                                                    Text(
                                                        text = value,
                                                        style = MaterialTheme.typography.bodyMedium.copy(
                                                            color = MaterialTheme.colorScheme.primary,
                                                            textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                                                            fontWeight = FontWeight.Medium
                                                        ),
                                                        modifier = Modifier
                                                            .weight(1f)
                                                            .clickable {
                                                                runCatching {
                                                                    val encoded = java.net.URLEncoder.encode(value.trim(), "UTF-8")
                                                                    uriHandler.openUri("https://www.google.com/search?q=$encoded")
                                                                }
                                                            },
                                                        textAlign = androidx.compose.ui.text.style.TextAlign.End,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                } else {
                                                    Text(
                                                        text = value,
                                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                                        color = MaterialTheme.colorScheme.onSurface,
                                                        modifier = Modifier.weight(1f),
                                                        textAlign = androidx.compose.ui.text.style.TextAlign.End,
                                                        maxLines = 2,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Attachments
                        item {
                            val attachmentCount = currentItem.attachmentIds.size
                            DetailSection(
                                title = "Photos & Attachments",
                                subtitle = if (attachmentCount == 0) null else {
                                    "$attachmentCount file${if (attachmentCount == 1) "" else "s"}"
                                }
                            ) {
                                // Existing attachments
                                currentItem.attachmentIds.forEach { att ->
                                    val attFile = repository.getAttachmentFile(currentItem.id, att.storedName)
                                    val isImage = att.storedName.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS
                                    if (isImage) {
                                        AsyncImage(
                                            model = ImageRequest.Builder(context)
                                                .data(attFile)
                                                .crossfade(true)
                                                .build(),
                                            contentDescription = att.originalName,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .heightIn(max = 280.dp)
                                                .clip(RoundedCornerShape(9.dp)),
                                            contentScale = ContentScale.FillWidth
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            att.originalName,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))
                                    } else {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                att.originalName,
                                                style = MaterialTheme.typography.bodyMedium,
                                                modifier = Modifier.weight(1f),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(4.dp))
                                    }
                                }

                                // Add attachment buttons
                                Spacer(modifier = Modifier.height(8.dp))
                                if (isAddingAttachment) {
                                    Box(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                                    }
                                } else if (attachmentCount == 0) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        Card(
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(96.dp)
                                                .clickable { launchCamera() },
                                            shape = RoundedCornerShape(9.dp),
                                            colors = CardDefaults.cardColors(
                                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                                            )
                                        ) {
                                            Column(
                                                modifier = Modifier.fillMaxSize(),
                                                verticalArrangement = Arrangement.Center,
                                                horizontalAlignment = Alignment.CenterHorizontally
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Filled.PhotoCamera,
                                                    contentDescription = "Camera",
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.size(28.dp)
                                                )
                                                Spacer(modifier = Modifier.height(6.dp))
                                                Text(
                                                    "Camera",
                                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                        
                                        Card(
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(96.dp)
                                                .clickable { galleryLauncher.launch("image/*") },
                                            shape = RoundedCornerShape(9.dp),
                                            colors = CardDefaults.cardColors(
                                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                                            )
                                        ) {
                                            Column(
                                                modifier = Modifier.fillMaxSize(),
                                                verticalArrangement = Arrangement.Center,
                                                horizontalAlignment = Alignment.CenterHorizontally
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Filled.Image,
                                                    contentDescription = "Gallery",
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.size(28.dp)
                                                )
                                                Spacer(modifier = Modifier.height(6.dp))
                                                Text(
                                                    "Gallery",
                                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }
                                } else {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedButton(
                                            onClick = { launchCamera() },
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                                        ) {
                                            Icon(
                                                Icons.Filled.PhotoCamera,
                                                contentDescription = "Camera icon",
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Camera", style = MaterialTheme.typography.labelMedium)
                                        }
                                        OutlinedButton(
                                            onClick = { galleryLauncher.launch("image/*") },
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                                        ) {
                                            Icon(
                                                Icons.Filled.Image,
                                                contentDescription = "Gallery icon",
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Gallery", style = MaterialTheme.typography.labelMedium)
                                        }
                                    }
                                }
                            }
                        }

                        // Barcodes section
                        item {
                            ItemBarcodeSection(
                                item = currentItem,
                                barcodeStore = barcodeStore,
                                scope = coroutineScope,
                                onRefresh = {
                                    coroutineScope.launch {
                                        val loaded = withContext(Dispatchers.IO) { repository.getItem(itemId) }
                                        item = loaded
                                    }
                                }
                            )
                        }
                        item { Spacer(modifier = Modifier.height(16.dp)) }
                        item { HorizontalDivider() }

                        // Comments header
                        item {
                            DashboardSectionHeader(
                                title = "Comments",
                                subtitle = if (comments.isEmpty()) null else {
                                    "${comments.size} comment${if (comments.size == 1) "" else "s"}"
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        // Comment list
                        if (comments.isEmpty()) {
                            item {
                                Text(
                                    "No comments yet.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        } else {
                            items(comments, key = { it.id }) { comment ->
                                CommentCard(
                                    comment = comment,
                                    onDelete = {
                                        coroutineScope.launch {
                                            withContext(Dispatchers.IO) {
                                                repository.deleteComment(currentItem.id, comment.id)
                                            }
                                            comments = withContext(Dispatchers.IO) {
                                                repository.getComments(currentItem.id)
                                            }
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }

                        // Add comment form
                        item {
                            DashboardSurfaceCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(9.dp)) {
                                Column(
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text("Add Comment", style = MaterialTheme.typography.labelLarge)
                                    if (employeeName.isBlank()) {
                                        OutlinedTextField(
                                            value = commentAuthor,
                                            onValueChange = { commentAuthor = it },
                                            label = { Text("Your Name") },
                                            modifier = Modifier.fillMaxWidth(),
                                            singleLine = true,
                                            shape = RoundedCornerShape(4.dp)
                                        )
                                    }
                                    OutlinedTextField(
                                        value = commentText,
                                        onValueChange = { commentText = it },
                                        label = { Text("Comment") },
                                        modifier = Modifier.fillMaxWidth(),
                                        minLines = 2,
                                        maxLines = 4,
                                        shape = RoundedCornerShape(4.dp)
                                    )
                                    Button(
                                        onClick = {
                                            val author = commentAuthor.ifBlank { "Unknown" }
                                            val text = commentText.trim()
                                            if (text.isNotBlank()) {
                                                coroutineScope.launch {
                                                    isSubmittingComment = true
                                                    try {
                                                        withContext(Dispatchers.IO) {
                                                            repository.addComment(itemId, author, text, tabletId)
                                                        }
                                                        commentText = ""
                                                        val updated = withContext(Dispatchers.IO) {
                                                            repository.getComments(itemId)
                                                        }
                                                        comments = updated
                                                    } catch (_: Exception) {
                                                    } finally {
                                                        isSubmittingComment = false
                                                    }
                                                }
                                            }
                                        },
                                        enabled = commentText.isNotBlank() && commentAuthor.isNotBlank() && !isSubmittingComment,
                                        modifier = Modifier.align(Alignment.End)
                                    ) {
                                        if (isSubmittingComment) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(16.dp),
                                                strokeWidth = 2.dp
                                            )
                                        } else {
                                            Text("Submit")
                                        }
                                    }
                                }
                            }
                        }

                        item { Spacer(modifier = Modifier.height(32.dp)) }
                    }
                }
            }
        }

        // Delete Confirm Dialog
        if (showDeleteConfirmDialog && currentItem != null) {
            AlertDialog(
                onDismissRequest = { showDeleteConfirmDialog = false },
                title = { Text("Delete Item?") },
                text = { Text("Are you sure you want to delete ${currentItem.name}? This will remove the item permanently across all devices.") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showDeleteConfirmDialog = false
                            coroutineScope.launch {
                                withContext(Dispatchers.IO) {
                                    repository.deleteItem(currentItem.id)
                                }
                                onBack() // close detail view
                            }
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) { Text("Delete") }
                },
                dismissButton = { TextButton(onClick = { showDeleteConfirmDialog = false }) { Text("Cancel") } }
            )
        }
    }

    // Status change sheet
    if (showStatusSheet && currentItem != null) {
        SupplyPickerDialog(
            title = "Change Status",
            options = ALL_SUPPLY_STATUSES.map { status ->
                val tier = SUPPLY_STATUS_PRIORITY[status] ?: 99
                val color = supplyStatusColor(tier)
                SupplyPickerOption(
                    id = status,
                    label = status,
                    selected = currentItem.status == status,
                    onClick = {
                        coroutineScope.launch {
                            showStatusSheet = false
                            withContext(Dispatchers.IO) {
                                runCatching {
                                    repository.setStatus(
                                        itemId, status,
                                        employeeName.ifBlank { "Floor" }, tabletId
                                    )
                                }
                            }
                            val updated = withContext(Dispatchers.IO) {
                                runCatching { repository.getItem(itemId) }.getOrNull()
                            }
                            if (updated != null) item = updated
                        }
                    },
                    icon = {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .background(color, CircleShape)
                        )
                    }
                )
            },
            onDismiss = { showStatusSheet = false },
            headerTint = supplyStatusHeaderTint(currentItem.status)
        )
    }

    // Scanner overlay (per-item mode)
    if (scanMode is ScanMode.Item && currentItem != null && (scanMode as ScanMode.Item).itemId == currentItem.id) {
        val item = currentItem
        SupplyScannerOverlay(
            barcodeStore = barcodeStore,
            isModalActive = itemScanResult != null,
            onDismiss = { barcodeStore.setScanMode(ScanMode.Idle) },
            onKnownBarcode = { foundItem, barcode ->
                if (foundItem.id == item.id) {
                    barcodeStore.setScanMode(ScanMode.Idle)
                } else {
                    itemScanResult = barcode
                }
            },
            onUnknownBarcode = { barcode -> itemScanResult = barcode }
        )

        itemScanResult?.let { barcode ->
            val onOtherItem = barcodeStore.lookup(barcode)?.let { it != item.id } ?: false
            AlertDialog(
                onDismissRequest = { itemScanResult = null },
                title = { Text(if (onOtherItem) "Move barcode?" else "Link barcode?") },
                text = {
                    if (onOtherItem)
                        Text("This barcode is linked to another item. Move it to ${item.name}?")
                    else
                        Text("Link \"${barcode.take(24)}\" to ${item.name}?")
                },
                confirmButton = {
                    TextButton(onClick = {
                        itemScanResult = null
                        barcodeStore.setScanMode(ScanMode.Idle)
                        coroutineScope.launch { barcodeStore.link(barcode, item.id); loadData() }
                    }) { Text(if (onOtherItem) "Move" else "Link") }
                },
                dismissButton = {
                    TextButton(onClick = { itemScanResult = null }) { Text("Cancel") }
                }
            )
        }
    }
}

/** "For 72d by Kevin Palmer" — how long the item has held its status and who set it. */
internal fun supplyStatusMetaLabel(item: SupplyItem): String {
    val ageLabel = supplyStatusAgeLabel(item.statusAt)
    return listOfNotNull(
        ageLabel?.let { if (it == "today") "Set today" else "For $it" },
        item.statusBy.takeIf { it.isNotBlank() }?.let { "by $it" }
    ).joinToString(" ")
}

@Composable
private fun DetailSection(
    title: String,
    subtitle: String? = null,
    accent: DashboardAccent = DashboardAccent.NEUTRAL,
    content: @Composable ColumnScope.() -> Unit
) {
    DashboardSurfaceCard(accent = accent, shape = RoundedCornerShape(9.dp)) {
        DashboardSectionHeader(title = title, subtitle = subtitle)
        content()
    }
}

@Composable
private fun CommentCard(
    comment: SupplyComment,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val formattedTime = remember(comment.createdAt) {
        runCatching {
            val instant = Instant.parse(comment.createdAt)
            DateTimeFormatter
                .ofLocalizedDateTime(FormatStyle.SHORT)
                .withZone(ZoneId.systemDefault())
                .format(instant)
        }.getOrElse { comment.createdAt }
    }
    val initials = remember(comment.author) {
        comment.author.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.let { parts ->
            when {
                parts.isEmpty() -> "?"
                parts.size == 1 -> parts[0].take(2)
                else -> parts.first().take(1) + parts.last().take(1)
            }
        }.uppercase()
    }

    DashboardSurfaceCard(
        modifier = modifier,
        contentPadding = PaddingValues(12.dp),
        shape = RoundedCornerShape(9.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    initials,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        comment.author,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            formattedTime,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        IconButton(onClick = onDelete, modifier = Modifier.size(24.dp)) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Delete Comment",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }
                MarkdownText(comment.text, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ItemBarcodeSection(
    item: SupplyItem,
    barcodeStore: SupplyBarcodeStore,
    scope: kotlinx.coroutines.CoroutineScope,
    onRefresh: () -> Unit
) {
    var confirmRemoveBarcode by remember { mutableStateOf<String?>(null) }

    DetailSection(
        title = "Barcodes",
        subtitle = if (item.barcodes.isEmpty()) null else "${item.barcodes.size} linked"
    ) {
        if (item.barcodes.isEmpty()) {
            Text("No barcodes linked.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            item.barcodes.forEachIndexed { index, barcode ->
                if (index > 0) {
                    HorizontalDivider(
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        Icons.Filled.QrCodeScanner, null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        barcode,
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.weight(1f, fill = false),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (barcode == item.fields["sku"]) {
                        Text(
                            "SKU",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(MaterialTheme.colorScheme.secondaryContainer)
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { confirmRemoveBarcode = barcode }) {
                        Icon(Icons.Outlined.Delete, "Remove barcode",
                            tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                            modifier = Modifier.size(20.dp))
                    }
                }
            }
        }

        TextButton(
            onClick = { barcodeStore.setScanMode(ScanMode.Item(item.id)) },
            // Zero start padding so the icon lines up with the section text above.
            contentPadding = PaddingValues(start = 0.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
            modifier = Modifier.align(Alignment.Start)
        ) {
            Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Add barcode")
        }
    }

    // Remove confirmation dialog
    confirmRemoveBarcode?.let { barcode ->
        AlertDialog(
            onDismissRequest = { confirmRemoveBarcode = null },
            title = { Text("Remove barcode?") },
            text = { Text("Remove \"${barcode.take(24)}\" from ${item.name}?") },
            confirmButton = {
                TextButton(
                    onClick = { confirmRemoveBarcode = null; scope.launch { barcodeStore.unlink(barcode); onRefresh() } },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { confirmRemoveBarcode = null }) { Text("Cancel") } }
        )
    }

}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SideActionButton(
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    checked: Boolean = false,
    bold: Boolean = false,
    interactive: Boolean = true
) {
    val isDark = LocalKKCIsDarkTheme.current
    val bgColor = if (checked) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        if (isDark) Color(0xFF2E3B4E) else Color(0xFFE4E8ED)
    }
    val textColor = if (checked) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        if (isDark) Color(0xFFE2EDF7) else Color(0xFF1E2A38)
    }
    val shape = RoundedCornerShape(6.dp)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .shadow(elevation = 1.5.dp, shape = shape, clip = false)
            .clip(shape)
            .background(bgColor)
            .then(
                if (interactive) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = textColor.copy(alpha = 0.8f),
            modifier = Modifier.size(16.dp)
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium),
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
