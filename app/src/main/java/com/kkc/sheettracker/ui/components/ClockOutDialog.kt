package com.kkc.sheettracker.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.kkc.sheettracker.data.ClockInBilling
import com.kkc.sheettracker.ui.theme.KKCThemeColors
import com.kkc.sheettracker.ui.theme.LocalKKCIsDarkTheme
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToLong

private val ClockOutEnter: EnterTransition =
    fadeIn(tween(150)) + scaleIn(tween(170), initialScale = 0.96f) + slideInVertically(tween(170)) { it / 18 }

private const val HOUR_STEP = 0.25

/**
 * Confirms hours to log after clocking out. Styled like the app's modal frame (SupplyModalFrame):
 * flat surface, 14dp corners, status-tinted header with a 2dp rule.
 *
 * Only the explicit Discard / Save buttons close it — the clock-in is already cleared when this
 * shows, so an accidental outside tap or back press would otherwise silently drop the time.
 */
@Composable
fun ClockOutDialog(
    jobNumber: String,
    jobName: String,
    initialHours: Double,
    startTimeMs: Long,
    stopTimeMs: Long,
    actualElapsedMs: Long,
    onConfirm: (Double) -> Unit,
    onDismiss: () -> Unit
) {
    val status = KKCThemeColors.statusColors
    var hours by remember { mutableDoubleStateOf(initialHours) }
    val billedHours = ClockInBilling.billedHours(actualElapsedMs)
    val counted = actualElapsedMs >= ClockInBilling.MIN_COUNTED_MS
    val tint = if (counted) status.complete else status.skip
    val timeFmt = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }
    val timeRange = remember(startTimeMs, stopTimeMs) {
        "${timeFmt.format(Date(startTimeMs))} → ${timeFmt.format(Date(stopTimeMs))}"
    }
    val animationsOn = !LocalLowEndMode.current.animationsDisabled
    val visibleState = remember { MutableTransitionState(!animationsOn).apply { targetState = true } }

    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = false,
            dismissOnClickOutside = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 18.dp, vertical = 36.dp),
            contentAlignment = Alignment.Center
        ) {
            AnimatedVisibility(visibleState = visibleState, enter = ClockOutEnter) {
                val modalBg = if (LocalKKCIsDarkTheme.current) Color(0xFF1B2028) else Color.White
                Surface(
                    modifier = Modifier
                        .widthIn(max = 460.dp)
                        .fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    tonalElevation = 0.dp,
                    shadowElevation = 16.dp,
                    color = modalBg
                ) {
                    ImmersiveDialogDecor()
                    Column {
                        Text(
                            "Clock out · $jobNumber",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(tint.copy(alpha = 0.16f).compositeOver(modalBg))
                                .padding(horizontal = 20.dp, vertical = 16.dp)
                        )
                        HorizontalDivider(thickness = 2.dp, color = tint.copy(alpha = 0.85f))

                        Column(modifier = Modifier.padding(20.dp)) {
                            Text(
                                jobName,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                timeRange,
                                style = MaterialTheme.typography.bodySmall.merge(ClockDigits),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(16.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                                ClockOutFigure("Worked", ClockInBilling.formatElapsed(actualElapsedMs))
                                ClockOutFigure("Counts as", ClockInBilling.formatHours(billedHours), tint)
                            }
                            Spacer(Modifier.height(8.dp))
                            if (counted) {
                                Text(
                                    "Rounded up to the next 15 minutes",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                Text(
                                    "Under 7 minutes — the timeclock won't count this. " +
                                        "Discard it unless you really need to log time.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = tint,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(tint.copy(alpha = 0.12f))
                                        .padding(horizontal = 12.dp, vertical = 10.dp)
                                )
                            }

                            Spacer(Modifier.height(18.dp))
                            Text(
                                "Hours to log",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(6.dp))
                            HoursStepper(
                                hours = hours,
                                onHoursChange = { hours = it }
                            )

                            Spacer(Modifier.height(20.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val saveLabel = "Save ${ClockInBilling.formatHours(hours)}"
                                if (counted) {
                                    TextButton(
                                        onClick = onDismiss,
                                        colors = ButtonDefaults.textButtonColors(
                                            contentColor = MaterialTheme.colorScheme.error
                                        ),
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(52.dp)
                                    ) { Text("Discard") }
                                    Button(
                                        onClick = { onConfirm(hours) },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = status.complete,
                                            contentColor = Color.White
                                        ),
                                        modifier = Modifier
                                            .weight(1.6f)
                                            .height(52.dp)
                                    ) { Text(saveLabel, fontWeight = FontWeight.Bold) }
                                } else {
                                    // Short punch: discarding is the expected path, saving the exception.
                                    OutlinedButton(
                                        onClick = { onConfirm(hours) },
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(52.dp)
                                    ) { Text(saveLabel) }
                                    Button(
                                        onClick = onDismiss,
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.error,
                                            contentColor = MaterialTheme.colorScheme.onError
                                        ),
                                        modifier = Modifier
                                            .weight(1.6f)
                                            .height(52.dp)
                                    ) { Text("Discard", fontWeight = FontWeight.Bold) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ClockOutFigure(
    label: String,
    value: String,
    valueColor: Color = MaterialTheme.colorScheme.onSurface
) {
    Column {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            style = MaterialTheme.typography.headlineSmall.merge(ClockDigits),
            fontWeight = FontWeight.SemiBold,
            color = valueColor
        )
    }
}

@Composable
private fun HoursStepper(hours: Double, onHoursChange: (Double) -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    val outline = MaterialTheme.colorScheme.outline
    // Snap to quarter hours so repeated taps never accumulate float drift.
    fun step(delta: Double) = (((hours + delta) / HOUR_STEP).roundToLong() * HOUR_STEP).coerceAtLeast(HOUR_STEP)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(shape)
            .border(1.dp, outline, shape),
        verticalAlignment = Alignment.CenterVertically
    ) {
        StepperButton("−", "Subtract 15 minutes", enabled = hours > HOUR_STEP) {
            onHoursChange(step(-HOUR_STEP))
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(outline))
        Text(
            ClockInBilling.formatHours(hours),
            style = MaterialTheme.typography.headlineSmall.merge(ClockDigits),
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(2f)
        )
        Box(Modifier.width(1.dp).fillMaxHeight().background(outline))
        StepperButton("+", "Add 15 minutes", enabled = true) {
            onHoursChange(step(HOUR_STEP))
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.StepperButton(
    symbol: String,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .clickable(enabled = enabled, onClickLabel = description, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            symbol,
            style = MaterialTheme.typography.headlineSmall,
            color = if (enabled) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        )
    }
}
