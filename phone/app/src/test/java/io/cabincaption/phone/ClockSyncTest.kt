package io.cabincaption.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClockSyncTest {

    @Test
    fun usesShortestRoundTrip() {
        val c = ClockSync(samplesWanted = 3)
        assertNull(c.offsetMs)
        c.sample(sentMs = 1_000, carMs = 6_050, receivedMs = 1_100) // rtt 100 → 5,000
        c.sample(sentMs = 2_000, carMs = 7_010, receivedMs = 2_020) // rtt 20 → 5,000
        assertFalse(c.done)
        c.sample(sentMs = 3_000, carMs = 8_300, receivedMs = 3_060) // rtt 60, 버림
        assertTrue(c.done)
        assertEquals(5_000L, c.offsetMs)
        assertEquals(20L, c.rttMs)
    }

    @Test
    fun negativeRoundTripIsIgnored() {
        val c = ClockSync()
        c.sample(sentMs = 10, carMs = 0, receivedMs = 5)
        assertEquals(0, c.samples)
        assertNull(c.offsetMs)
    }
}
