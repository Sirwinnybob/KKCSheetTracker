package com.kkc.sheettracker.ui.hours

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.data.HoursStore
import com.kkc.sheettracker.data.models.HoursEntry
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private val TIME_FMT = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HoursTrackerScreen(
    hoursStore: HoursStore,
    employeeName: String
) {
    var entries by remember { mutableStateOf(hoursStore.getEntriesForDate()) }

    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(30_000L)
            entries = hoursStore.getEntriesForDate()
        }
    }

    val todayEntries = entries.sortedByDescending { it.clockInMs }
    val myEntries = todayEntries.filter { it.employeeName == employeeName }
    val activeEntry = myEntries.firstOrNull { it.clockOutMs == null }
    val isClockedIn = activeEntry != null

    val myTotalMs = myEntries
        .filter { it.clockOutMs != null }
        .sumOf { it.clockOutMs!! - it.clockInMs }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Hours Tracker") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                "Hello, $employeeName",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )

            Surface(
                tonalElevation = 2.dp,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (isClockedIn) {
                        Text(
                            "Clocked in — ${formatMs(myTotalMs)} today",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            "Since ${formatEpochTime(activeEntry!!.clockInMs)}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Button(
                            onClick = {
                                hoursStore.clockOut(activeEntry.id)
                                entries = hoursStore.getEntriesForDate()
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error
                            ),
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Filled.Logout, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Clock Out")
                        }
                    } else {
                        Text(
                            "Clocked out — ${formatMs(myTotalMs)} today",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Button(
                            onClick = {
                                hoursStore.clockIn(employeeName)
                                entries = hoursStore.getEntriesForDate()
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            ),
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Filled.Login, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Clock In")
                        }
                    }
                }
            }

            if (myEntries.isNotEmpty()) {
                Text("My Sessions Today", style = MaterialTheme.typography.titleMedium)
                myEntries.forEach { entry ->
                    HoursEntryRow(entry = entry)
                }
            }

            val otherEmployees = todayEntries
                .filter { it.employeeName != employeeName && it.clockOutMs == null }
                .groupBy { it.employeeName }
            if (otherEmployees.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text("On the Clock Today", style = MaterialTheme.typography.titleMedium)
                otherEmployees.forEach { (name, empEntries) ->
                    val empTotal = empEntries.filter { it.clockOutMs != null }
                        .sumOf { it.clockOutMs!! - it.clockInMs }
                    EmployeeSummaryRow(name = name, totalMs = empTotal, isClockedIn = true)
                }
            }
        }
    }
}

@Composable
private fun HoursEntryRow(entry: HoursEntry) {
    val start = formatEpochTime(entry.clockInMs)
    val end = if (entry.clockOutMs != null) formatEpochTime(entry.clockOutMs) else "Active"
    val duration = if (entry.clockOutMs != null) {
        formatMs(entry.clockOutMs - entry.clockInMs)
    } else {
        formatMs(System.currentTimeMillis() - entry.clockInMs)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("$start → $end", style = MaterialTheme.typography.bodyMedium)
        Text(
            duration,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun EmployeeSummaryRow(name: String, totalMs: Long, isClockedIn: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isClockedIn) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(50))
                )
            }
            Text(name, style = MaterialTheme.typography.bodyMedium)
        }
        Text(
            formatMs(totalMs),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun formatMs(ms: Long): String {
    val hours = ms / 3_600_000
    val minutes = (ms % 3_600_000) / 60_000
    return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
}

private fun formatEpochTime(epochMs: Long): String {
    return Instant.ofEpochMilli(epochMs)
        .atZone(ZoneId.systemDefault())
        .toLocalTime()
        .format(TIME_FMT)
}