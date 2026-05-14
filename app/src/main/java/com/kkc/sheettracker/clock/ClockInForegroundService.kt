package com.kkc.sheettracker.clock

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.kkc.sheettracker.R
import com.kkc.sheettracker.data.ClockInSnapshot
import com.kkc.sheettracker.data.ClockInState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class ClockInForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var clockInState: ClockInState
    private var tickerJob: Job? = null
    private var isForegroundStarted = false

    override fun onCreate() {
        super.onCreate()
        clockInState = ClockInState.create(this)
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action ?: ClockInNotificationContract.ACTION_START_OR_UPDATE) {
            ClockInNotificationContract.ACTION_START_OR_UPDATE -> {
                clockInState.refreshFromDisk()
                publishOrStop()
            }

            ClockInNotificationContract.ACTION_PAUSE -> {
                clockInState.pause()
                publishOrStop()
            }

            ClockInNotificationContract.ACTION_RESUME -> {
                clockInState.resume()
                publishOrStop()
            }

            ClockInNotificationContract.ACTION_CANCEL -> {
                clockInState.triggerPrompt()
                publishOrStop()
            }

            ClockInNotificationContract.ACTION_STOP -> {
                stopForegroundAndSelf()
            }

            else -> {
                clockInState.refreshFromDisk()
                publishOrStop()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        tickerJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun publishOrStop() {
        val snapshot = clockInState.snapshot
        if (!snapshot.isActive) {
            stopForegroundAndSelf()
            return
        }

        val notification = buildNotification(snapshot)
        if (!isForegroundStarted) {
            startForeground(ClockInNotificationContract.NOTIFICATION_ID, notification)
            isForegroundStarted = true
        } else {
            NotificationManagerCompat.from(this).notify(ClockInNotificationContract.NOTIFICATION_ID, notification)
        }
        ensureTickerRunning()
    }

    private fun ensureTickerRunning() {
        if (tickerJob?.isActive == true) return
        tickerJob = serviceScope.launch {
            while (isActive) {
                delay(1_000L)
                val snapshot = clockInState.snapshot
                if (!snapshot.isActive) {
                    stopForegroundAndSelf()
                    break
                }
                NotificationManagerCompat.from(this@ClockInForegroundService).notify(
                    ClockInNotificationContract.NOTIFICATION_ID,
                    buildNotification(snapshot)
                )
            }
        }
    }

    private fun stopForegroundAndSelf() {
        tickerJob?.cancel()
        tickerJob = null
        isForegroundStarted = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(snapshot: ClockInSnapshot): Notification {
        val elapsed = formatElapsed(clockInState.elapsedActiveMs())
        val status = if (snapshot.isPaused) "Paused" else "Clocked In"
        val actionLabel = if (snapshot.isPaused) "Resume" else "Pause"
        val action = if (snapshot.isPaused) ClockInNotificationContract.ACTION_RESUME else ClockInNotificationContract.ACTION_PAUSE

        return NotificationCompat.Builder(this, ClockInNotificationContract.CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("$status • ${snapshot.jobNumber}")
            .setContentText("${snapshot.jobName}   $elapsed")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("${snapshot.jobNumber} — ${snapshot.jobName}\nActive Time: $elapsed")
            )
            .setContentIntent(ClockInNotificationContract.appOpenPendingIntent(this, openClockOutPrompt = false))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                0,
                actionLabel,
                ClockInNotificationContract.serviceActionPendingIntent(this, action, requestCode = 1101)
            )
            .addAction(
                0,
                "Cancel",
                ClockInNotificationContract.appOpenPendingIntent(this, openClockOutPrompt = true)
            )
            .build()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            ClockInNotificationContract.CHANNEL_ID,
            ClockInNotificationContract.CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows active clock-in time and quick actions."
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(channel)
    }

    companion object {
        fun formatElapsed(elapsedMs: Long): String {
            val totalSeconds = (elapsedMs / 1000L).coerceAtLeast(0L)
            val hours = totalSeconds / 3600L
            val minutes = (totalSeconds % 3600L) / 60L
            val seconds = totalSeconds % 60L
            return "%02d:%02d:%02d".format(hours, minutes, seconds)
        }
    }
}
