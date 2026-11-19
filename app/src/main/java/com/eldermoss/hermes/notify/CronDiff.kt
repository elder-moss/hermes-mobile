package com.eldermoss.hermes.notify

import com.eldermoss.hermes.core.net.CronJob

/**
 * Pure cron-state diff used by both notification paths. `changes` returns
 * (jobId, failure) pairs for jobs whose last_run_at moved since the previous
 * sighting. First sightings never notify (no spam on app start).
 */
object CronDiff {

    data class Fingerprint(val enabled: Boolean, val lastRunAt: String?, val lastError: String?, val status: String?)

    fun fingerprint(job: CronJob) = Fingerprint(job.enabled, job.lastRunAt, job.lastError, job.latestExecution?.status)

    fun changes(
        previous: Map<String, Fingerprint>,
        jobs: List<CronJob>,
    ): List<Pair<String, Boolean>> {
        val out = ArrayList<Pair<String, Boolean>>()
        for (job in jobs) {
            val old = previous[job.id] ?: continue
            val fp = fingerprint(job)
            val ranAgain = fp.lastRunAt != null && fp.lastRunAt != old.lastRunAt
            if (ranAgain) out.add(job.id to !fp.lastError.isNullOrBlank())
        }
        return out
    }
}
