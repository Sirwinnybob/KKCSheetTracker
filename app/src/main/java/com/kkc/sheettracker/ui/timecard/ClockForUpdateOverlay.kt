package com.kkc.sheettracker.ui.timecard

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.data.TimecardDiscovery
import com.kkc.sheettracker.data.TimecardServerConfig
import com.kkc.sheettracker.data.TimeclockMessagesRepository
import kotlinx.coroutines.delay
import java.io.File

/**
 * Full-screen, navigation-agnostic timeclock overlay shown from the update prompt so a worker can
 * clock in/out quickly before kicking off the (slow) app update. It owns its own [TimecardStore]
 * so it works regardless of which navigation host is active.
 *
 * [onFinished] is invoked — restoring the update prompt — when the worker either completes a
 * successful punch (after a brief confirmation pause) or backs out of the overlay.
 */
@Composable
fun ClockForUpdateOverlay(
    basePath: String,
    onFinished: () -> Unit
) {
    val context = LocalContext.current
    val store = remember {
        TimecardStore(
            config = TimecardServerConfig.create(context),
            discovery = TimecardDiscovery(context),
            messagesRepo = TimeclockMessagesRepository(File(basePath)),
            baseDir = File(basePath)
        )
    }
    DisposableEffect(store) { onDispose { store.cancel() } }

    // Back out of the overlay → restore the update prompt (safety net if they don't punch).
    BackHandler { onFinished() }

    // Auto-return to the update prompt right after a successful punch. The store keeps the
    // confirmation message up for ~5.5s; pause briefly so the worker sees "Clocked in/out".
    val punchCount by store.punchCompletions.collectAsState()
    LaunchedEffect(punchCount) {
        if (punchCount > 0) {
            delay(2200)
            onFinished()
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            TextButton(
                onClick = onFinished,
                modifier = Modifier
                    .statusBarsPadding()
                    .padding(start = 4.dp, top = 4.dp)
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                Text("  Back to update", style = MaterialTheme.typography.labelLarge)
            }
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                TimecardScreen(store = store)
            }
        }
    }
}
