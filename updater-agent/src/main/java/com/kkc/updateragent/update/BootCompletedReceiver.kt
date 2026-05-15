package com.kkc.updateragent.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val basePath = UpdatePaths.defaultBasePath()
        val paths = UpdatePaths(basePath)
        val policy = UpdateFeedRepository().readPolicy(paths)
        val interval = policy?.pollIntervalMinutes ?: 15L
        UpdateScheduler.schedule(context, policy?.basePath ?: basePath, interval)
        UpdateScheduler.runNow(context, policy?.basePath ?: basePath)
    }
}
