package io.cabincaption.car

import io.cabincaption.car.JoinTokens.Check
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JoinTokensTest {

    private val tokens = JoinTokens(ttlMs = 1_000)

    @Test
    fun tokenIsStableUntilExpiryThenRotates() {
        val t = tokens.current(0)
        assertEquals(t, tokens.current(999))
        assertNotEquals(t, tokens.current(1_000))
    }

    @Test
    fun tokenIs128BitBase64Url() {
        val t = tokens.current(0)
        assertEquals(22, t.length)
        assertTrue(t.all { it.isLetterOrDigit() || it == '-' || it == '_' })
    }

    @Test
    fun checkDistinguishesValidWrongAndExpired() {
        val t = tokens.current(0)
        assertEquals(Check.OK, tokens.check(t, 500))
        assertEquals(Check.INVALID, tokens.check(t + "x", 500))
        assertEquals(Check.INVALID, tokens.check("", 500))
        assertEquals(Check.EXPIRED, tokens.check(t, 1_000))
    }

    @Test
    fun consumedTokenCannotBeReused() {
        val t = tokens.current(0)
        tokens.consume(10)
        assertEquals(Check.INVALID, tokens.check(t, 20))
        assertNotEquals(t, tokens.current(20))
    }

    @Test
    fun nothingIsValidBeforeIssueOrAfterClear() {
        assertEquals(Check.INVALID, JoinTokens().check("anything", 0))
        val t = tokens.current(0)
        tokens.clear()
        assertEquals(Check.INVALID, tokens.check(t, 1))
    }

    @Test
    fun tokenRotatedByExpiryStillReportsExpired() {
        val t = tokens.current(0)
        val next = tokens.current(1_000)
        assertEquals(Check.EXPIRED, tokens.check(t, 1_001))
        assertEquals(Check.OK, tokens.check(next, 1_001))
        tokens.consume(1_002)
        assertEquals(Check.INVALID, tokens.check(t, 1_003))
        assertEquals(Check.INVALID, tokens.check(next, 1_003))
    }
}
