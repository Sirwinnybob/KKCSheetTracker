package com.kkc.sheettracker.ui.supply

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.*
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import com.kkc.sheettracker.ui.components.headerBackground
import com.kkc.sheettracker.ui.components.ImmersiveDialogDecor
import androidx.compose.ui.platform.LocalContext
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

    val subscriptionData by subscriptionManager.subscriptionData.collectAsState()
    val isSubscribed = subscriptionData.subscribedItemIds.contains(itemId)

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
    val statusSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

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

    Scaffold(
        topBar = {
            TopAppBar(
                modifier = Modifier.headerBackground(),
                title = {
                    Text(
                        currentItem?.name ?: "Supply Item",
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        coroutineScope.launch {
                            subscriptionManager.toggleItemSubscription(itemId)
                        }
                    }) {
                        Icon(
                            imageVector = if (isSubscribed) Icons.Filled.Notifications else Icons.Outlined.Notifications,
                            contentDescription = if (isSubscribed) "Unsubscribe from notifications" else "Subscribe to notifications",
                            tint = if (isSubscribed) MaterialTheme.colorScheme.primary else LocalContentColor.current
                        )
                    }
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Filled.Edit, contentDescription = "Edit item")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                ),
                windowInsets = WindowInsets.statusBars
            )
        }
    ) { padding ->
        when {
            isLoading -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator() }
            }
            errorMessage != null -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
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
                val tier = SUPPLY_STATUS_PRIORITY[currentItem.status] ?: 99
                val statusColor = supplyStatusColor(tier)

                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Status chip
                    item {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("Status:", style = MaterialTheme.typography.labelLarge)
                            AssistChip(
                                onClick = { showStatusSheet = true },
                                label = { Text(currentItem.status) },
                                leadingIcon = {
                                    Box(
                                        modifier = Modifier
                                            .size(10.dp)
                                            .background(statusColor, CircleShape)
                                    )
                                }
                            )
                        }
                    }

                    // Notes
                    if (!currentItem.notes.isNullOrBlank()) {
                        item {
                            DetailSection(title = "Notes") {
                                Text(currentItem.notes, style = MaterialTheme.typography.bodyMedium)
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
                        DetailSection(title = "Photos & Attachments") {
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
                        Text("Comments", style = MaterialTheme.typography.titleMedium)
                        HorizontalDivider(modifier = Modifier.padding(top = 4.dp))
                    }

                    // Comment list
                    if (comments.isEmpty()) {
                        item {
                            Text(
                                "No comments yet.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        items(comments, key = { it.id }) { comment ->
                            CommentCard(comment = comment)
                        }
                    }

                    // Add comment form
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.medium
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
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
        ModalBottomSheet(
            onDismissRequest = { showStatusSheet = false },
            sheetState = statusSheetState
        ) {
            ImmersiveDialogDecor()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 32.dp)
            ) {
                Text(
                    "Change Status",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
                HorizontalDivider()
                ALL_SUPPLY_STATUSES.forEach { status ->
                    val tier = SUPPLY_STATUS_PRIORITY[status] ?: 99
                    val color = supplyStatusColor(tier)
                    NavigationDrawerItem(
                        label = { Text(status) },
                        selected = currentItem.status == status,
                        onClick = {
                            coroutineScope.launch {
                                statusSheetState.hide()
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
                        },
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            HorizontalDivider()
            content()
        }
    }
}

@Composable
private fun CommentCard(comment: SupplyComment) {
    val formattedTime = remember(comment.createdAt) {
        runCatching {
            val instant = Instant.parse(comment.createdAt)
            DateTimeFormatter
                .ofLocalizedDateTime(FormatStyle.SHORT)
                .withZone(ZoneId.systemDefault())
                .format(instant)
        }.getOrElse { comment.createdAt }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    comment.author,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    formattedTime,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(comment.text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
