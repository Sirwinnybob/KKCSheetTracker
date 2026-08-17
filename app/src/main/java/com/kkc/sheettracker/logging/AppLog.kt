package com.kkc.sheettracker.logging

import android.util.Log
import com.kkc.sheettracker.BuildConfig

internal enum class AppLogPriority { DEBUG, INFO, WARN, ERROR }

internal fun shouldEmitAppLog(priority: AppLogPriority, isDebugBuild: Boolean): Boolean =
    isDebugBuild || priority == AppLogPriority.WARN || priority == AppLogPriority.ERROR

object AppLog {
    fun d(tag: String, message: String, throwable: Throwable? = null): Int =
        emit(AppLogPriority.DEBUG, tag, message, throwable)

    fun i(tag: String, message: String, throwable: Throwable? = null): Int =
        emit(AppLogPriority.INFO, tag, message, throwable)

    fun w(tag: String, message: String, throwable: Throwable? = null): Int =
        emit(AppLogPriority.WARN, tag, message, throwable)

    fun e(tag: String, message: String, throwable: Throwable? = null): Int =
        emit(AppLogPriority.ERROR, tag, message, throwable)

    private fun emit(
        priority: AppLogPriority,
        tag: String,
        message: String,
        throwable: Throwable?
    ): Int {
        if (!shouldEmitAppLog(priority, BuildConfig.DEBUG)) return 0
        return when (priority) {
            AppLogPriority.DEBUG -> if (throwable == null) Log.d(tag, message) else Log.d(tag, message, throwable)
            AppLogPriority.INFO -> if (throwable == null) Log.i(tag, message) else Log.i(tag, message, throwable)
            AppLogPriority.WARN -> if (throwable == null) Log.w(tag, message) else Log.w(tag, message, throwable)
            AppLogPriority.ERROR -> if (throwable == null) Log.e(tag, message) else Log.e(tag, message, throwable)
        }
    }
}
