package io.cabincaption.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RateLimitTest {

    private var now = 0L
    private val limit = RateLimit(burst = 3, refillMs = 1_000, clock = { now })

    @Test
    fun burstThenRefusesUntilRefilled() {
        repeat(3) { assertTrue(limit.take()) }
        assertFalse(limit.take())
        now = 999
        assertFalse(limit.take())
        now = 1_000
        assertTrue(limit.take())
        assertFalse(limit.take())
    }

    @Test
    fun partialTimeIsKeptForTheNextToken() {
        repeat(3) { limit.take() }
        now = 1_500
        assertTrue(limit.take())
        now = 2_000
        assertTrue(limit.take())
        assertFalse(limit.take())
    }

    @Test
    fun longIdleRefillsOnlyUpToBurst() {
        repeat(3) { limit.take() }
        now = 60_000
        assertEquals(3, (1..10).count { limit.take() })
    }

    @Test
    fun steadyOnePerSecondIsNeverRefused() {
        repeat(30) {
            assertTrue(limit.take())
            now += 1_000
        }
    }

    @Test
    fun defaultsAllowFiveAtOnceAndOnePerSecond() {
        var t = 0L
        val d = RateLimit(clock = { t })
        assertEquals(5, (1..10).count { d.take() })
        t = RateLimit.REFILL_MS
        assertTrue(d.take())
        assertFalse(d.take())
    }
}
