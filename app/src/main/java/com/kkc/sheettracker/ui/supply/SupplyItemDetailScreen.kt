package com.kkc.sheettracker.ui.supply

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import com.kkc.sheettracker.ui.components.MarkdownText
import com.kkc.sheettracker.ui.components.StatusChip
import com.kkc.sheettracker.ui.dashboard.DashboardSectionHeader
import com.kkc.sheettracker.ui.dashboard.DashboardSurfaceCard
import com.kkc.sheettracker.ui.dashboard.DashboardAccent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.kkc.sheettracker.data.SupplyRepository
import com.kkc.sheettracker.data.SupplySubscriptionManager
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
    subscriptionManager: SupplySubscriptionManager
) {
    val repository = remember(basePath) { SupplyRepository(basePath) }
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var item by remember { mutableStateOf<SupplyItem?>(null) }
    var comments by remember { mutableStateOf<List<SupplyComment>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var commentText by remember { mutableStateOf("") }
    var commentAuthor by remember { mutableStateOf(employeeName) }
    var isSubmittingComment by remember { mutableStateOf(false) }
    var isAddingAttachment by remember { mutableStateOf(false) }
    var showAttachmentOptions by remember { mutableStateOf(false) }
    var captureTargetFile by remember { mutableStateOf<File?>(null) }

    var showStatusSheet by remember { mutableStateOf(false) }

    fun loadData() {
        coroutineScope.launch {
            isLoading = true
            errorMessage = null
            try {
                val loadedItem = withContext(Dispatchers.IO) { repository.getItem(itemId) }
                if (loadedItem == null) {
                    errorMessage = "Item not found"
                } else {
                    val loadedComments = withContext(Dispatchers.IO) { repository.getComments(itemId) }
                    item = loadedItem
                    comments = loadedComments
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
        val dir = File(context.cacheDir, "supply_temp")
        dir.mkdirs()
        val f = File(dir, "capture_${System.currentTimeMillis()}.jpg")
        captureTargetFile = f
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", f)
        cameraLauncher.launch(uri)
    }

    val currentItem = item

    LaunchedEffect(currentItem, comments) {
        if (currentItem != null) {
            subscriptionManager.dismissNotification(currentItem.id)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        when {
            isLoading -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator() }
            }
            errorMessage != null -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
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
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Status pill
                    item {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            StatusChip(
                                text = currentItem.status,
                                backgroundColor = supplyStatusColor(SUPPLY_STATUS_PRIORITY[currentItem.status] ?: 99),
                                contentColor = Color.White,
                                modifier = Modifier.clickable { showStatusSheet = true }
                            )
                            Text(
                                "Tap to change status",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
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

                    // Built-in fields
                    val builtinFields = buildList {
                        currentItem.fields["sku"]?.takeIf { it.isNotBlank() }?.let { add("SKU" to it) }
                        currentItem.fields["quantity"]?.takeIf { it.isNotBlank() }?.let { add("Quantity" to it) }
                        currentItem.fields["vendorLink"]?.takeIf { it.isNotBlank() }?.let { add("Vendor Link" to it) }
                        currentItem.fields["trackingNumber"]?.takeIf { it.isNotBlank() }?.let { add("Tracking #" to it) }
                    }
                    if (builtinFields.isNotEmpty()) {
                        item {
                            DetailSection(title = "Details") {
                                builtinFields.forEach { (label, value) ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            "$label:",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            value,
                                            style = MaterialTheme.typography.bodyMedium,
                                            modifier = Modifier.weight(1f),
                                            textAlign = androidx.compose.ui.text.style.TextAlign.End
                                        )
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
                                            .clip(MaterialTheme.shapes.medium),
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
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (isAddingAttachment) {
                                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                                } else {
                                    OutlinedButton(
                                        onClick = { launchCamera() },
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                                    ) {
                                        Icon(
                                            Icons.Filled.Add,
                                            contentDescription = "Add icon",
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
                                            Icons.Filled.Add,
                                            contentDescription = "Add icon",
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Gallery", style = MaterialTheme.typography.labelMedium)
                                    }
                                }
                            }
                        }
                    }

                    // Comments header
                    item {
                        DashboardSectionHeader(
                            title = "Comments",
                            subtitle = if (comments.isEmpty()) null else {
                                "${comments.size} comment${if (comments.size == 1) "" else "s"}"
                            },
                            modifier = Modifier.fillMaxWidth(0.5f)
                        )
                    }

                    // Comment list
                    if (comments.isEmpty()) {
                        item {
                            Text(
                                "No comments yet.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth(0.5f)
                            )
                        }
                    } else {
                        items(comments, key = { it.id }) { comment ->
                            CommentCard(comment = comment, modifier = Modifier.fillMaxWidth(0.5f))
                        }
                    }

                    // Add comment form
                    item {
                        DashboardSurfaceCard(modifier = Modifier.fillMaxWidth(0.5f)) {
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
                                        shape = MaterialTheme.shapes.medium
                                    )
                                }
                                OutlinedTextField(
                                    value = commentText,
                                    onValueChange = { commentText = it },
                                    label = { Text("Comment") },
                                    modifier = Modifier.fillMaxWidth(),
                                    minLines = 2,
                                    maxLines = 4,
                                    shape = MaterialTheme.shapes.medium
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
            onDismiss = { showStatusSheet = false }
        )
    }
}

@Composable
private fun DetailSection(
    title: String,
    subtitle: String? = null,
    accent: DashboardAccent = DashboardAccent.NEUTRAL,
    content: @Composable ColumnScope.() -> Unit
) {
    DashboardSurfaceCard(accent = accent) {
        DashboardSectionHeader(title = title, subtitle = subtitle)
        content()
    }
}

@Composable
private fun CommentCard(comment: SupplyComment, modifier: Modifier = Modifier) {
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

    DashboardSurfaceCard(modifier = modifier, contentPadding = PaddingValues(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    initials,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary
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
                    Text(
                        formattedTime,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                MarkdownText(comment.text, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
