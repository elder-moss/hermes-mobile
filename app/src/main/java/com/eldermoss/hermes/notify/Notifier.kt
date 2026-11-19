package com.eldermoss.hermes.notify

import android.content.Context
import com.eldermoss.hermes.ServiceLocator
import com.eldermoss.hermes.core.net.CronJob

/**
 * Notification decisions shared by the foreground service (60s poll) and the
 * WorkManager fallback (15 min poll). Diff state is process-global.
 */
object Notifier {

    data class JobFingerprint(val enabled: Boolean, val lastRunAt: String?, val lastError: String?, val status: String?)

    private val seen = HashMap<String, JobFingerprint>()
    private var nextId = 2000

    private fun fingerprint(job: CronJob) = JobFingerprint(
        job.enabled, job.lastRunAt, job.lastError, job.latestExecution?.status,
    )

    /** Fetch cron jobs and post notifications for state changes. */
    suspend fun diffCron(context: Context) {
        val settings = ServiceLocator.settings.snapshot()
        val jobs = try {
            ServiceLocator.connections.cron.list()
        } catch (_: Exception) {
            return // offline: silent, retried next tick
        }
        synchronized(seen) {
            for (job in jobs) {
                val fp = fingerprint(job)
                val old = seen[job.id]
                seen[job.id] = fp
                if (old == null) continue // first sighting after start: no spam
                val ranAgain = fp.lastRunAt != null && fp.lastRunAt != old.lastRunAt
                if (ranAgain) {
                    val failed = !fp.lastError.isNullOrBlank()
                    if (failed && settings.notifCronFail) {
                        post(context, job, failure = true)
                    } else if (!failed && settings.notifCronDone) {
                        post(context, job, failure = false)
                    }
                }
            }
        }
    }

    private fun post(context: Context, job: CronJob, failure: Boolean) {
        val title = if (failure) "Schedule failed: ${job.name ?: job.id}" else "Schedule finished: ${job.name ?: job.id}"
        val text = job.lastError ?: "Last run ${job.lastRunAt ?: ""} completed"
        Channels.post(context, Channels.CRON, nextId++, title, text)
    }

    /** The agent completed a turn in a session the user is not watching. */
    fun agentFinished(context: Context, sessionId: String, text: String) {
        val settings = kotlinx.coroutines.runBlocking { ServiceLocator.settings.snapshot() }
        if (!settings.notifAgentDone) return
        if (AppForeground.isForeground()) return // user is watching; no notification
        val id = (sessionId.hashCode() and 0xFFFF) + 100
        Channels.post(context, Channels.AGENT, id, "Agent finished", text.take(300))
    }
}

/** Cheap foreground check without androidx.lifecycle-process dependency. */
object AppForeground {
    @Volatile var foreground: Boolean = false
    fun isForeground(): Boolean = foreground
}
