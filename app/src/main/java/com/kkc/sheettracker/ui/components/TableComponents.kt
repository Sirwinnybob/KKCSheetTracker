package com.kkc.sheettracker.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class SortColumn { NUMBER, WIDTH, LENGTH, NAME, CAB, ROOM }
enum class SortDirection { NONE, ASC, DESC }

@Composable
fun SortHeader(
    title: String,
    modifier: Modifier,
    isActive: Boolean,
    direction: SortDirection,
    onClick: () -> Unit
) {
    Row(
        modifier = modifier.clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
            color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
        )
        if (isActive) {
            Text(
                text = when (direction) {
                    SortDirection.ASC -> " ▲"
                    SortDirection.DESC -> " ▼"
                    SortDirection.NONE -> ""
                },
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
fun ResizeHandle(onDrag: (Float) -> Unit) {
    Box(
        modifier = Modifier
            .width(16.dp)
            .height(28.dp)
            .pointerInput(Unit) {
                detectHorizontalDragGestures { _, dragAmount ->
                    onDrag(dragAmount)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .width(2.dp)
                .height(20.dp)
                .background(
                    MaterialTheme.colorScheme.outlineVariant,
                    shape = MaterialTheme.shapes.extraSmall
                )
        )
    }
}
