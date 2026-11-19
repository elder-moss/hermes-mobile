package com.eldermoss.hermes.core.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Shapes captured live from the Asgard gateway (0.23.2) on 2026-11-19. */
class ModelsParsingTest {

    private val sessionRow = """
        {"id":"20261119_131314_8f0e8511","title":"Build Android app for Hermes",
         "message_count":133,"started_at":1795119194.3382835,"pinned":false,
         "archived":false,"unread":false,"preview":"hello","source":"user",
         "some_future_field":{"nested":true}}
    """.trimIndent()

    @Test
    fun `session page parses with unknown fields ignored`() {
        val body = """{"limit":3,"offset":0,"total":42,"storage":{},"sessions":[$sessionRow]}"""
        val page = lenientJson.decodeFromString<SessionPage>(body)
        assertEquals(1, page.sessions.size)
        val row = page.sessions[0]
        assertEquals("20261119_131314_8f0e8511", row.id)
        assertEquals("Build Android app for Hermes", row.title)
        assertEquals(133, row.messageCount)
        assertTrue(row.startedAt!! > 0)
        assertFalse(row.pinned)
    }

    @Test
    fun `messages page parses tool and assistant rows`() {
        val body = """
            {"session_id":"s1","profile":"default",
             "pagination":{"limit":6,"offset":0,"order":"latest","returned":6},
             "messages":[
               {"role":"tool","content":"{\"output\":\"ok\",\"exit_code\":0}"},
               {"role":"assistant","content":""},
               {"role":"assistant","content":"Done.","display_content":"Done."}
             ]}
        """.trimIndent()
        val page = lenientJson.decodeFromString<MessagesPage>(body)
        assertEquals(3, page.messages.size)
        assertEquals("tool", page.messages[0].role)
        assertEquals("latest", page.pagination?.order)
        assertEquals("Done.", page.messages[2].content)
    }

    @Test
    fun `cron job parses schedule object and execution`() {
        val body = """
            {"id":"34483a056457","name":"asgard-net-defense-log",
             "schedule":{"kind":"interval","minutes":30,"display":"every 30m"},
             "schedule_display":"every 30m","enabled":false,"state":"paused",
             "last_run_at":"2026-11-11T14:39:18.932788-07:00","last_error":null,
             "next_run_at":"2026-11-11T15:09:18.932788-07:00",
             "latest_execution":{"id":"e1","status":"succeeded"}}
        """.trimIndent()
        val job = lenientJson.decodeFromString<CronJob>(body)
        assertEquals("34483a056457", job.id)
        assertEquals("every 30m", job.scheduleDisplay)
        assertFalse(job.enabled)
        assertEquals("paused", job.state)
        assertNull(job.lastError)
        assertEquals("succeeded", job.latestExecution?.status)
    }

    @Test
    fun `skill info parses`() {
        val skill = lenientJson.decodeFromString<SkillInfo>(
            """{"name":"ai-debate-show","enabled":true,"category":null,"description":"d","provenance":"agent"}"""
        )
        assertEquals("ai-debate-show", skill.name)
        assertTrue(skill.enabled)
        assertNull(skill.category)
        assertEquals("agent", skill.provenance)
    }

    @Test
    fun `mem record parses`() {
        val record = lenientJson.decodeFromString<MemRecord>(
            """{"id":292,"text":"Hermes Mobile app","category":"project","importance":4,
                "learned_at":"2026-11-11T05:40:00+00:00","confidence":0.85,"source":"legacy",
                "status":"active","semantic_similarity":0.8389,"created_at":"2026-11-11"}"""
        )
        assertEquals(292, record.id)
        assertEquals(4, record.importance)
        assertEquals(0.8389, record.semanticSimilarity!!, 1e-9)
        assertEquals("active", record.status)
    }

    @Test
    fun `search response parses`() {
        val body = """{"results":[{"session_id":"s1","title":"T","snippet":"…","role":"user"}]}"""
        val res = lenientJson.decodeFromString<SearchResponse>(body)
        assertEquals(1, res.results.size)
        assertEquals("s1", res.results[0].sessionId)
    }
}
