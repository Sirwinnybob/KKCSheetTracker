package com.kkc.sheettracker.ui.supply

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.data.SupplyRepository
import com.kkc.sheettracker.data.models.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SupplyItemDetailScreen(
    itemId: String,
    serverUrl: String,
    employeeName: String,
    onBack: () -> Unit
) {
    val repository = remember(serverUrl) { SupplyRepository(serverUrl) }
    val coroutineScope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current

    var item by remember { mutableStateOf<SupplyItem?>(null) }
    var comments by remember { mutableStateOf<List<SupplyComment>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var commentText by remember { mutableStateOf("") }
    var commentAuthor by remember { mutableStateOf(employeeName) }
    var isSubmittingComment by remember { mutableStateOf(false) }

    // Status change bottom sheet
    var showStatusSheet by remember { mutableStateOf(false) }
    val statusSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    fun loadData() {
        coroutineScope.launch {
            isLoading = true
            errorMessage = null
            try {
                val loadedItem = withContext(Dispatchers.IO) {
                    // getItems and filter by id (no single-item endpoint given)
                    repository.getItems().find { it.id == itemId }
                        ?: throw Exception("Item not found")
                }
                val loadedComments = withContext(Dispatchers.IO) {
                    repository.getComments(itemId)
                }
                item = loadedItem
                comments = loadedComments
            } catch (e: Exception) {
                errorMessage = e.message ?: "Failed to load item"
            } finally {
                isLoading = false
            }
        }
    }

    LaunchedEffect(itemId) { loadData() }

    val currentItem = item

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        currentItem?.name ?: "Supply Item",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { padding ->
        when {
            isLoading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
            errorMessage != null -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
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
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
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
                                Text(
                                    currentItem.notes,
                                    style = MaterialTheme.typography.bodyMedium
                                )
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
                    if (currentItem.attachmentIds.isNotEmpty()) {
                        item {
                            DetailSection(title = "Attachments") {
                                currentItem.attachmentIds.forEach { att ->
                                    val url = repository.attachmentUrl(currentItem.id, att.id)
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            att.originalName,
                                            style = MaterialTheme.typography.bodyMedium,
                                            modifier = Modifier.weight(1f),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        IconButton(
                                            onClick = {
                                                runCatching { uriHandler.openUri(url) }
                                            }
                                        ) {
                                            Icon(
                                                Icons.Filled.Download,
                                                contentDescription = "Download ${att.originalName}",
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Comments section header
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
                                                        repository.addComment(itemId, author, text)
                                                    }
                                                    commentText = ""
                                                    // Reload comments
                                                    val updated = withContext(Dispatchers.IO) {
                                                        repository.getComments(itemId)
                                                    }
                                                    comments = updated
                                                } catch (_: Exception) {
                                                    // Ignore — comment may have been saved even if response fails
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

                    // Bottom padding
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
                                try {
                                    val updated = withContext(Dispatchers.IO) {
                                        repository.patchStatus(itemId, status)
                                    }
                                    item = updated
                                } catch (_: Exception) {
                                    // silently ignore
                                }
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
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
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
