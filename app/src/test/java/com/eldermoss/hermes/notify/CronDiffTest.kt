package com.eldermoss.hermes.notify

import com.eldermoss.hermes.core.net.CronExecution
import com.eldermoss.hermes.core.net.CronJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CronDiffTest {

    private fun job(id: String, lastRun: String?, error: String? = null, status: String? = null) = CronJob(
        id = id,
        name = "job-$id",
        lastRunAt = lastRun,
        lastError = error,
        latestExecution = status?.let { CronExecution(id = "e-$id", status = it) },
    )

    @Test
    fun `first sighting never notifies`() {
        val jobs = listOf(job("a", "2026-11-19T10:00:00Z"))
        assertTrue(CronDiff.changes(emptyMap(), jobs).isEmpty())
    }

    @Test
    fun `new last_run_at fires a success notification`() {
        val old = mapOf("a" to CronDiff.fingerprint(job("a", "2026-11-19T10:00:00Z")))
        val jobs = listOf(job("a", "2026-11-19T11:00:00Z"))
        val changes = CronDiff.changes(old, jobs)
        assertEquals(listOf("a" to false), changes)
    }

    @Test
    fun `new last_run_at with error fires a failure notification`() {
        val old = mapOf("a" to CronDiff.fingerprint(job("a", "2026-11-19T10:00:00Z")))
        val jobs = listOf(job("a", "2026-11-19T11:00:00Z", error = "boom"))
        assertEquals(listOf("a" to true), CronDiff.changes(old, jobs))
    }

    @Test
    fun `unchanged jobs stay quiet`() {
        val old = mapOf("a" to CronDiff.fingerprint(job("a", "t1")))
        assertTrue(CronDiff.changes(old, listOf(job("a", "t1"))).isEmpty())
    }

    @Test
    fun `jobs that disappeared are ignored`() {
        val old = mapOf("gone" to CronDiff.fingerprint(job("gone", "t1")))
        assertTrue(CronDiff.changes(old, emptyList()).isEmpty())
    }
}
