package com.eldermoss.hermes.notify

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/** Starts/stops the foreground link service and the WorkManager poll fallback. */
object LinkControl {

    fun set(context: Context, enabled: Boolean) {
        if (enabled) {
            LinkService.start(context)
            WorkManager.getInstance(context).cancelUniqueWork("hermes-cron-poll")
        } else {
            LinkService.stop(context)
            schedulePoller(context)
        }
    }

    fun schedulePoller(context: Context) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "hermes-cron-poll",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<CronPollWorker>(15, TimeUnit.MINUTES).build(),
        )
    }
}
