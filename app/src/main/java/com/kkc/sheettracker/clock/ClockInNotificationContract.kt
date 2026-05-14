package com.kkc.sheettracker.clock

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.kkc.sheettracker.MainActivity

object ClockInNotificationContract {
    const val CHANNEL_ID = "clock_in_ongoing"
    const val CHANNEL_NAME = "Clock-In Session"
    const val NOTIFICATION_ID = 1002

    const val ACTION_START_OR_UPDATE = "com.kkc.sheettracker.clock.START_OR_UPDATE"
    const val ACTION_STOP = "com.kkc.sheettracker.clock.STOP"
    const val ACTION_PAUSE = "com.kkc.sheettracker.clock.PAUSE"
    const val ACTION_RESUME = "com.kkc.sheettracker.clock.RESUME"
    const val ACTION_CANCEL = "com.kkc.sheettracker.clock.CANCEL"
    const val ACTION_OPEN_APP = "com.kkc.sheettracker.clock.OPEN_APP"

    const val EXTRA_OPEN_CLOCK_OUT_PROMPT = "extra_open_clock_out_prompt"

    fun startOrUpdateService(context: Context) {
        val intent = Intent(context, ClockInForegroundService::class.java).apply {
            action = ACTION_START_OR_UPDATE
        }
        androidx.core.content.ContextCompat.startForegroundService(context, intent)
    }

    fun stopService(context: Context) {
        context.stopService(Intent(context, ClockInForegroundService::class.java))
    }

    fun serviceActionPendingIntent(context: Context, action: String, requestCode: Int): PendingIntent {
        val serviceIntent = Intent(context, ClockInForegroundService::class.java).apply {
            this.action = action
        }
        return PendingIntent.getService(
            context,
            requestCode,
            serviceIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun appOpenPendingIntent(context: Context, openClockOutPrompt: Boolean): PendingIntent {
        return PendingIntent.getActivity(
            context,
            if (openClockOutPrompt) 2002 else 2001,
            mainActivityIntent(context, openClockOutPrompt),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun mainActivityIntent(context: Context, openClockOutPrompt: Boolean): Intent {
        return Intent(context, MainActivity::class.java).apply {
            action = ACTION_OPEN_APP
            putExtra(EXTRA_OPEN_CLOCK_OUT_PROMPT, openClockOutPrompt)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
    }

    fun shouldOpenClockOutPrompt(intent: Intent?): Boolean {
        return intent?.getBooleanExtra(EXTRA_OPEN_CLOCK_OUT_PROMPT, false) == true
    }
}
