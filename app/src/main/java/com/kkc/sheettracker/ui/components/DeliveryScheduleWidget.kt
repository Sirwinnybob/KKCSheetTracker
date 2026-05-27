package com.kkc.sheettracker.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.data.models.DeliverySchedule
import com.kkc.sheettracker.data.models.DELIVERY_DAY_LABELS
import com.kkc.sheettracker.data.models.DELIVERY_DAYS
import com.kkc.sheettracker.data.models.DELIVERY_PERIODS

/**
 * Compact always-visible delivery schedule row, shown above the job board grid.
 * Hidden when the schedule is empty. Tap to expand to full-screen dialog.
 */
@Composable
fun DeliveryScheduleWidget(
    schedule: DeliverySchedule,
    onTap: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (schedule.isEmpty) return

    Surface(
        tonalElevation = 2.dp,
        modifier = modifier
            .fillMaxWidth()
            .clickable { onTap() }
    ) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
            Text(
                text = "DELIVERIES",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                DELIVERY_DAYS.forEachIndexed { dayIdx, day ->
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = DELIVERY_DAY_LABELS.getOrNull(dayIdx) ?: "Day",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(bottom = 2.dp)
                        )
                        DELIVERY_PERIODS.forEach { period ->
                            val slot = schedule.slot(day, period)
                            Text(
                                text = period.uppercase(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.Bold
                            )
                            if (slot.jobs.isEmpty()) {
                                Text(
                                    text = "—",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                )
                            } else {
                                slot.jobs.forEach { job ->
                                    Text(
                                        text = "${job.jobNumber} — ${job.description}",
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        color = MaterialTheme.colorScheme.onSurface
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
