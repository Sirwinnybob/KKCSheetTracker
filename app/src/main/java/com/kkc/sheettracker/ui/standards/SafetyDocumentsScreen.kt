package com.kkc.sheettracker.ui.standards

import android.content.Intent
import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.kkc.sheettracker.ui.components.KKCTopAppBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Read-only Safety / SDS document list: a flat list of the PDFs published under `.safety` at the
 * root of the shared Ready Jobs tree (a sibling of `.metadata`, not nested under it). Tapping a
 * document launches the device's external PDF viewer via [FileProvider] — this screen never
 * renders PDFs itself and never writes anything.
 */
@Composable
fun SafetyDocumentsScreen(basePath: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val safetyDir = remember(basePath) { File(basePath, ".safety") }
    var files by remember { mutableStateOf<List<File>>(emptyList()) }

    LaunchedEffect(basePath) {
        files = withContext(Dispatchers.IO) { SafetyDocumentsScreenLogic.listPdfs(safetyDir) }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        KKCTopAppBar(
            title = { Text("Safety / SDS") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            }
        )

        if (files.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "No safety documents found.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(vertical = 4.dp)) {
                items(files, key = { it.name }) { file ->
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
    }
}
