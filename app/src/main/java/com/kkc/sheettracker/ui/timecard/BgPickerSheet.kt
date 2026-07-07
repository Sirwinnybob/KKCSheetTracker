package com.kkc.sheettracker.ui.timecard

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.GetContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.ui.components.ImmersiveDialogDecor
import com.kkc.sheettracker.data.TimecardBgConfig
import com.kkc.sheettracker.data.TimecardBgStore
import com.kkc.sheettracker.data.TimecardBgType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class BgPhase { OPTIONS, COLOR_PICKER }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BgPickerSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val bgStore = remember { TimecardBgStore(context) }
    val scope = rememberCoroutineScope()
    val currentConfig by bgStore.configFlow.collectAsState(initial = TimecardBgConfig())
    var phase by remember { mutableStateOf(BgPhase.OPTIONS) }

    val imagePicker = rememberLauncherForActivityResult(GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val filename = "bg_image_${System.currentTimeMillis()}"
            val path = withContext(Dispatchers.IO) { copyToInternal(context, uri, filename) }
            if (path != null) bgStore.save(TimecardBgConfig(type = TimecardBgType.IMAGE, mediaPath = path))
            onDismiss()
        }
    }

    val videoPicker = rememberLauncherForActivityResult(GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val filename = "bg_video_${System.currentTimeMillis()}"
            val path = withContext(Dispatchers.IO) { copyToInternal(context, uri, filename) }
            if (path != null) bgStore.save(TimecardBgConfig(type = TimecardBgType.VIDEO, mediaPath = path))
            onDismiss()
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        ImmersiveDialogDecor()
        when (phase) {
            BgPhase.OPTIONS -> BgOptionsContent(
                currentType = currentConfig.type,
                onColorTap = { phase = BgPhase.COLOR_PICKER },
                onImageTap = { imagePicker.launch("image/*") },
                onVideoTap = { videoPicker.launch("video/*") },
                onClear = {
                    scope.launch {
                        bgStore.save(TimecardBgConfig())
                        onDismiss()
                    }
                }
            )
            BgPhase.COLOR_PICKER -> HsvColorPicker(
                initialColor = if (currentConfig.type == TimecardBgType.COLOR && currentConfig.color != 0)
                    Color(currentConfig.color) else Color(0xFF1565C0.toInt()),
                onColorSelected = { color ->
                    scope.launch {
                        bgStore.save(TimecardBgConfig(type = TimecardBgType.COLOR, color = color.toArgb()))
                    }
                    onDismiss()
                },
                onBack = { phase = BgPhase.OPTIONS }
            )
        }
    }
}

@Composable
private fun BgOptionsContent(
    currentType: TimecardBgType,
    onColorTap: () -> Unit,
    onImageTap: () -> Unit,
    onVideoTap: () -> Unit,
    onClear: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Timeclock Background",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            BgOptionCard(
                icon = Icons.Default.Palette,
                label = "Color",
                selected = currentType == TimecardBgType.COLOR,
                onClick = onColorTap,
                modifier = Modifier.weight(1f)
            )
            BgOptionCard(
                icon = Icons.Default.Image,
                label = "Photo / GIF",
                selected = currentType == TimecardBgType.IMAGE,
                onClick = onImageTap,
                modifier = Modifier.weight(1f)
            )
            BgOptionCard(
                icon = Icons.Default.PlayCircle,
                label = "Video",
                selected = currentType == TimecardBgType.VIDEO,
                onClick = onVideoTap,
                modifier = Modifier.weight(1f)
            )
        }

        if (currentType != TimecardBgType.NONE) {
            TextButton(
                onClick = onClear,
                modifier = Modifier.align(Alignment.CenterHorizontally),
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) {
                Text("Remove background")
            }
        }

        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun BgOptionCard(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val borderColor = if (selected)
        MaterialTheme.colorScheme.primary
    else
        MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
    val bgColor = if (selected)
        MaterialTheme.colorScheme.primaryContainer
    else
        MaterialTheme.colorScheme.surfaceVariant
    val iconTint = if (selected)
        MaterialTheme.colorScheme.onPrimaryContainer
    else
        MaterialTheme.colorScheme.onSurfaceVariant

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .border(1.5.dp, borderColor, RoundedCornerShape(12.dp))
            .background(bgColor)
            .clickable(onClick = onClick)
            .padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(28.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = iconTint)
    }
}

private fun copyToInternal(context: Context, uri: Uri, filename: String): String? {
    return try {
        val dest = TimecardBgStore.bgDir(context).resolve(filename)
        //noinspection Recycle -- already closed via .use{}; known lint false positive (issuetracker.google.com/issues/248675800)
        context.contentResolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        }
        dest.absolutePath
    } catch (e: Exception) {
        null
    }
}
