package io.cabincaption.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.concurrent.thread

class ChunkBufferTest {

    @Test
    fun keepsNewestUpToCapacity() {
        val b = ChunkBuffer(capacity = 3)
        (1..5).forEach { b.offer(byteArrayOf(it.toByte())) }
        assertEquals(3, b.size())
        assertEquals(2L, b.dropped)
        assertEquals(listOf(3, 4, 5), (1..3).map { b.take(0)!![0].toInt() })
        assertTrue(b.isEmpty())
    }

    @Test
    fun takeTimesOutWhenEmpty() {
        val b = ChunkBuffer()
        val t0 = System.nanoTime()
        assertNull(b.take(50))
        assertTrue((System.nanoTime() - t0) / 1_000_000 >= 45)
    }

    @Test
    fun takeWakesWhenChunkArrives() {
        val b = ChunkBuffer()
        thread {
            Thread.sleep(50)
            b.offer(byteArrayOf(7))
        }
        assertEquals(7, b.take(2_000)!![0].toInt())
    }

    @Test
    fun clearEmpties() {
        val b = ChunkBuffer()
        b.offer(ByteArray(2))
        b.clear()
        assertNull(b.take(0))
    }

    @Test
    fun defaultIsTwoSecondsOfChunks() = assertEquals(20, ChunkBuffer.CAPACITY)
}
