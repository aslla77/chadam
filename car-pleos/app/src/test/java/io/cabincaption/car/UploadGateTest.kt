package io.cabincaption.car

import io.cabincaption.car.UploadGate.Drop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadGateTest {

    private val gate = UploadGate()

    @Test
    fun noPhoneMeansNothingSent() {
        repeat(5) { assertEquals(Drop.NO_PHONE, gate.offer(0, paused = false, speakerBusy = false, nav = false, silenced = false).drop) }
        assertEquals(UploadGate.Counters(5, 0, 5, 0, 0, 0, 0), gate.counters())
    }

    @Test
    fun onePhoneGetsConsecutiveSeq() {
        val seqs = (1..4).map { gate.offer(1, paused = false, speakerBusy = false, nav = false, silenced = false) }
        assertTrue(seqs.all { it.send })
        assertEquals(listOf(0L, 1L, 2L, 3L), seqs.map { it.seq })
        assertEquals(4L, gate.counters().sent)
    }

    @Test
    fun eachBlockingConditionDrops() {
        assertEquals(Drop.PAUSED, gate.offer(2, paused = true, speakerBusy = false, nav = false, silenced = false).drop)
        assertEquals(Drop.SPEAKER, gate.offer(2, paused = false, speakerBusy = true, nav = false, silenced = false).drop)
        assertEquals(Drop.SILENCED, gate.offer(2, paused = false, speakerBusy = false, nav = false, silenced = true).drop)
        assertEquals(Drop.NAV, gate.offer(2, paused = false, speakerBusy = false, nav = true, silenced = false).drop)
        assertNull(gate.offer(2, paused = false, speakerBusy = false, nav = false, silenced = false).drop)
        assertEquals(UploadGate.Counters(5, 1, 0, 1, 1, 1, 1), gate.counters())
    }

    @Test
    fun droppedChunksStillAdvanceSeq() {
        gate.offer(1, paused = false, speakerBusy = false, nav = false, silenced = false)
        gate.offer(1, paused = true, speakerBusy = false, nav = false, silenced = false)
        gate.offer(1, paused = true, speakerBusy = false, nav = false, silenced = false)
        val d = gate.offer(1, paused = false, speakerBusy = false, nav = false, silenced = false)
        assertTrue(d.send)
        assertEquals(3L, d.seq)
    }

    @Test
    fun noPhoneWinsOverOtherReasons() {
        val d = gate.offer(0, paused = true, speakerBusy = true, nav = true, silenced = true)
        assertFalse(d.send)
        assertEquals(Drop.NO_PHONE, d.drop)
    }
}
