package com.kkc.updateragent.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.kkc.updateragent.logging.AgentLog

class TriggerUpdateReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "TriggerUpdateReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == "com.kkc.updateragent.TRIGGER_UPDATE") {
            AgentLog.i(TAG, "Received com.kkc.updateragent.TRIGGER_UPDATE broadcast intent. Triggering update check now...")
            val basePath = UpdatePaths.defaultBasePath()
            val paths = UpdatePaths(basePath)
            val policy = UpdateFeedRepository().readPolicy(paths)
            UpdateScheduler.runNow(context, policy?.basePath ?: basePath)
        }
    }
}
