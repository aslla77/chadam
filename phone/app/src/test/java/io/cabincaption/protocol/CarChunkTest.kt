package io.cabincaption.protocol

import java.io.ByteArrayInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarChunkTest {

    @Test
    fun roundTripsThroughFrameCodec() {
        val pcm = ByteArray(CarChunk.CHUNK_BYTES) { it.toByte() }
        val f = CarChunk(291, 1_730_000_000_123, pcm).frame()
        assertEquals(FrameType.CAR_PCM, f.type)
        assertEquals(CarChunk.HEADER + CarChunk.CHUNK_BYTES, f.body.size)
        val back = FrameCodec.read(ByteArrayInputStream(FrameCodec.encode(f)))!!
        val c = CarChunk.parse(back.body)!!
        assertEquals(291L, c.seq)
        assertEquals(1_730_000_000_123L, c.captureMs)
        assertArrayEquals(pcm, c.pcm)
    }

    @Test
    fun seqIsUnsigned32BitBigEndian() {
        val f = CarChunk(0xFFFF_FFFEL, 0, ByteArray(2)).frame()
        assertEquals(0xFF.toByte(), f.body[0])
        assertEquals(0xFE.toByte(), f.body[3])
        assertEquals(0xFFFF_FFFEL, CarChunk.parse(f.body)!!.seq)
        assertEquals(0L, CarChunk.parse(CarChunk(1L shl 32, 0, ByteArray(2)).frame().body)!!.seq)
    }

    @Test
    fun rejectsEmptyOrOddPcm() {
        assertNull(CarChunk.parse(ByteArray(CarChunk.HEADER)))
        assertNull(CarChunk.parse(ByteArray(CarChunk.HEADER - 1)))
        assertNull(CarChunk.parse(ByteArray(CarChunk.HEADER + 3)))
    }

    @Test
    fun chunkIsHundredMillisAndFitsFrame() {
        assertEquals(CarChunk.RATE * 2 / 10, CarChunk.CHUNK_BYTES)
        assertTrue(CarChunk.HEADER + CarChunk.CHUNK_BYTES <= FrameCodec.MAX_BODY)
    }
}
