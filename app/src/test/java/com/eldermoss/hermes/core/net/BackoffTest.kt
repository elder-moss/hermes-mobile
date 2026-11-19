package com.eldermoss.hermes.core.net

import org.junit.Assert.assertEquals
import org.junit.Test

class BackoffTest {
    @Test
    fun `doubles to the cap then holds`() {
        val backoff = Backoff(initialMs = 1_000, maxMs = 30_000)
        assertEquals(1_000L, backoff.next())
        assertEquals(2_000L, backoff.next())
        assertEquals(4_000L, backoff.next())
        assertEquals(8_000L, backoff.next())
        assertEquals(16_000L, backoff.next())
        assertEquals(30_000L, backoff.next())
        assertEquals(30_000L, backoff.next())
    }

    @Test
    fun `reset returns to initial`() {
        val backoff = Backoff(initialMs = 500, maxMs = 1_000)
        backoff.next()
        backoff.next()
        backoff.reset()
        assertEquals(500L, backoff.next())
    }
}
