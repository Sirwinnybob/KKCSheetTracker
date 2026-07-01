package com.kkc.sheettracker.ui.supply

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.kkc.sheettracker.ui.components.ImmersiveDialogDecor

private val SupplyModalEnter: EnterTransition =
    fadeIn(tween(150)) + scaleIn(tween(170), initialScale = 0.96f) + slideInVertically(tween(170)) { it / 18 }

private val SupplyModalExit: ExitTransition =
    fadeOut(tween(120)) + scaleOut(tween(120), targetScale = 0.98f) + slideOutVertically(tween(120)) { it / 24 }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SupplyModalFrame(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit
) {
    val transitionState = remember {
        MutableTransitionState(false).apply { targetState = true }
    }

    fun requestDismiss() {
        transitionState.targetState = false
    }

    LaunchedEffect(transitionState.currentState, transitionState.targetState, transitionState.isIdle) {
        if (transitionState.isIdle && !transitionState.currentState && !transitionState.targetState) {
            onDismiss()
        }
    }

    Dialog(
        onDismissRequest = { requestDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        BackHandler { requestDismiss() }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 18.dp, vertical = 36.dp),
            contentAlignment = Alignment.Center
        ) {
            AnimatedVisibility(
                visibleState = transitionState,
                enter = SupplyModalEnter,
                exit = SupplyModalExit
            ) {
                Surface(
                    modifier = modifier
                        .fillMaxWidth()
                        .widthIn(max = 1040.dp)
                        .fillMaxHeight(0.92f),
                    shape = MaterialTheme.shapes.extraLarge,
                    tonalElevation = 6.dp,
                    shadowElevation = 16.dp,
                    color = MaterialTheme.colorScheme.surface
                ) {
                    ImmersiveDialogDecor()
                    Scaffold(
                        containerColor = Color.Transparent,
                        topBar = {
                            TopAppBar(
                                title = {
                                    Text(
                                        title,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                },
                                actions = {
                                    actions()
                                    IconButton(onClick = { requestDismiss() }) {
                                        Icon(Icons.Default.Close, contentDescription = "Close")
                                    }
                                },
                                colors = TopAppBarDefaults.topAppBarColors(
                                    containerColor = Color.Transparent,
                                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                                    actionIconContentColor = MaterialTheme.colorScheme.onSurface
                                )
                            )
                        }
                    ) { innerPadding ->
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(innerPadding),
                            content = content
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SupplyPickerDialog(
    title: String,
    options: List<SupplyPickerOption>,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    SupplyModalFrame(
        title = title,
        onDismiss = onDismiss,
        modifier = modifier.heightIn(max = 620.dp)
    ) {
        HorizontalDivider()
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(options, key = { it.id }) { option ->
                NavigationDrawerItem(
                    label = { Text(option.label) },
                    selected = option.selected,
                    onClick = option.onClick,
                    icon = option.icon,
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
            }
        }
    }
}

data class SupplyPickerOption(
    val id: String,
    val label: String,
    val selected: Boolean,
    val onClick: () -> Unit,
    val icon: (@Composable () -> Unit)? = null
)
