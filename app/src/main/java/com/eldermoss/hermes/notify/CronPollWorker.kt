package com.eldermoss.hermes.notify

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/** 15-minute cron-state poll used when the foreground link service is off. */
class CronPollWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        runCatching { Notifier.diffCron(applicationContext) }
        return Result.success()
    }
}
