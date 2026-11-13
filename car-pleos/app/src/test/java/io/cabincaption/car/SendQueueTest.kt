package io.cabincaption.car

import io.cabincaption.protocol.Frame
import io.cabincaption.protocol.FrameType
import io.cabincaption.protocol.Wire
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SendQueueTest {

    private fun audio(n: Int) = Frame(FrameType.CAR_PCM, byteArrayOf(n.toByte()))

    private fun ctrl(n: Int) = Wire.ctrl("t" to "c$n")

    /** 지금 들어 있는 것을 순서대로 꺼낸다(끝 표시를 하나 넣고 그것이 나올 때까지). */
    private fun SendQueue.drain(): List<Frame> {
        val end = Wire.ctrl("t" to "end")
        val out = mutableListOf<Frame>()
        val forced = offerControl(end) != SendQueue.Offer.ACCEPTED
        if (forced) return error("queue full")
        while (true) {
            val f = take() ?: break
            if (f == end) break
            out += f
        }
        return out
    }

    @Test
    fun keepsOrderAndDropsOldestAudioBeyondOneSecond() {
        val q = SendQueue(maxAudio = 3)
        q.offerAudio(audio(1))
        assertEquals(SendQueue.Offer.ACCEPTED, q.offerControl(ctrl(1)))
        (2..5).forEach { q.offerAudio(audio(it)) }
        assertEquals(listOf(ctrl(1), audio(3), audio(4), audio(5)), q.drain())
        assertEquals(2L, q.droppedAudio)
    }

    @Test
    fun controlFramesAreNeverDroppedButOverflowIsReported() {
        val q = SendQueue(maxAudio = 1, maxControl = 2)
        q.offerControl(ctrl(1))
        repeat(5) { q.offerAudio(audio(it)) }
        assertEquals(SendQueue.Offer.ACCEPTED, q.offerControl(ctrl(2)))
        assertEquals(SendQueue.Offer.FULL, q.offerControl(ctrl(3)))
        assertEquals(ctrl(1), q.take())
        assertEquals(listOf(audio(4), ctrl(2)), q.drain())
    }

    @Test
    fun finishDropsPendingAudioSendsLastThenEnds() {
        val q = SendQueue()
        q.offerControl(ctrl(1))
        q.offerAudio(audio(1))
        q.finish(Wire.ctrl("t" to "off"))
        assertEquals(SendQueue.Offer.CLOSED, q.offerControl(ctrl(2)))
        q.offerAudio(audio(2))
        assertEquals(ctrl(1), q.take())
        assertEquals(Wire.ctrl("t" to "off"), q.take())
        assertNull(q.take())
        assertEquals(1L, q.droppedAudio)
    }

    @Test
    fun closeWakesBlockedTaker() {
        val q = SendQueue()
        val done = CountDownLatch(1)
        var got: Frame? = audio(0)
        Thread {
            got = q.take()
            done.countDown()
        }.start()
        Thread.sleep(50)
        q.close()
        assertTrue(done.await(1, TimeUnit.SECONDS))
        assertNull(got)
        assertEquals(SendQueue.Offer.CLOSED, q.offerControl(ctrl(1)))
    }
}
