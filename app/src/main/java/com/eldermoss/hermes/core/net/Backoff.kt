package com.eldermoss.hermes.core.net

/**
 * Reconnect backoff: doubles from 1s to a 30s cap, resets on a successful
 * dial. Pure so the reconnect policy is unit-testable.
 */
class Backoff(private val initialMs: Long = 1_000, private val maxMs: Long = 30_000) {
    private var current = initialMs

    fun next(): Long {
        val value = current
        current = (current * 2).coerceAtMost(maxMs)
        return value
    }

    fun reset() { current = initialMs }
}
