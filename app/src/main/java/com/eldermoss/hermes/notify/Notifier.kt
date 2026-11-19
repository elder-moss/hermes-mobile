package com.eldermoss.hermes.notify

import android.content.Context
import com.eldermoss.hermes.ServiceLocator
import com.eldermoss.hermes.core.net.CronJob

/**
 * Notification decisions shared by the foreground service (60s poll) and the
 * WorkManager fallback (15 min poll). Diff state is process-global; the pure
 * change detection lives in CronDiff.
 */
object Notifier {

    private val seen = HashMap<String, CronDiff.Fingerprint>()
    private var nextId = 2000

    /** Fetch cron jobs and post notifications for state changes. */
    suspend fun diffCron(context: Context) {
        val settings = ServiceLocator.settings.snapshot()
        val jobs = try {
            ServiceLocator.connections.cron.list()
        } catch (_: Exception) {
            return // offline: silent, retried next tick
        }
        val fired = synchronized(seen) {
            val changes = CronDiff.changes(seen, jobs)
            for (job in jobs) seen[job.id] = CronDiff.fingerprint(job)
            changes.mapNotNull { (id, failed) ->
                jobs.firstOrNull { it.id == id }?.let { job ->
                    val wanted = if (failed) settings.notifCronFail else settings.notifCronDone
                    if (wanted) job to failed else null
                }
            }
        }
        for ((job, failed) in fired) post(context, job, failure = failed)
    }

    private fun post(context: Context, job: CronJob, failure: Boolean) {
        val title = if (failure) "Schedule failed: ${job.name ?: job.id}" else "Schedule finished: ${job.name ?: job.id}"
        val text = job.lastError ?: "Last run ${job.lastRunAt ?: ""} completed"
        Channels.post(context, Channels.CRON, nextId++, title, text)
    }

    /** The agent completed a turn in a session the user is not watching. */
    suspend fun agentFinished(context: Context, sessionId: String, text: String) {
        val settings = ServiceLocator.settings.snapshot()
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
