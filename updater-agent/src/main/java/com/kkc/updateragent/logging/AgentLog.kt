package com.kkc.updateragent.logging

import android.util.Log
import com.kkc.updateragent.BuildConfig

internal fun shouldEmitAgentLog(isDebugBuild: Boolean): Boolean = isDebugBuild

object AgentLog {
    fun i(tag: String, message: String): Int =
        if (shouldEmitAgentLog(BuildConfig.DEBUG)) Log.i(tag, message) else 0
}
