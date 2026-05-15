package com.kkc.updateragent.update

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

object UpdateScheduler {
    const val KEY_BASE_PATH = "base_path"
    private const val PERIODIC_WORK_NAME = "kkc_updater_periodic"

    fun schedule(context: Context, basePath: String?, pollIntervalMinutes: Long) {
        val interval = pollIntervalMinutes.coerceIn(15, 12 * 60)
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val input = workDataOf(KEY_BASE_PATH to basePath)
        val request = PeriodicWorkRequestBuilder<UpdateWorker>(interval, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.MINUTES)
            .setInputData(input)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    fun runNow(context: Context, basePath: String?) {
        val input = workDataOf(KEY_BASE_PATH to basePath)
        val request = OneTimeWorkRequestBuilder<UpdateWorker>()
            .setInputData(input)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 1, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueue(request)
    }
}
