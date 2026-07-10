package com.kkc.sheettracker.ui.supply

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.data.UiPreferencesStore
import com.kkc.sheettracker.ui.dashboard.DashboardSurfaceCard
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@Composable
fun SupplyTabReorderScreen(
    availableTabs: List<SupplyTabItem>,
    preferencesStore: UiPreferencesStore,
    onOrderChanged: (List<String>) -> Unit
) {
    val tabs = remember { mutableStateListOf<SupplyTabItem>().apply { addAll(availableTabs) } }
    val listState = rememberLazyListState()
    
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        if (from.index in tabs.indices && to.index in tabs.indices) {
            tabs.add(to.index, tabs.removeAt(from.index))
        }
    }

    val saveOrder = {
        val newOrder = tabs.map { it.id }
        preferencesStore.setSupplyTabOrder(newOrder)
        onOrderChanged(newOrder)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Drag or use arrows to reorder tabs. Top of the list is far left, bottom is far right.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            itemsIndexed(tabs, key = { _, item -> item.id }) { index, tabItem ->
                ReorderableItem(reorderState, key = tabItem.id) {
                    DashboardSurfaceCard(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.DragHandle,
                                    contentDescription = "Drag to reorder",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .size(24.dp)
                                        .draggableHandle(
                                            onDragStopped = { saveOrder() }
                                        )
                                )
                                
                                Text(
                                    text = tabItem.name,
                                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                IconButton(
                                    onClick = {
                                        if (index > 0) {
                                            tabs.add(index - 1, tabs.removeAt(index))
                                            saveOrder()
                                        }
                                    },
                                    enabled = index > 0
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.ArrowUpward,
                                        contentDescription = "Move Up"
                                    )
                                }
                                
                                IconButton(
                                    onClick = {
                                        if (index < tabs.size - 1) {
                                            tabs.add(index + 1, tabs.removeAt(index))
                                            saveOrder()
                                        }
                                    },
                                    enabled = index < tabs.size - 1
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.ArrowDownward,
                                        contentDescription = "Move Down"
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
